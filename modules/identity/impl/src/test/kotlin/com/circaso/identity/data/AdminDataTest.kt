package com.circaso.identity.data

import com.circaso.identity.MemoryCache
import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.DayCount
import com.circaso.identity.api.SessionId
import com.circaso.identity.data.PostgresIdentity.Companion.count
import com.circaso.identity.data.repositories.AccountAdminRepositoryImpl
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.usecases.CountActiveUsers
import com.circaso.identity.domain.usecases.CountSignUps
import com.circaso.identity.domain.usecases.FindAccountsByEmail
import com.circaso.identity.domain.usecases.ListNewestAccounts
import com.circaso.identity.domain.usecases.SuspendAccount
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.DomainException
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Staff reads on PostgreSQL (decision 2026-11-08): the keyset list, email search, the counts, and a suspension. */
class AdminDataTest {
    private val pg = PostgresIdentity()
    private val admin = AccountAdminRepositoryImpl(pg.database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() = PostgresIdentity.reset()

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    private suspend fun account(
        createdAt: Instant = now,
        status: AccountStatus = AccountStatus.ACTIVE,
        kind: AccountKind = AccountKind.PERSON,
    ): Account {
        val account = Account(UserId.new(), null, status, null, Verification.NONE, 1, createdAt, kind = kind)
        pg.transactor.run("test.create") { pg.accounts.create(account) }
        return account
    }

    private fun device(
        userId: UserId,
        lastSeenAt: Instant,
        status: DeviceStatus = DeviceStatus.ACTIVE,
    ) = Device(userId, DeviceId.new(), "android", "Pixel", "1.0.0", "key", "ES256", null, null, status, now, lastSeenAt)

    private fun session(userId: UserId, createdAt: Instant) =
        Session(SessionId.new(), userId, DeviceId.new(), "hash", Scope.APP, SessionStatus.ACTIVE, createdAt, createdAt)

    @Test
    fun `every account lists newest first by keyset, ties broken by id, and a bad cursor is refused`() =
        runBlocking<Unit> {
            val oldest = account(now.minusSeconds(30))
            val tied = listOf(account(now), account(now)).sortedByDescending { it.userId.value }
            val pending = account(now.minusSeconds(10), AccountStatus.PENDING_PHONE)
            val list = ListNewestAccounts(admin)

            val first = list(null, 2).data()
            val second = list(first.nextCursor, 2).data()

            assertEquals(tied.map { it.userId }, first.accounts.map { it.account.userId })
            assertEquals(listOf(pending.userId, oldest.userId), second.accounts.map { it.account.userId })
            assertNull(second.nextCursor)
            assertEquals("request.malformed", list("not-a-cursor", 2).errorCode())
        }

    @Test
    fun `an email finds its accounts whatever its case`() =
        runBlocking<Unit> {
            val amina = account()
            val other = account()
            pg.transactor.run("test.links") {
                pg.providers.claim(ProviderLink(amina.userId, SocialProvider.GOOGLE, "g-1", "Amina.O@Gmail.com", now))
                pg.providers.claim(ProviderLink(other.userId, SocialProvider.APPLE, "a-1", "someone@icloud.com", now))
            }

            assertEquals(listOf(amina.userId), FindAccountsByEmail(admin)(" amina.o@gmail.COM ").data())
            assertEquals(emptyList(), FindAccountsByEmail(admin)("nobody@gmail.com").data())
            assertEquals(
                "Amina.O@Gmail.com",
                admin
                    .links(setOf(amina.userId))
                    .getValue(amina.userId)
                    .single()
                    .email,
            )
        }

    @Test
    fun `sign-ups count people by UTC day with every day of the range, zeros included`() =
        runBlocking<Unit> {
            val day = LocalDate.of(2026, 10, 1)

            fun at(date: LocalDate, hour: Int) = date.atStartOfDay().plusHours(hour.toLong()).toInstant(ZoneOffset.UTC)
            account(at(day, 0))
            account(at(day, 23))
            account(at(day.plusDays(2), 12))
            account(at(day.plusDays(1), 12), AccountStatus.PENDING_PHONE)
            account(at(day.plusDays(1), 13), AccountStatus.DELETED)
            account(at(day.plusDays(1), 14), kind = AccountKind.SUPPORT)
            account(at(day.plusDays(1), 15), AccountStatus.SUSPENDED)
            account(at(day.plusDays(10), 1))

            val stats = CountSignUps(admin)(day, day.plusDays(3)).data()

            assertEquals(
                listOf(
                    DayCount(day, 2),
                    DayCount(day.plusDays(1), 1),
                    DayCount(day.plusDays(2), 1),
                    DayCount(day.plusDays(3), 0),
                ),
                stats.series,
            )
            assertEquals(4, stats.inRange)
            assertEquals(5, stats.total, "every finished, not deleted person sign-up of all time")
            assertFailsWith<DomainException> { CountSignUps(admin)(day, day.plusDays(366)) }
            assertFailsWith<DomainException> { CountSignUps(admin)(day, day.minusDays(1)) }
        }

    @Test
    fun `active people are counted once each, by their active phone's last seen time`() =
        runBlocking<Unit> {
            val today = account()
            val week = account()
            val month = account()
            val gone = account()
            pg.store.commit(
                Changes(
                    devices =
                        listOf(
                            device(today.userId, now.minus(Duration.ofHours(2))),
                            device(today.userId, now.minus(Duration.ofHours(3))),
                            device(week.userId, now.minus(Duration.ofDays(3))),
                            device(week.userId, now.minus(Duration.ofMinutes(5)), DeviceStatus.REPLACED),
                            device(month.userId, now.minus(Duration.ofDays(20))),
                            device(gone.userId, now.minus(Duration.ofDays(40))),
                        ),
                ),
            )

            val counts = CountActiveUsers(admin)(now).data()

            assertEquals(1, counts.day1)
            assertEquals(2, counts.day7)
            assertEquals(3, counts.day30)
        }

    @Test
    fun `touching a phone moves its last seen time forward only, and never on a replaced phone`() =
        runBlocking<Unit> {
            val amina = account()
            val active = device(amina.userId, now.minus(Duration.ofDays(2)))
            val replaced = device(amina.userId, now.minus(Duration.ofDays(2)), DeviceStatus.REPLACED)
            pg.store.commit(Changes(devices = listOf(active, replaced)))

            assertTrue(pg.devices.touch(amina.userId, active.deviceId, now))
            assertEquals(false, pg.devices.touch(amina.userId, active.deviceId, now.minusSeconds(60)))
            assertEquals(false, pg.devices.touch(amina.userId, replaced.deviceId, now))
            assertEquals(now, pg.devices.find(amina.userId, active.deviceId)!!.lastSeenAt)
        }

    @Test
    fun `an account's sessions list newest first, without the ones that ran out`() =
        runBlocking<Unit> {
            val amina = account()
            val older = session(amina.userId, now.minus(Duration.ofDays(2)))
            val newer = session(amina.userId, now.minus(Duration.ofHours(1)))
            val expired = session(amina.userId, now.minus(Duration.ofDays(31)))
            pg.store.commit(Changes(sessions = listOf(older, newer, expired)))

            val listed = admin.sessions(amina.userId, now, 10)

            assertEquals(listOf(newer.sessionId, older.sessionId), listed.map { it.session.sessionId })
            assertEquals(newer.rotatedAt.plus(Duration.ofDays(30)), listed.first().expiresAt)
        }

    @Test
    fun `a suspension on PostgreSQL ends the sessions and writes the account, its log line and the event together`() =
        runBlocking<Unit> {
            val amina = account()
            val live = session(amina.userId, now)
            pg.store.commit(Changes(sessions = listOf(live)))
            val cache = MemoryCache()

            SuspendAccount(pg.transactor, pg.accounts, pg.providers, pg.sessions, pg.store, cache, pg.bus)(
                amina.userId,
            ).data()

            assertEquals(AccountStatus.SUSPENDED, pg.accounts.find(amina.userId)!!.status)
            assertEquals(SessionStatus.REVOKED, pg.sessions.find(live.sessionId)!!.status)
            assertTrue(live.sessionId in cache.revoked)
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM identity.security_log WHERE type = 'SUSPENDED' AND user_id = '${amina.userId}'",
                ),
            )
            assertEquals(
                1,
                count("SELECT count(*) FROM identity.outbox WHERE type = 'identity.AccountStatusChanged'"),
            )
        }
}
