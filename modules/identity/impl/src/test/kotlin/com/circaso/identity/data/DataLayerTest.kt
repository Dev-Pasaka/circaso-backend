package com.circaso.identity.data

import com.circaso.identity.api.AccountActivated
import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SessionId
import com.circaso.identity.data.PostgresIdentity.Companion.count
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.RecoveryStatus
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.repositories.Changes
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Identity's repositories and store on PostgreSQL: every method, the claims and their races, paging, expiry. */
class DataLayerTest {
    private val pg = PostgresIdentity()
    private val number = PhoneNumber.parse("+254712345678")!!
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() = PostgresIdentity.reset()

    private fun pending(createdAt: Instant = now) =
        Account(UserId.new(), null, AccountStatus.PENDING_PHONE, null, Verification.NONE, 1, createdAt)

    private suspend fun created(account: Account = pending()): Account {
        pg.transactor.run("test.create") { pg.accounts.create(account) }
        return account
    }

    private fun link(
        userId: UserId,
        subject: String,
        provider: SocialProvider = SocialProvider.GOOGLE,
    ) = ProviderLink(userId, provider, subject, "$subject@example.com", now)

    private fun device(userId: UserId) =
        Device(userId, DeviceId.new(), "android", "Pixel", "1.0.0", "key", "ES256", null, DeviceStatus.ACTIVE, now, now)

    private fun session(userId: UserId, rotatedAt: Instant = now) =
        Session(SessionId.new(), userId, DeviceId.new(), "hash", Scope.APP, SessionStatus.ACTIVE, rotatedAt, rotatedAt)

    /** Claims the number for the account as the use cases do: in `attempt`, rolled back when it is lost. */
    private suspend fun setPhone(account: Account, phone: PhoneNumber = number): Boolean =
        pg.transactor.attempt("test.claim-phone") {
            val locked = pg.accounts.lock(account.userId)!!
            if (pg.accounts.claimPhone(locked, phone)) Resource.success(Unit) else Resource.error(SharedError.Internal)
        } is Resource.Success

    @Test
    fun `an account is created, found by id and by number, and each write moves its version on`() =
        runBlocking<Unit> {
            val account = created()
            assertEquals(account, pg.accounts.find(account.userId))

            setPhone(account)

            val stored = assertNotNull(pg.accounts.findByPhone(number))
            assertEquals(account.userId, stored.userId)
            assertEquals(1, stored.version)
            assertNull(pg.accounts.find(UserId.new()))
        }

