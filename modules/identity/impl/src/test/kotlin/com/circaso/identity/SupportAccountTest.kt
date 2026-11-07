package com.circaso.identity

import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SessionId
import com.circaso.identity.api.SupportAccountCreated
import com.circaso.identity.api.SupportProfile
import com.circaso.identity.api.SupportProfiles
import com.circaso.identity.data.security.Ed25519Tokens
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.CreateSupportAccountCommand
import com.circaso.identity.domain.models.DeviceProof
import com.circaso.identity.domain.models.RefreshCommand
import com.circaso.identity.domain.models.RegisterDeviceCommand
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SignInCommand
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.models.VerifyCarrierCommand
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.identity.domain.usecases.CreateSupportAccount
import com.circaso.identity.domain.usecases.GetAccountKind
import com.circaso.identity.domain.usecases.ListSupportAccounts
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.DomainError
import com.circaso.shared.DomainException
import com.circaso.shared.ErrorKind
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.decode
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Support accounts (decision 2026-10-31): made by ops, never given an `app` session by any path. */
class SupportAccountTest {
    private val number = "+254712345678"
    private val clock = Clock.systemUTC()

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    /** Users' side of the contract: handles it knows are taken, the profiles it has made. */
    private class FakeSupportProfiles : SupportProfiles {
        val taken = mutableSetOf<String>()
        val made = mutableMapOf<UserId, SupportProfile>()

        override suspend fun checkHandle(handle: String): Resource<String> {
            val stored = handle.removePrefix("@").lowercase()
            if (stored in taken) return Resource.error(UsersCode("users.handle_taken", ErrorKind.CONFLICT))
            return Resource.success(stored)
        }

        override suspend fun describe(ids: Set<UserId>): Map<UserId, SupportProfile> = made.filterKeys { it in ids }
    }

    private data class UsersCode(override val code: String, override val kind: ErrorKind) : DomainError {
        override val message: String = code
    }

    private class Ops(val h: Harness) {
        val profiles = FakeSupportProfiles()
        val create = CreateSupportAccount(h.store, h.store, h.store, profiles, h.bus, Clock.systemUTC())
        val list = ListSupportAccounts(h.store, profiles)
        val operator = "staff-0193f2c1"

        suspend fun support(handle: String = "circaso"): UserId =
            (create(CreateSupportAccountCommand(operator, "Circaso Support", handle)) as Resource.Success)
                .data.account.userId
    }

    /** Turns [userId] into a support account behind the use cases' backs, as if a path had been missed. */
    private fun Harness.makeSupport(userId: UserId) {
        store.accounts[userId] = store.accounts.getValue(userId).copy(kind = AccountKind.SUPPORT)
    }

    private suspend fun Harness.signInCommand(
        sub: String,
        phone: Phone,
        proof: Boolean,
    ): SignInCommand {
        val nonce = nonce()
        val signed =
            if (proof) {
                val stamp = Instant.now()
                DeviceProof(stamp, phone.sign(DeviceProofs.signInPayload(nonce, DeviceProof(stamp, ByteArray(0)))))
            } else {
                null
            }
        return SignInCommand(
            SocialProvider.GOOGLE,
            "dev:$sub:$sub@example.com:Test $sub",
            nonce,
            null,
            phone.info(),
            signed,
        )
    }

    // ---- the kind ----

    @Test
    fun `every account is a person's unless ops made it, including those made before kinds existed`() =
        runTest {
            val h = Harness()
            val registered = h.signUp("amina", Phone(), number)

            assertEquals(AccountKind.PERSON, registered.account.kind)
            assertEquals(
                AccountKind.PERSON,
                h.store.accounts
                    .getValue(registered.account.userId)
                    .kind,
            )
            val older =
                Account(UserId.new(), null, AccountStatus.ACTIVE, null, Verification.NONE, 1, Instant.now())
            assertEquals(AccountKind.PERSON, older.kind, "an account read without a kind is a person's")
            assertEquals(AccountKind.PERSON, GetAccountKind(h.store)(registered.account.userId).data())
            assertNull(GetAccountKind(h.store)(UserId.new()).data())
        }

    // ---- ops ----

    @Test
    fun `ops creates an active support account with no phone, device or login, and tells users`() =
        runTest {
            val h = Harness()
            val ops = Ops(h)

            val created =
                ops.create(CreateSupportAccountCommand(ops.operator, "Circaso Support", "@Circaso")).data()

            val account = h.store.accounts.getValue(created.account.userId)
            assertEquals(AccountKind.SUPPORT, account.kind)
            assertEquals(AccountStatus.ACTIVE, account.status)
            assertNull(account.phone)
            assertNull(account.activeDeviceId)
            assertTrue(h.store.links.none { it.userId == account.userId }, "no Google or Apple login to sign in with")
            assertEquals("circaso", created.handle)
            assertEquals(listOf(SupportAccountCreated.TYPE), h.store.eventTypes())
            val event =
                h.store.events
                    .single()
                    .decode<SupportAccountCreated>()
            assertEquals(SupportAccountCreated(account.userId, "Circaso Support", "circaso"), event)
            val audit = h.store.log.single { it.userId == account.userId }
            assertEquals(SecurityEventType.SUPPORT_ACCOUNT_CREATED, audit.type)
            assertEquals("by ${ops.operator}", audit.detail)
            assertEquals(GetAccountKind(h.store)(account.userId).data(), AccountKind.SUPPORT)
        }

