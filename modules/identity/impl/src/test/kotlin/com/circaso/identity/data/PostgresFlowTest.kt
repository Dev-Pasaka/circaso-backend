package com.circaso.identity.data

import com.circaso.identity.Harness
import com.circaso.identity.Phone
import com.circaso.identity.api.AccountActivated
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SignedOut
import com.circaso.identity.data.PostgresIdentity.Companion.count
import com.circaso.identity.domain.models.DeviceProof
import com.circaso.identity.domain.models.NextStep
import com.circaso.identity.domain.models.RefreshCommand
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The use cases on the PostgreSQL data layer (the in-memory flows are in `SignInFlowTest`): the sign-up to sign-out
 * path, and the races the transactions settle (two taps, two sign-ups for one number, two refreshes of one token).
 */
class PostgresFlowTest {
    private val pg = PostgresIdentity()
    private val number = "+254712345678"

    @BeforeTest
    fun clean() = PostgresIdentity.reset()

    private fun harness() = Harness(ports = pg.ports())

    private fun refreshCommand(phone: Phone, refresh: String): RefreshCommand {
        val stamp = Instant.now()
        val proof =
            DeviceProof(stamp, phone.sign(DeviceProofs.refreshPayload(refresh, DeviceProof(stamp, ByteArray(0)))))
        return RefreshCommand(refresh, phone.deviceId, proof)
    }

    @Test
    fun `sign-up, sign-in on the same phone, refresh and sign-out, with their events in the outbox`() =
        runBlocking<Unit> {
            val h = harness()
            val phone = Phone()

            h.signUp("amina", phone, number)
            val account = assertNotNull(pg.accounts.findByPhone(PhoneNumber.parse(number)!!))
            assertEquals(AccountStatus.ACTIVE, account.status)
            assertEquals(1, count("SELECT count(*) FROM identity.outbox WHERE type = '${AccountActivated.TYPE}'"))

            val again = h.signIn("amina", phone, proofFor = phone)
            assertEquals(NextStep.DONE, again.next)

            val principal = h.principalOf(again.session.accessToken)
            val refreshed = h.refresh(refreshCommand(phone, again.session.refreshToken!!))
            assertTrue(refreshed is Resource.Success)

            h.signOut(principal)
            h.signOut(principal)
            assertEquals(1, count("SELECT count(*) FROM identity.outbox WHERE type = '${SignedOut.TYPE}'"), "once")
            assertTrue(pg.log.list(account.userId, null, 50).size >= 4)
        }

    @Test
    fun `two taps on the sign-in button create one account`() =
        runBlocking<Unit> {
            val h = harness()

            val results = (1..8).map { async(Dispatchers.Default) { h.signIn("amina", Phone()) } }.awaitAll()

            assertTrue(results.all { it.next == NextStep.VERIFY_PHONE })
            assertEquals(1, count("SELECT count(*) FROM identity.accounts"))
            assertEquals(1, count("SELECT count(*) FROM identity.provider_links"))
            val owner = pg.providers.ownerOf(SocialProvider.GOOGLE, "amina")
            assertEquals(setOf(owner), results.map { h.principalOf(it.session.accessToken).userId }.toSet())
        }

    @Test
    fun `two sign-ups verifying one number, one gets it and the other is told it is taken`() =
        runBlocking<Unit> {
            val h = harness()
            val people =
                listOf("amina", "baraka").map { sub ->
                    val principal = h.principalOf(h.signIn(sub, Phone()).session.accessToken)
                    val started =
                        (
                            h.startPhone(
                                StartPhoneCommand(principal, PhoneNumber.parse(number)!!),
                            ) as Resource.Success
                        ).data
                    Triple(principal, started.verificationId, h.sms.lastCode())
                }

            val outcomes =
                people
                    .map { (principal, verificationId, code) ->
                        async(Dispatchers.Default) { h.verifyCode(VerifyCodeCommand(principal, verificationId, code)) }
                    }.awaitAll()

            assertEquals(1, outcomes.count { it is Resource.Success })
            assertEquals(
                "identity.phone_taken",
                outcomes
                    .filterIsInstance<Resource.Error>()
                    .single()
                    .error.code,
            )
            assertEquals(1, count("SELECT count(*) FROM identity.accounts WHERE phone = '$number'"))
        }

    @Test
    fun `two refreshes with one token, one rotates and the other is a reuse that ends the session`() =
        runBlocking<Unit> {
            val h = harness()
            val phone = Phone()
            val registered = h.signUp("amina", phone, number)
            val refresh = registered.session.refreshToken!!

            val outcomes =
                (1..2).map { async(Dispatchers.Default) { h.refresh(refreshCommand(phone, refresh)) } }.awaitAll()

            assertEquals(1, outcomes.count { it is Resource.Success })
            val sessionId = SessionOpener.sessionIdOf(refresh)!!
            assertEquals(SessionStatus.REVOKED, pg.sessions.find(sessionId)!!.status)
        }
}
