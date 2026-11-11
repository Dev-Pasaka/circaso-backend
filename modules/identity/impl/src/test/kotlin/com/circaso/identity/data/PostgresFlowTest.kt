package com.circaso.identity.data

import com.circaso.identity.Harness
import com.circaso.identity.IdentityPorts
import com.circaso.identity.Phone
import com.circaso.identity.api.AccountActivated
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.api.SignedOut
import com.circaso.identity.data.PostgresIdentity.Companion.count
import com.circaso.identity.domain.models.DeviceProof
import com.circaso.identity.domain.models.NextStep
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.RefreshCommand
import com.circaso.identity.domain.models.RegisterDeviceCommand
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun `two registrations of one phone racing, one activates the account and the other is told it is in progress`() =
        runBlocking<Unit> {
            // Both read the account before either transaction starts, as two taps arriving together do.
            val h = Harness(ports = pg.ports().copyWith(TogetherAt(pg.transactor, "devices.register", parties = 2)))
            val phone = Phone()
            val onboarding = h.principalOf(h.signIn("amina", phone).session.accessToken)
            val started =
                (
                    h.startPhone(
                        StartPhoneCommand(onboarding, PhoneNumber.parse(number)!!),
                    ) as Resource.Success
                ).data
            h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, h.sms.lastCode()))
            val command =
                RegisterDeviceCommand(
                    onboarding,
                    phone.deviceId,
                    phone.publicKey,
                    "ES256",
                    "fcm:pixel",
                    "attest",
                    "android",
                    phone.model,
                    "1.0.0",
                )

            val outcomes = (1..2).map { async(Dispatchers.Default) { h.registerDevice(command) } }.awaitAll()

            assertEquals(1, outcomes.count { it is Resource.Success })
            assertEquals(
                "request.in_progress",
                outcomes
                    .filterIsInstance<Resource.Error>()
                    .single()
                    .error.code,
            )
            assertEquals(1, count("SELECT count(*) FROM identity.outbox WHERE type = '${AccountActivated.TYPE}'"))
        }

    @Test
    fun `account deletion sets the end of its grace, and asking again changes and publishes nothing`() =
        runBlocking<Unit> {
            val h = harness()
            val registered = h.signUp("amina", Phone(), number)
            val principal = h.principalOf(registered.session.accessToken)
            val asked = Instant.now()

            assertTrue(h.delete(principal) is Resource.Success)
            val deleted = pg.accounts.find(principal.userId)!!
            assertEquals(AccountStatus.DELETED, deleted.status)
            val deleteAfter = assertNotNull(deleted.deleteAfter)
            assertTrue(Duration.between(asked.plus(Duration.ofDays(30)), deleteAfter).abs() < Duration.ofMinutes(1))

            assertTrue(h.delete(principal) is Resource.Success)
            assertEquals(deleteAfter, pg.accounts.find(principal.userId)!!.deleteAfter, "unchanged")
            assertEquals(1, count("SELECT count(*) FROM identity.outbox WHERE type = '${AccountStatusChanged.TYPE}'"))
            assertEquals(1, pg.bus.published.count { it.type == AccountStatusChanged.TYPE }, "published once")
        }

    @Test
    fun `two unlinks at once, of each of the two logins, keep one`() =
        runBlocking<Unit> {
            val h = harness()
            val registered = h.signUp("amina", Phone(), number)
            val principal = h.principalOf(registered.session.accessToken)
            pg.providers.claim(ProviderLink(principal.userId, SocialProvider.APPLE, "apple-amina", null, Instant.now()))

            val outcomes =
                listOf(SocialProvider.GOOGLE, SocialProvider.APPLE)
                    .map { provider -> async(Dispatchers.Default) { h.unlink(principal, provider) } }
                    .awaitAll()

            assertEquals(1, outcomes.count { it is Resource.Success })
            assertEquals(
                "identity.provider_last",
                outcomes
                    .filterIsInstance<Resource.Error>()
                    .single()
                    .error.code,
            )
            assertEquals(1, pg.providers.links(principal.userId).size)
        }
}

/** Holds every [operation] transaction until [parties] callers have reached it, so their reads all come first. */
private class TogetherAt(private val inner: Transactor, private val operation: String, private val parties: Int) :
    Transactor {
    private val arrived = AtomicInteger()
    private val everyone = CompletableDeferred<Unit>()

    override suspend fun <T> run(operation: String, block: suspend () -> T): T {
        if (operation == this.operation) {
            if (arrived.incrementAndGet() >= parties) everyone.complete(Unit)
            everyone.await()
        }
        return inner.run(operation, block)
    }

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> =
        inner.attempt(operation, block)
}

private fun IdentityPorts.copyWith(transactor: Transactor) =
    IdentityPorts(transactor, accounts, providers, devices, sessions, store, vaults)