    @Test
    fun `a handle users refuses creates nothing`() =
        runTest {
            val h = Harness()
            val ops = Ops(h)
            ops.profiles.taken += "circaso"

            val refused = ops.create(CreateSupportAccountCommand(ops.operator, "Circaso Support", "circaso"))

            assertEquals("users.handle_taken", refused.errorCode())
            assertTrue(h.store.accounts.isEmpty())
            assertTrue(h.store.events.isEmpty())
        }

    @Test
    fun `ops lists support accounts oldest first, page by page, with their profiles and no people`() =
        runTest {
            val h = Harness()
            val ops = Ops(h)
            h.signUp("amina", Phone(), number)
            // UUIDv7 made in the same millisecond come out in random order: the list is in id order, so take theirs.
            val (first, second) = listOf(ops.support("circaso"), ops.support("help")).sortedBy { it.value }
            ops.profiles.made[first] = SupportProfile("Circaso Support", "circaso")

            val page = ops.list(null, 1).data()
            assertEquals(listOf(first), page.accounts.map { it.account.userId })
            assertEquals("Circaso Support", page.accounts.single().displayName)
            assertEquals(first.toString(), page.nextCursor)

            val rest = ops.list(page.nextCursor, 1).data()
            assertEquals(listOf(second), rest.accounts.map { it.account.userId })
            assertNull(rest.accounts.single().displayName, "users hasn't made this profile yet")
            assertNull(rest.nextCursor)
            assertEquals("request.malformed", ops.list("nope", 10).errorCode())
        }

    // ---- no app session, by any path ----

    @Test
    fun `Google or Apple sign-in on a support account is refused, with or without a device proof`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            val registered = h.signUp("support", phone, number)
            h.makeSupport(registered.account.userId)

            val withProof = h.signInWithProvider(h.signInCommand("support", phone, proof = true))
            val withoutProof = h.signInWithProvider(h.signInCommand("support", Phone(), proof = false))

            assertEquals(IdentityError.SupportAccountNotOnMobile.code, withProof.errorCode())
            assertEquals(IdentityError.SupportAccountNotOnMobile.code, withoutProof.errorCode())
            assertEquals(
                ErrorKind.FORBIDDEN,
                ((withProof as Resource.Error).error).kind,
            )
        }

    @Test
    fun `phone OTP for a support account is refused, both sending and checking the code`() =
        runTest {
            val h = Harness()
            val onboarding = h.principalOf(h.signIn("support", Phone()).session.accessToken)
            val started =
                h.startPhone(StartPhoneCommand(onboarding, PhoneNumber.parse(number)!!)).data()
            h.makeSupport(onboarding.userId)

            val sent = h.startPhone(StartPhoneCommand(onboarding, PhoneNumber.parse(number)!!))
            val checked = h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, h.sms.lastCode()))
            val carrier = h.verifyCarrier(VerifyCarrierCommand(onboarding, number))

            assertEquals(IdentityError.SupportAccountNotOnMobile.code, sent.errorCode())
            assertEquals(IdentityError.SupportAccountNotOnMobile.code, checked.errorCode())
            assertEquals(IdentityError.SupportAccountNotOnMobile.code, carrier.errorCode())
            assertNull(
                h.store.accounts
                    .getValue(onboarding.userId)
                    .phone,
                "the number was never claimed",
            )
        }

    @Test
    fun `registering a phone for a support account is refused, so no app session is issued`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            val onboarding = h.principalOf(h.signIn("support", phone).session.accessToken)
            val started = h.startPhone(StartPhoneCommand(onboarding, PhoneNumber.parse(number)!!)).data()
            h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, h.sms.lastCode())).data()
            h.makeSupport(onboarding.userId)

            val registered =
                h.registerDevice(
                    RegisterDeviceCommand(
                        onboarding,
                        phone.deviceId,
                        phone.publicKey,
                        "ES256",
                        null,
                        "attest",
                        "android",
                        phone.model,
                        "1.0.0",
                    ),
                )

            assertEquals(IdentityError.SupportAccountNotOnMobile.code, registered.errorCode())
            assertTrue(h.store.sessions.isEmpty())
        }

    @Test
    fun `a refresh for a support account is refused`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            val registered = h.signUp("support", phone, number)
            h.makeSupport(registered.account.userId)
            val refresh = registered.session.refreshToken!!
            val stamp = Instant.now()
            val proof =
                DeviceProof(stamp, phone.sign(DeviceProofs.refreshPayload(refresh, DeviceProof(stamp, ByteArray(0)))))

            val refused = h.refresh(RefreshCommand(refresh, phone.deviceId, proof))

            assertEquals(IdentityError.SupportAccountNotOnMobile.code, refused.errorCode())
        }

    @Test
    fun `the session opener itself never issues an app session to a support account`() =
        runTest {
            val h = Harness()
            val secrets =
                object : Secrets {
                    override fun get(name: String): String? = null
                }
            val opener = SessionOpener(Ed25519Tokens(secrets, local = true, clock = clock), h.store, h.cache, clock)
            val support =
                Account(
                    UserId.new(),
                    null,
                    AccountStatus.ACTIVE,
                    null,
                    Verification.NONE,
                    1,
                    Instant.now(),
                    kind = AccountKind.SUPPORT,
                )

            val refused =
                assertFailsWith<DomainException> {
                    opener.open(support, DeviceId.new(), previous = null as SessionId?)
                }
            assertEquals(IdentityError.SupportAccountNotOnMobile, refused.error)
        }
}
