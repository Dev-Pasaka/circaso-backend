package com.circaso.identity

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.api.SupportProfile
import com.circaso.identity.api.SupportProfiles
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.DeviceProof
import com.circaso.identity.domain.models.NextStep
import com.circaso.identity.domain.models.RefreshCommand
import com.circaso.identity.domain.models.RegisterDeviceCommand
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.SignInCommand
import com.circaso.identity.domain.models.SignInResult
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.usecases.CreateSupportAccount
import com.circaso.identity.domain.usecases.ListSupportAccounts
import com.circaso.identity.domain.usecases.SuspendAccount
import com.circaso.identity.domain.usecases.UnsuspendAccount
import com.circaso.identity.presentation.api.SupportAccountsImpl
import com.circaso.identity.presentation.services.SupportAccountService
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.decode
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Staff suspending and giving back an account (decision 2026-11-08), and every way back in refused while it is
 * suspended; OPS1 and OPS2 through the `SupportAccounts` contract the ops module calls.
 */
class AccountAdminTest {
    private val number = "+254712345678"

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    private fun Harness.suspend() = SuspendAccount(store, store, store, store, store, cache, bus)

    private fun Harness.unsuspend() = UnsuspendAccount(store, store, store, store, bus)

    private suspend fun Harness.signInWithProof(sub: String, phone: Phone): Resource<SignInResult> {
        val nonce = nonce()
        val stamp = Instant.now()
        val proof = DeviceProof(stamp, phone.sign(DeviceProofs.signInPayload(nonce, DeviceProof(stamp, ByteArray(0)))))
        return signInWithProvider(
            SignInCommand(
                SocialProvider.GOOGLE,
                "dev:$sub:$sub@example.com:Test $sub",
                nonce,
                null,
                phone.info(),
                proof,
            ),
        )
    }

    private fun refreshCommand(phone: Phone, refresh: String): RefreshCommand {
        val stamp = Instant.now()
        val proof =
            DeviceProof(stamp, phone.sign(DeviceProofs.refreshPayload(refresh, DeviceProof(stamp, ByteArray(0)))))
        return RefreshCommand(refresh, phone.deviceId, proof)
    }

    // ---- suspend ----

    @Test
    fun `suspending ends every session at once, logs it and tells the other modules`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            val registered = h.signUp("amina", phone, number)
            val userId = registered.account.userId
            val sessionId = h.principalOf(registered.session.accessToken).sessionId
            h.store.events.clear()

            val suspended = h.suspend()(userId).data()

