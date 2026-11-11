package com.circaso.identity.data

import com.circaso.identity.api.AccountDeleted
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SessionId
import com.circaso.identity.data.PostgresIdentity.Companion.count
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.PinVault
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.usecases.CompleteDeletions
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.decode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `identity.complete-deletions` (decision 2026-10-27): an account `DELETED` past its 30-day grace loses every identity
 * row, and `AccountDeleted` is written to the outbox in the same transaction, once, however often or widely it runs.
 */
class CompleteDeletionsTest {
    private val pg = PostgresIdentity()
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val job = CompleteDeletions(pg.transactor, pg.accounts, pg.store, pg.bus, Clock.systemUTC())

    @BeforeTest
    fun clean() = PostgresIdentity.reset()

    /**
     * An account with a number, a login, a phone, a session, a log entry and a PIN Vault, deleted with its grace ending
     * [at].
     */
    private suspend fun deleted(number: String, at: Instant?): UserId {
        val userId = UserId.new()
        val account = Account(userId, null, AccountStatus.PENDING_PHONE, null, Verification.NONE, 1, now)
        pg.transactor.run("test.create") {
            pg.accounts.create(account)
            pg.providers.claim(ProviderLink(userId, SocialProvider.GOOGLE, "sub-$number", null, now))
            val deviceId = DeviceId.new()
            pg.store.write(
                Changes(
                    accounts =
                        listOf(
                            account.copy(
                                phone = PhoneNumber.parse(number),
                                status = if (at == null) AccountStatus.ACTIVE else AccountStatus.DELETED,
                                deleteAfter = at,
                            ),
                        ),
                    devices =
                        listOf(
                            Device(
                                userId,
                                deviceId,
                                "android",
                                "Pixel",
                                "1",
                                "key",
                                "ES256",
                                null,
                                null,
                                DeviceStatus.ACTIVE,
                                now,
                                now,
                            ),
                        ),
                    sessions =
                        listOf(
                            Session(SessionId.new(), userId, deviceId, "h", Scope.APP, SessionStatus.REVOKED, now, now),
                        ),
                    securityEvents =
                        listOf(
                            SecurityEvent(userId, null, SecurityEventType.DELETION_REQUESTED, null, null),
                        ),
                    vaults = listOf(PinVault(userId, "cred", "c2FsdA==", "Y2lwaGVy", "prf/v1", 1, now)),
                ),
            )
        }
        return userId
    }

    private fun rowsOf(userId: UserId): Int =
        listOf(
            "accounts",
            "provider_links",
            "devices",
            "sessions",
            "security_log",
            "support_recoveries",
            "pin_vaults",
        ).sumOf {
            count("SELECT count(*) FROM identity.$it WHERE user_id = '$userId'")
        }

    private fun outboxRows(): Int = count("SELECT count(*) FROM identity.outbox WHERE type = '${AccountDeleted.TYPE}'")

    @Test
    fun `only deletions whose grace has ended are completed, each with one AccountDeleted in the outbox`() =
        runBlocking<Unit> {
            val due = deleted("+254700000001", at = now.minus(Duration.ofMinutes(1)))
            val inGrace = deleted("+254700000002", at = now.plus(Duration.ofDays(29)))
            val active = deleted("+254700000003", at = null)

            assertEquals(Resource.success(1), job())

            assertEquals(0, rowsOf(due), "every identity row of the account is gone")
            assertNull(pg.accounts.findByPhone(PhoneNumber.parse("+254700000001")!!), "its number is free")
            assertNotNull(pg.accounts.find(inGrace))
            assertNotNull(pg.accounts.find(active))
            assertEquals(1, outboxRows())
            assertEquals(
                AccountDeleted(due),
                pg.bus.published
                    .single()
                    .decode<AccountDeleted>(),
            )
        }

    @Test
    fun `a second run finds nothing to do`() =
        runBlocking<Unit> {
            deleted("+254700000001", at = now.minus(Duration.ofMinutes(1)))

            assertEquals(Resource.success(1), job())
            assertEquals(Resource.success(0), job())
            assertEquals(1, outboxRows())
        }

    @Test
    fun `an account restored before its grace ended is left alone`() =
        runBlocking<Unit> {
            val restored = deleted("+254700000001", at = now.minus(Duration.ofMinutes(1)))
            pg.transactor.run("test.restore") {
                val account = pg.accounts.lock(restored)!!
                pg.store.write(
                    Changes(accounts = listOf(account.copy(status = AccountStatus.ACTIVE, deleteAfter = null))),
                )
            }

            assertEquals(Resource.success(0), job())
            assertEquals(AccountStatus.ACTIVE, pg.accounts.find(restored)!!.status)
            assertEquals(0, outboxRows())
        }

    @Test
    fun `jobs racing on several nodes complete each account once`() =
        runBlocking<Unit> {
            val accounts = (1..5).map { deleted("+25470000000$it", at = now.minus(Duration.ofMinutes(1))) }

            val completed =
                (1..4)
                    .map { async(Dispatchers.Default) { (job() as Resource.Success).data } }
                    .awaitAll()

            assertEquals(5, completed.sum())
            accounts.forEach { assertEquals(0, rowsOf(it)) }
            assertEquals(5, outboxRows())
            assertEquals(5, pg.bus.published.size)
        }
}
