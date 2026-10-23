package com.circaso.identity

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.DeviceProof
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.NextStep
import com.circaso.identity.domain.models.RefreshCommand
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.VerifyCarrierCommand
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.identity.domain.ports.AttestationMode
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignInFlowTest {

    private val number = "+254712345678"
    private fun phone(raw: String = number) = PhoneNumber.parse(raw)!!

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data
    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    @Test
    fun `sign-up from Google to an active account with a session and an agent token`() = runTest {
        val h = Harness()
        val phone = Phone()

        val first = h.signIn("amina", phone)
        assertEquals(NextStep.VERIFY_PHONE, first.next)
        assertEquals("onboarding", first.session.scope)
        val onboarding = h.principalOf(first.session.accessToken)
        assertEquals(Scope.ONBOARDING, onboarding.scope)

        val started = h.startPhone(StartPhoneCommand(onboarding, phone())).data()
        assertTrue(h.sms.texts.last().startsWith("Your Circaso code is"))
        val verified = h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, h.sms.lastCode())).data()
        assertEquals(NextStep.REGISTER_DEVICE, verified.next)
        assertEquals(phone().masked(), verified.maskedPhone)

        val registered = h.register(onboarding, phone)
        assertEquals(AccountStatus.ACTIVE, registered.account.status)
        assertEquals(Scope.APP, h.principalOf(registered.session.accessToken).scope)
        assertEquals(Scope.AGENT, h.principalOf(registered.agentSession.accessToken).scope)
        assertNotNull(registered.session.refreshToken)
        assertEquals(null, registered.agentSession.refreshToken, "the agent token has no refresh token of its own")
        assertEquals(listOf("identity.AccountActivated"), h.store.eventTypes())
        assertEquals("Test amina", h.cache.hints(registered.account.userId)?.displayName)
        assertEquals(listOf(com.circaso.integrations.api.EmailTemplate.WELCOME), h.email.sent.toList(), "a welcome email, once")
        assertEquals(listOf("welcome-${registered.account.userId}"), h.email.keys.toList(), "keyed by the account so a retry cannot send it twice")
    }

    @Test
    fun `no welcome email for someone who has no email address, and none when signing back in`() = runTest {
        val h = Harness()
        val phone = Phone()
        val registered = h.signUp("noemail", phone, number, email = null)
        assertTrue(h.email.sent.isEmpty(), "no address, so nothing is sent")

        val h2 = Harness()
        val phone2 = Phone()
        val account = h2.signUp("amina", phone2, number)
        h2.signOut(h2.principalOf(account.session.accessToken))
        h2.signIn("amina", phone2, proofFor = phone2)
        assertEquals(1, h2.email.sent.count { it == com.circaso.integrations.api.EmailTemplate.WELCOME }, "signing back in does not welcome again")
        assertNotNull(registered)
    }

    @Test
    fun `the same phone signing back in goes straight to home`() = runTest {
        val h = Harness()
        val phone = Phone()
        val registered = h.signUp("amina", phone, number)
        h.signOut(h.principalOf(registered.session.accessToken))

        val again = h.signIn("amina", phone, proofFor = phone)
        assertEquals(NextStep.DONE, again.next)
        assertEquals("app", again.session.scope)
        assertNotNull(again.session.refreshToken)
        assertNotNull(again.agentSession)
    }

    @Test
    fun `a proof from another key is not enough and the user verifies the number again`() = runTest {
        val h = Harness()
        val phone = Phone()
        h.signUp("amina", phone, number)

        val forged = h.signIn("amina", phone, proofFor = Phone())
        assertEquals(NextStep.REVERIFY_PHONE, forged.next)
        assertEquals("onboarding", forged.session.scope)
        assertEquals(phone().masked(), forged.maskedPhone)
    }

    @Test
    fun `a sign-out revokes the session on the deny-list`() = runTest {
        val h = Harness()
        val phone = Phone()
        val registered = h.signUp("amina", phone, number)
        val principal = h.principalOf(registered.session.accessToken)

        h.signOut(principal)

        assertEquals("identity.session_revoked", h.verifyToken(registered.session.accessToken).errorCode())
    }

    @Test
    fun `refresh rotates the token pair and an old refresh token ends the session`() = runTest {
        val h = Harness()
        val phone = Phone()
        val registered = h.signUp("amina", phone, number)
        val oldRefresh = registered.session.refreshToken!!

        fun command(refresh: String): RefreshCommand {
            val proof = DeviceProof(Instant.now(), ByteArray(0)).let { DeviceProof(it.timestamp, phone.sign(DeviceProofs.refreshPayload(refresh, it))) }
            return RefreshCommand(refresh, phone.deviceId, proof)
        }

        val rotated = h.refresh(command(oldRefresh)).data()
        assertNotEquals(oldRefresh, rotated.session.refreshToken)
        assertEquals(Scope.AGENT, h.principalOf(rotated.agentSession.accessToken).scope)

        // The old token again: someone holds a copy, so the whole session ends, the new token included.
        assertEquals("identity.session_revoked", h.refresh(command(oldRefresh)).errorCode())
        assertEquals("identity.session_revoked", h.refresh(command(rotated.session.refreshToken!!)).errorCode())
    }

    @Test
    fun `a refresh with a bad signature is refused`() = runTest {
        val h = Harness()
        val phone = Phone()
        val registered = h.signUp("amina", phone, number)
        val refresh = registered.session.refreshToken!!
        val stamp = Instant.now()
        val forged = DeviceProof(stamp, Phone().sign(DeviceProofs.refreshPayload(refresh, DeviceProof(stamp, ByteArray(0)))))

        assertEquals("identity.device_proof_invalid", h.refresh(RefreshCommand(refresh, phone.deviceId, forged)).errorCode())
    }

    @Test
    fun `a new phone verifies the same number, replaces the old phone and ends its session`() = runTest {
        val h = Harness()
        val old = Phone("Tecno")
        val registered = h.signUp("amina", old, number)
        val oldSession = h.principalOf(registered.session.accessToken).sessionId

        val fresh = Phone("Samsung")
        val signIn = h.signIn("amina", fresh)
        assertEquals(NextStep.REVERIFY_PHONE, signIn.next)
        val onboarding = h.principalOf(signIn.session.accessToken)
        val started = h.startPhone(StartPhoneCommand(onboarding, phone())).data()
        h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, h.sms.lastCode())).data()

        val replaced = h.register(onboarding, fresh)
        assertEquals("Tecno", replaced.replacedDevice?.model)
        assertEquals(DeviceStatus.REPLACED, h.store.devices[registered.account.userId to old.deviceId]?.status)
        assertEquals(fresh.deviceId, h.store.accounts[registered.account.userId]?.activeDeviceId)
        assertTrue(h.cache.isRevoked(oldSession), "the old phone's access tokens go on the deny-list")
        assertTrue("identity.DeviceReplaced" in h.store.eventTypes())
        assertTrue(h.email.sent.isNotEmpty(), "the owner is told by email")
    }

    @Test
    fun `a new phone cannot verify a different number`() = runTest {
        val h = Harness()
        h.signUp("amina", Phone(), number)
        val fresh = Phone()
        val onboarding = h.principalOf(h.signIn("amina", fresh).session.accessToken)

        assertEquals("identity.phone_mismatch", h.startPhone(StartPhoneCommand(onboarding, phone("+254733000111"))).errorCode())
    }

    @Test
    fun `one number belongs to one account`() = runTest {
        val h = Harness()
        h.signUp("amina", Phone(), number)

        val other = Phone()
        val onboarding = h.principalOf(h.signIn("brian", other).session.accessToken)
        val started = h.startPhone(StartPhoneCommand(onboarding, phone())).data()
        val result = h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, h.sms.lastCode()))

        assertEquals("identity.phone_taken", result.errorCode())
        assertTrue(h.cache.otp(started.verificationId)?.proven == true, "the SIM is proven, which a phone release builds on")
    }

    @Test
    fun `five wrong codes lock the account and the right code no longer works`() = runTest {
        val h = Harness()
        val onboarding = h.principalOf(h.signIn("amina", Phone()).session.accessToken)
        val started = h.startPhone(StartPhoneCommand(onboarding, phone())).data()

        repeat(4) { assertEquals("identity.code_invalid", h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, "000000")).errorCode()) }
        assertEquals("identity.code_locked", h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, "000000")).errorCode())
        assertEquals("identity.code_locked", h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, h.sms.lastCode())).errorCode())
    }

    @Test
    fun `an SMS provider outage answers identity sms_unavailable and leaves no code behind`() = runTest {
        val h = Harness()
        val onboarding = h.principalOf(h.signIn("amina", Phone()).session.accessToken)
        h.sms.down = true

        assertEquals("identity.sms_unavailable", h.startPhone(StartPhoneCommand(onboarding, phone())).errorCode())
        assertTrue(h.cache.otps.isEmpty())
    }

    @Test
    fun `the carrier path verifies without an SMS`() = runTest {
        val h = Harness()
        val onboarding = h.principalOf(h.signIn("amina", Phone()).session.accessToken)

        val verified = h.verifyCarrier(VerifyCarrierCommand(onboarding, number)).data()
        assertEquals(NextStep.REGISTER_DEVICE, verified.next)
        assertTrue(h.sms.texts.isEmpty())
    }

    @Test
    fun `a nonce works once`() = runTest {
        val h = Harness()
        val nonce = h.nonce()
        assertTrue(h.cache.consumeNonce(nonce))
        assertTrue(!h.cache.consumeNonce(nonce))
    }

    @Test
    fun `production attestation that fails blocks the device and no session is issued`() = runTest {
        val h = Harness(attestation = AttestationMode.ENFORCE, attested = false)
        val phone = Phone()
        val onboarding = h.principalOf(h.signIn("amina", phone).session.accessToken)
        val started = h.startPhone(StartPhoneCommand(onboarding, phone())).data()
        h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, h.sms.lastCode()))

        val result = h.registerDevice(
            com.circaso.identity.domain.models.RegisterDeviceCommand(onboarding, phone.deviceId, phone.publicKey, "ES256", null, null, "android", "Pixel", "1.0.0"),
        )

        assertEquals("identity.device_attestation_failed", result.errorCode())
        assertTrue(h.store.sessions.isEmpty())
    }

    @Test
    fun `registering needs the number verified first`() = runTest {
        val h = Harness()
        val phone = Phone()
        val onboarding = h.principalOf(h.signIn("amina", phone).session.accessToken)

        val result = h.registerDevice(
            com.circaso.identity.domain.models.RegisterDeviceCommand(onboarding, phone.deviceId, phone.publicKey, "ES256", null, null, "android", "Pixel", "1.0.0"),
        )
        assertIs<Resource.Error>(result)
    }

    @Test
    fun `a sign-in account can be unlinked only while another stays`() = runTest {
        val h = Harness()
        val registered = h.signUp("amina", Phone(), number)
        val principal = h.principalOf(registered.session.accessToken)

        assertEquals("identity.provider_last", h.unlink(principal, com.circaso.integrations.api.SocialProvider.GOOGLE).errorCode())
    }
}