            assertEquals(AccountStatus.SUSPENDED, suspended.account.status)
            assertEquals(
                AccountStatus.SUSPENDED,
                h.store.accounts
                    .getValue(userId)
                    .status,
            )
            assertEquals(
                SessionStatus.REVOKED,
                h.store.sessions
                    .getValue(sessionId)
                    .status,
            )
            assertTrue(sessionId in h.cache.revoked, "the access token stops now, not in 15 minutes")
            assertEquals(IdentityError.SessionRevoked.code, h.verifyToken(registered.session.accessToken).errorCode())
            assertEquals(
                IdentityError.SessionRevoked.code,
                h.verifyToken(registered.agentSession.accessToken).errorCode(),
            )
            assertEquals(listOf(AccountStatusChanged.TYPE), h.store.eventTypes())
            assertEquals(
                AccountStatusChanged(userId, AccountStatus.SUSPENDED),
                h.store.events
                    .single()
                    .decode<AccountStatusChanged>(),
            )
            assertEquals(
                SecurityEventType.SUSPENDED,
                h.store.log
                    .last()
                    .type,
            )
            assertEquals(listOf("amina@example.com"), suspended.links.map { it.email })
        }

    @Test
    fun `only an active account can be suspended, and an unknown one is not found`() =
        runTest {
            val h = Harness()
            val registered = h.signUp("amina", Phone(), number)
            val pending = h.principalOf(h.signIn("baraka", Phone()).session.accessToken).userId
            h.suspend()(registered.account.userId).data()
            h.store.events.clear()

            assertEquals("identity.account_not_active", h.suspend()(registered.account.userId).errorCode())
            assertEquals("identity.account_not_active", h.suspend()(pending).errorCode())
            assertEquals("identity.account_not_found", h.suspend()(UserId.new()).errorCode())
            assertTrue(h.store.events.isEmpty(), "a refusal writes nothing")
        }

    // ---- no way back in while suspended ----

    @Test
    fun `a suspended account can't sign in, even on its own phone with its proof`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            val registered = h.signUp("amina", phone, number)
            h.suspend()(registered.account.userId).data()

            assertEquals(IdentityError.AccountSuspended.code, h.signInWithProof("amina", phone).errorCode())
        }

    @Test
    fun `a suspended account's refresh token no longer works`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            val registered = h.signUp("amina", phone, number)
            h.suspend()(registered.account.userId).data()

            val refused = h.refresh(refreshCommand(phone, registered.session.refreshToken!!))

            assertEquals(IdentityError.SessionRevoked.code, refused.errorCode())
        }

    @Test
    fun `an onboarding token from before the suspension can't register a phone and reactivate the account`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            h.signUp("amina", phone, number)
            // A new phone starts over before the suspension: an onboarding token and its number verified again.
            val newPhone = Phone("Galaxy")
            val onboarding = h.principalOf(h.signIn("amina", newPhone).session.accessToken)
            val started = h.startPhone(StartPhoneCommand(onboarding, PhoneNumber.parse(number)!!)).data()
            h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, h.sms.lastCode())).data()
            h.suspend()(onboarding.userId).data()

            val registered =
                h.registerDevice(
                    RegisterDeviceCommand(
                        onboarding,
                        newPhone.deviceId,
                        newPhone.publicKey,
                        "ES256",
                        null,
                        "attest",
                        "android",
                        newPhone.model,
                        "1.0.0",
                    ),
                )

            assertEquals(IdentityError.AccountSuspended.code, registered.errorCode())
            assertEquals(
                AccountStatus.SUSPENDED,
                h.store.accounts
                    .getValue(onboarding.userId)
                    .status,
            )
        }

    // ---- unsuspend ----

    @Test
    fun `giving the account back makes it active, logs it, and the person signs in again`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            val registered = h.signUp("amina", phone, number)
            val userId = registered.account.userId
            h.suspend()(userId).data()
            h.store.events.clear()

            val restored = h.unsuspend()(userId).data()

            assertEquals(AccountStatus.ACTIVE, restored.account.status)
            assertEquals(
                SecurityEventType.UNSUSPENDED,
                h.store.log
                    .last()
                    .type,
            )
            assertEquals(
                AccountStatusChanged(userId, AccountStatus.ACTIVE),
                h.store.events
                    .single()
                    .decode<AccountStatusChanged>(),
            )
            val stale = h.refresh(refreshCommand(phone, registered.session.refreshToken!!))
            assertEquals(IdentityError.SessionRevoked.code, stale.errorCode(), "the old sessions stay ended")
            val again = h.signInWithProof("amina", phone).data()
            assertEquals(NextStep.DONE, again.next)
        }

    @Test
    fun `only a suspended account can be given back`() =
        runTest {
            val h = Harness()
            val registered = h.signUp("amina", Phone(), number)

            assertEquals("identity.account_not_suspended", h.unsuspend()(registered.account.userId).errorCode())
            assertEquals("identity.account_not_found", h.unsuspend()(UserId.new()).errorCode())
        }

    // ---- refresh marks the phone seen ----

    @Test
    fun `a refresh moves the phone's last seen time forward, at most once an hour`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            val registered = h.signUp("amina", phone, number)
            val key = registered.account.userId to phone.deviceId
            val longAgo = Instant.now().minusSeconds(3 * 3600)
            h.store.devices[key] =
                h.store.devices
                    .getValue(key)
                    .copy(lastSeenAt = longAgo)

            val rotated = h.refresh(refreshCommand(phone, registered.session.refreshToken!!)).data()
            val seen =
                h.store.devices
                    .getValue(key)
                    .lastSeenAt
            h.refresh(refreshCommand(phone, rotated.session.refreshToken!!)).data()

            assertTrue(seen.isAfter(longAgo))
            assertEquals(
                seen,
                h.store.devices
                    .getValue(key)
                    .lastSeenAt,
                "a second refresh within the hour writes nothing",
            )
        }

    // ---- OPS1 and OPS2 through the SupportAccounts contract ----

    private class Profiles : SupportProfiles {
        val made = mutableMapOf<UserId, SupportProfile>()

        override suspend fun checkHandle(handle: String): Resource<String> =
            Resource.success(handle.removePrefix("@").lowercase())

        override suspend fun describe(ids: Set<UserId>): Map<UserId, SupportProfile> = made.filterKeys { it in ids }
    }

    private fun Harness.supportAccounts(profiles: SupportProfiles) =
        SupportAccountsImpl(
            SupportAccountService(
                CreateSupportAccount(store, store, store, profiles, bus, Clock.systemUTC()),
                ListSupportAccounts(store, profiles),
            ),
        )

    @Test
    fun `the ops module creates a support account, and the staff member is in its security log`() =
        runTest {
            val h = Harness()
            val support = h.supportAccounts(Profiles())

            val created = support.create("  Circaso Support ", "@Circaso", "staff-0193f2c1").data()

            assertEquals("Circaso Support", created.displayName)
            assertEquals("circaso", created.handle)
            assertEquals(
                "by staff-0193f2c1",
                h.store.log
                    .single { it.userId == created.userId }
                    .detail,
            )
        }

    @Test
    fun `the contract checks the request's shape before anything is written`() =
        runTest {
            val h = Harness()
            val support = h.supportAccounts(Profiles())

            assertEquals("request.malformed", support.create(" ", "circaso", "staff").errorCode())
            assertEquals("request.malformed", support.create("x".repeat(41), "circaso", "staff").errorCode())
            assertEquals("request.malformed", support.create("Circaso", " ", "staff").errorCode())
            assertEquals("request.malformed", support.create("Circaso", "circaso", " ").errorCode())
            assertTrue(h.store.accounts.isEmpty())
        }

    @Test
    fun `the ops module lists support accounts page by page, a name pending until users makes the profile`() =
        runTest {
            val h = Harness()
            val profiles = Profiles()
            val support = h.supportAccounts(profiles)
            // UUIDv7 made in the same millisecond come out in random order: the list is in id order, so take theirs.
            val (first, second) =
                listOf(
                    support.create("Circaso Support", "circaso", "staff").data(),
                    support.create("Circaso Help", "help", "staff").data(),
                ).sortedBy { it.userId.value }
            profiles.made[first.userId] = SupportProfile("Circaso Support", "circaso")

            val page = support.list(null, 1).data()
            val rest = support.list(page.nextCursor, 1).data()

            assertEquals(listOf(first.userId), page.accounts.map { it.userId })
            assertEquals("Circaso Support", page.accounts.single().displayName)
            assertEquals(listOf(second.userId), rest.accounts.map { it.userId })
            assertEquals("", rest.accounts.single().displayName)
            assertEquals(null, rest.nextCursor)
            assertEquals("request.malformed", support.list("nope", 10).errorCode())
        }
}