    @Test
    fun `clearing the active device and the number writes real NULLs`() =
        runBlocking<Unit> {
            val account = created()
            val deviceId = DeviceId.new()
            setPhone(account)
            pg.transactor.run("test.activate") {
                val locked = pg.accounts.lock(account.userId)!!
                val active = locked.copy(status = AccountStatus.ACTIVE, activeDeviceId = deviceId)
                pg.store.write(Changes(accounts = listOf(active)))
            }
            pg.transactor.run("test.clear") {
                val locked = pg.accounts.lock(account.userId)!!
                pg.store.write(Changes(accounts = listOf(locked.copy(phone = null, activeDeviceId = null))))
            }

            val cleared = pg.accounts.find(account.userId)!!
            assertNull(cleared.phone)
            assertNull(cleared.activeDeviceId)
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM identity.accounts WHERE phone IS NULL AND active_device_id IS NULL " +
                        "AND user_id = '${account.userId}'",
                ),
            )
        }

    @Test
    fun `a stale copy of an account never overwrites a newer one`() =
        runBlocking<Unit> {
            val account = created()
            setPhone(account)

            assertFailsWith<IllegalStateException> {
                pg.transactor.run("test.stale") {
                    pg.store.write(Changes(accounts = listOf(account.copy(status = AccountStatus.ACTIVE))))
                }
            }
            assertEquals(AccountStatus.PENDING_PHONE, pg.accounts.find(account.userId)!!.status)
        }

    @Test
    fun `a number taken by another account is a lost claim, and the whole transaction rolls back`() =
        runBlocking<Unit> {
            val first = created()
            val second = created()
            setPhone(first)

            val lost =
                pg.transactor.attempt("test.claim") {
                    val locked = pg.accounts.lock(second.userId)!!
                    val verified = SecurityEvent(second.userId, null, SecurityEventType.PHONE_VERIFIED, null, null)
                    // written first, so the rollback has something to undo
                    pg.store.write(Changes(securityEvents = listOf(verified)))
                    if (pg.accounts.claimPhone(locked, number)) {
                        Resource.success(Unit)
                    } else {
                        Resource.error(SharedError.Internal)
                    }
                }

            assertTrue(lost is Resource.Error)
            assertEquals(first.userId, pg.accounts.findByPhone(number)!!.userId)
            assertNull(pg.accounts.find(second.userId)!!.phone)
            assertEquals(0, count("SELECT count(*) FROM identity.security_log"))
        }

    @Test
    fun `ten accounts racing for one number, exactly one gets it`() =
        runBlocking<Unit> {
            val racers = (1..10).map { created() }

            val outcomes =
                racers
                    .map { account ->
                        async(Dispatchers.Default) { setPhone(account) }
                    }.awaitAll()

            assertEquals(1, outcomes.count { it })
            assertEquals(1, count("SELECT count(*) FROM identity.accounts WHERE phone = '${number.e164}'"))
        }

    @Test
    fun `a login is claimed once, kept by its owner, refused to another account, and replaced by a second one`() =
        runBlocking<Unit> {
            val amina = created()
            val other = created()

            assertTrue(pg.providers.claim(link(amina.userId, "sub-1")))
            assertTrue(pg.providers.claim(link(amina.userId, "sub-1")), "already hers")
            assertFalse(pg.providers.claim(link(other.userId, "sub-1")), "another account's")
            assertEquals(amina.userId, pg.providers.ownerOf(SocialProvider.GOOGLE, "sub-1"))

            assertTrue(pg.providers.claim(link(amina.userId, "sub-2")), "a new Google login replaces the old one")
            assertEquals(listOf("sub-2"), pg.providers.links(amina.userId).map { it.subject })
            assertNull(pg.providers.ownerOf(SocialProvider.GOOGLE, "sub-1"), "the old login is free again")

            assertTrue(pg.providers.claim(link(amina.userId, "apple-1", SocialProvider.APPLE)))
            assertEquals(2, pg.providers.links(amina.userId).size)
        }

    @Test
    fun `a replacement whose row is gone by the time it runs reports false, not a link`() =
        runBlocking<Unit> {
            // What claim meets when the account's other login is deleted between its insert and ownerOf: nothing to
            // replace. Called directly, since that window can't be opened from outside.
            val amina = created()

            val replaced = pg.transactor.run("test.replace") { pg.providers.afterConflict(link(amina.userId, "sub-9")) }

            assertFalse(replaced)
            assertEquals(emptyList(), pg.providers.links(amina.userId))
        }

    @Test
    fun `ten accounts racing for one login, exactly one claim lands`() =
        runBlocking<Unit> {
            val racers = (1..10).map { created() }

            val claimed =
                racers
                    .map { account ->
                        async(Dispatchers.Default) {
                            pg.transactor.run("test.claim-login") { pg.providers.claim(link(account.userId, "shared")) }
                        }
                    }.awaitAll()

            assertEquals(1, claimed.count { it })
            assertEquals(1, count("SELECT count(*) FROM identity.provider_links WHERE subject = 'shared'"))
        }

    @Test
    fun `removing a provider link deletes its row and frees the login`() =
        runBlocking<Unit> {
            val amina = created()
            val google = link(amina.userId, "sub-1")
            pg.providers.claim(google)

            pg.store.commit(Changes(removedProviderLinks = listOf(google)))

            assertEquals(emptyList(), pg.providers.links(amina.userId))
            assertTrue(pg.providers.claim(link(created().userId, "sub-1")))
        }

    @Test
    fun `devices are written whole and found by account and id`() =
        runBlocking<Unit> {
            val amina = created()
            val pixel = device(amina.userId)
            pg.store.commit(Changes(devices = listOf(pixel)))
            pg.store.commit(Changes(devices = listOf(pixel.copy(pushToken = "fcm:1", status = DeviceStatus.REPLACED))))

            assertEquals(
                pixel.copy(pushToken = "fcm:1", status = DeviceStatus.REPLACED),
                pg.devices.find(amina.userId, pixel.deviceId),
            )
            assertNull(pg.devices.find(amina.userId, DeviceId.new()))
        }

    @Test
    fun `a session is found until 30 days after its last rotation`() =
        runBlocking<Unit> {
            val amina = created()
            val live = session(amina.userId)
            val old = session(amina.userId, rotatedAt = now.minus(Duration.ofDays(31)))
            pg.store.commit(Changes(sessions = listOf(live, old)))

            assertEquals(live, pg.sessions.find(live.sessionId))
            assertNull(pg.sessions.find(old.sessionId), "expired: hidden at once, before the purge")
            assertNull(pg.sessions.revoke(old.sessionId), "an expired session can't be revoked either")
        }

    @Test
    fun `ten sign-outs of one session at once, exactly one ends it`() =
        runBlocking<Unit> {
            val amina = created()
            val live = session(amina.userId)
            pg.store.commit(Changes(sessions = listOf(live)))

            val revoked =
                (1..10)
                    .map {
                        async(Dispatchers.Default) {
                            pg.transactor.run("test.revoke") { pg.sessions.revoke(live.sessionId) }
                        }
                    }.awaitAll()

            assertEquals(1, revoked.count { it != null })
            assertEquals(SessionStatus.REVOKED, pg.sessions.find(live.sessionId)!!.status)
        }

    @Test
    fun `the security log pages newest first by keyset`() =
        runBlocking<Unit> {
            val amina = created()
            val events =
                (0 until 5).map {
                    SecurityEvent(
                        amina.userId,
                        Uuid7.generate(now.plusMillis(it.toLong())),
                        SecurityEventType.SIGN_IN,
                        "Pixel",
                        "$it",
                    )
                }
            pg.store.commit(Changes(securityEvents = events))
            pg.store.commit(
                Changes(
                    securityEvents =
                        listOf(
                            SecurityEvent(created().userId, null, SecurityEventType.SIGN_IN, null, null),
                        ),
                ),
            )

            val first = pg.log.list(amina.userId, null, 2)
            val second = pg.log.list(amina.userId, first.last().eventId, 2)
            val third = pg.log.list(amina.userId, second.last().eventId, 2)

            assertEquals(listOf("4", "3"), first.map { it.detail })
            assertEquals(listOf("2", "1"), second.map { it.detail })
            assertEquals(listOf("0"), third.map { it.detail })
        }

    @Test
    fun `a support case is written whole and found by its request id`() =
        runBlocking<Unit> {
            val amina = created()
            val case =
                SupportRecovery(
                    amina.userId,
                    Uuid7.generate(),
                    RecoveryStatus.RECEIVED,
                    "amina@example.com",
                    "lost my Google account",
                    null,
                    null,
                    null,
                    null,
                    null,
                    SocialProvider.APPLE,
                    "apple-sub",
                )
            pg.store.commit(Changes(recoveries = listOf(case)))
            val noticed =
                case.copy(
                    status = RecoveryStatus.NOTICE_SENT,
                    noticeSentAt = now,
                    relinkAfter = now.plus(Duration.ofHours(48)),
                )
            pg.store.commit(Changes(recoveries = listOf(noticed)))

            assertEquals(noticed, pg.recoveries.find(case.requestId))
            assertNull(pg.recoveries.find(Uuid7.generate()))
        }

    @Test
    fun `rows and their outbox row land together, and a failed transaction leaves neither`() =
        runBlocking<Unit> {
            val amina = created()
            val event = pg.bus.envelope(AccountActivated(amina.userId, "hmac"), AccountActivated.serializer())

            assertFailsWith<IllegalStateException> {
                pg.transactor.run("test.fails") {
                    pg.store.write(Changes(devices = listOf(device(amina.userId)), events = listOf(event)))
                    error("something after the writes fails")
                }
            }
            assertEquals(0, count("SELECT count(*) FROM identity.outbox"))
            assertEquals(0, count("SELECT count(*) FROM identity.devices"))

            pg.store.commit(Changes(devices = listOf(device(amina.userId)), events = listOf(event)))

            assertEquals(1, count("SELECT count(*) FROM identity.devices"))
            assertEquals(1, count("SELECT count(*) FROM identity.outbox WHERE event_id = '${event.eventId.value}'"))
            assertEquals(
                listOf(event.eventId),
                pg.bus.published.map(EventEnvelope::eventId),
                "published after the commit",
            )
        }

    @Test
    fun `the purge deletes expired sessions only and keeps the outbox partitions a day ahead`() =
        runBlocking<Unit> {
            val amina = created()
            val live = session(amina.userId)
            val old = session(amina.userId, rotatedAt = now.minus(Duration.ofDays(31)))
            pg.store.commit(Changes(sessions = listOf(live, old)))

            val changed = pg.expired.purge()

            assertTrue(changed >= 1)
            assertEquals(1, count("SELECT count(*) FROM identity.sessions"))
            assertNotNull(pg.sessions.find(live.sessionId))
            assertTrue(
                count(
                    "SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'outbox'",
                ) >=
                    2,
                "today's and tomorrow's partitions exist",
            )
            assertEquals(0, pg.expired.purge(), "nothing left to do")
        }

    @Test
    fun `the sweep deletes old pending sign-ups with their logins and log, and frees their numbers`() =
        runBlocking<Unit> {
            val abandoned = created(pending(createdAt = now.minus(Duration.ofDays(31))))
            pg.providers.claim(link(abandoned.userId, "abandoned"))
            setPhone(abandoned)
            pg.store.commit(
                Changes(
                    securityEvents =
                        listOf(
                            SecurityEvent(abandoned.userId, null, SecurityEventType.PROVIDER_LINKED, null, null),
                        ),
                ),
            )
            val recent = created(pending(createdAt = now.minus(Duration.ofDays(2))))
            val activeOld =
                created(pending(createdAt = now.minus(Duration.ofDays(60))).copy(status = AccountStatus.ACTIVE))

            val deleted = pg.accounts.deleteAbandoned(now.minus(Duration.ofDays(30)), 500)

            assertEquals(1, deleted)
            assertNull(pg.accounts.find(abandoned.userId))
            assertNull(pg.providers.ownerOf(SocialProvider.GOOGLE, "abandoned"))
            assertEquals(0, count("SELECT count(*) FROM identity.security_log"))
            assertNull(pg.accounts.findByPhone(number), "the number is free again")
            assertNotNull(pg.accounts.find(recent.userId))
            assertNotNull(pg.accounts.find(activeOld.userId))
            assertEquals(0, pg.accounts.deleteAbandoned(now.minus(Duration.ofDays(30)), 500))
        }

    @Test
    fun `a sweep chunk never takes more than its limit`() =
        runBlocking<Unit> {
            repeat(5) { created(pending(createdAt = now.minus(Duration.ofDays(31 + it.toLong())))) }

            assertEquals(2, pg.accounts.deleteAbandoned(now.minus(Duration.ofDays(30)), 2))
            assertEquals(3, count("SELECT count(*) FROM identity.accounts"))
            assertEquals(2, pg.accounts.deleteAbandoned(now.minus(Duration.ofDays(30)), 2))
            assertEquals(1, pg.accounts.deleteAbandoned(now.minus(Duration.ofDays(30)), 2))
        }

    @Test
    fun `the sweep skips a sign-up whose phone claim is in flight, and the claim then lands`() =
        runBlocking<Unit> {
            val late = created(pending(createdAt = now.minus(Duration.ofDays(31))))
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val claim =
                async(Dispatchers.Default) {
                    pg.transactor.attempt("test.claim-in-flight") {
                        val account = pg.accounts.lock(late.userId)!!
                        locked.complete(Unit)
                        release.await()
                        if (pg.accounts.claimPhone(account, number)) {
                            Resource.success(Unit)
                        } else {
                            Resource.error(SharedError.Internal)
                        }
                    }
                }
            locked.await()

            val swept = pg.accounts.deleteAbandoned(now.minus(Duration.ofDays(30)), 500)
            release.complete(Unit)

            assertEquals(0, swept, "the row its claim holds is skipped, not waited for")
            assertTrue(claim.await() is Resource.Success)
            assertEquals(number, pg.accounts.find(late.userId)!!.phone)
        }

    @Test
    fun `an account written without a kind is a person's, and only support accounts are listed as support`() =
        runBlocking<Unit> {
            // As every row written before V2__account_kind: the insert names no kind, the column's default fills it.
            val older = UserId.new()
            TestPostgres.superuser { connection ->
                connection.createStatement().use {
                    it.execute(
                        "INSERT INTO identity.accounts (user_id, status, created_at, updated_at) " +
                            "VALUES ('$older', 'ACTIVE', now(), now())",
                    )
                }
            }
            assertEquals(AccountKind.PERSON, pg.accounts.find(older)?.kind)

            val person = created()
            val first = created(pending().copy(status = AccountStatus.ACTIVE, kind = AccountKind.SUPPORT))
            val second = created(pending().copy(status = AccountStatus.ACTIVE, kind = AccountKind.SUPPORT))

            assertEquals(AccountKind.PERSON, pg.accounts.find(person.userId)?.kind)
            assertEquals(AccountKind.SUPPORT, pg.accounts.find(first.userId)?.kind)
            // Ids made in the same millisecond can come out in either order; the list is in id order.
            val (lower, higher) = listOf(first.userId, second.userId).sortedBy { it.toString() }
            assertEquals(listOf(lower, higher), pg.accounts.supportAccounts(null, 10).map { it.userId })
            assertEquals(listOf(lower), pg.accounts.supportAccounts(null, 1).map { it.userId })
            assertEquals(listOf(higher), pg.accounts.supportAccounts(lower, 10).map { it.userId })
        }
}
