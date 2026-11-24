package com.circaso.ramp

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.repositories.ReservationRepositoryImpl
import com.circaso.ramp.data.repositories.SettingsRepositoryImpl
import com.circaso.ramp.data.repositories.WithdrawalDestinationRepositoryImpl
import com.circaso.ramp.data.repositories.WithdrawalRepositoryImpl
import com.circaso.ramp.di.rampDatabaseSpec
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalDestination
import com.circaso.ramp.domain.models.WithdrawalState
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Float withdrawals on PostgreSQL (V13, 2026-11-24): the settings round trip, one live row per address whatever its
 * case, a withdrawal key that makes a repeat the same row, moves guarded by state, the day's total without failures,
 * and a withdrawal's hold counted in the merchant's reserved float until it is closed.
 */
class WithdrawalDataTest {
    private val database: Database = TestPostgres.database(rampDatabaseSpec)
    private val settingsRows = SettingsRepositoryImpl(database)
    private val destinations = WithdrawalDestinationRepositoryImpl(database)
    private val withdrawals = WithdrawalRepositoryImpl(database)
    private val reservations = ReservationRepositoryImpl(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val merchant = MerchantId.new()

    @BeforeTest
    fun clean() = runBlocking<Unit> { TestPostgres.truncate("ramp") }

    private fun destination(address: String = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed") =
        WithdrawalDestination(
            destinationId = Uuid7.generate(now),
            merchantId = merchant,
            network = TESTNET,
            blockchain = "BASE-SEPOLIA",
            address = address,
            label = "Ledger",
            createdAt = now,
            usableAt = now.plusSeconds(86_400),
        )

    private fun withdrawal(
        to: WithdrawalDestination,
        amountMinor: Long,
        key: String?,
        at: Instant = now,
    ) = Withdrawal(
        withdrawalId = Uuid7.generate(at),
        merchantId = merchant,
        network = TESTNET,
        blockchain = to.blockchain,
        destinationId = to.destinationId,
        destinationAddress = to.address,
        destinationLabel = to.label,
        amountMinor = amountMinor,
        state = WithdrawalState.REQUESTED,
        idempotencyKey = key,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `the withdrawal settings round trip`() =
        runBlocking {
            settingsRows.seed(RampSettings.defaults(TESTNET, now))
            val seeded = settingsRows.load().getValue(TESTNET)
            assertEquals(
                Triple(24, 1_000_000L, 0L),
                Triple(seeded.withdrawDestinationDelayHours, seeded.withdrawMinMinor, seeded.withdrawDailyMaxMinor),
            )
            val changed =
                seeded.copy(
                    withdrawDestinationDelayHours = 48,
                    withdrawMinMinor = 5_000_000,
                    withdrawDailyMaxMinor = 50_000_000,
                )
            settingsRows.update(changed, seeded.version, "staff", now)!!
            val read = settingsRows.load().getValue(TESTNET)
            assertEquals(
                Triple(48, 5_000_000L, 50_000_000L),
                Triple(read.withdrawDestinationDelayHours, read.withdrawMinMinor, read.withdrawDailyMaxMinor),
            )
        }

    @Test
    fun `one live row per address, whatever its case, and removing frees it`() =
        runBlocking {
            val saved = destination()
            assertTrue(destinations.insert(saved))
            assertFalse(destinations.insert(destination("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")))
            assertEquals(listOf(saved), destinations.live(merchant, TESTNET))

            assertTrue(destinations.remove(saved.destinationId, now))
            assertFalse(destinations.remove(saved.destinationId, now))
            assertTrue(destinations.live(merchant, TESTNET).isEmpty())
            assertTrue(destinations.insert(destination()))
            assertEquals(2, destinations.all(merchant, TESTNET, 10).size)
            assertEquals(now, destinations.find(saved.destinationId)!!.removedAt)
        }

    @Test
    fun `a withdrawal moves only forward, a repeated key adds nothing, and its hold is reserved until closed`() =
        runBlocking {
            val to = destination().also { destinations.insert(it) }
            val first = withdrawal(to, 10_000_000, "k1")
            assertTrue(withdrawals.insert(first))
            assertFalse(withdrawals.insert(withdrawal(to, 10_000_000, "k1")), "the same key is the same withdrawal")
            assertEquals(first, withdrawals.byKey(merchant, "k1"))
            reservations.reserveWithdrawal(first.withdrawalId, merchant, TESTNET, first.amountMinor, now)
            assertEquals(
                10_000_000L to 10_000_000L,
                reservations.openTotal(merchant, TESTNET) to reservations.withdrawingTotal(merchant, TESTNET),
            )

            assertFalse(withdrawals.complete(first.withdrawalId, "0xh", now), "not sent yet")
            assertTrue(withdrawals.sent(first.withdrawalId, "tx-1", now))
            assertFalse(withdrawals.sent(first.withdrawalId, "tx-2", now))
            withdrawals.noteHash(first.withdrawalId, "0xh", now)
            assertEquals("0xh", withdrawals.find(first.withdrawalId)!!.txHash)
            assertEquals(listOf(first.withdrawalId), withdrawals.open(TESTNET, 10).map { it.withdrawalId })
            assertTrue(withdrawals.complete(first.withdrawalId, "0xh", now))
            assertFalse(withdrawals.fail(first.withdrawalId, "late", now), "a completed withdrawal never fails")
            assertTrue(reservations.closeWithdrawal(first.withdrawalId, consumed = true, at = now))
            assertFalse(reservations.closeWithdrawal(first.withdrawalId, consumed = false, at = now))
            assertEquals(0L, reservations.withdrawingTotal(merchant, TESTNET))

            val second = withdrawal(to, 3_000_000, null, now.plusSeconds(1))
            assertTrue(withdrawals.insert(second))
            assertTrue(withdrawals.insert(withdrawal(to, 4_000_000, null, now.plusSeconds(2))), "no key, no clash")
            assertTrue(withdrawals.fail(second.withdrawalId, "refused", now))
            val failed = withdrawals.find(second.withdrawalId)!!
            assertEquals(WithdrawalState.FAILED to "refused", failed.state to failed.failureReason)
            assertNull(failed.custodyTxId)
            assertEquals(14_000_000L, withdrawals.totalSince(merchant, TESTNET, now.minusSeconds(1)), "failed left out")
            val page = withdrawals.page(merchant, TESTNET, 2, null)
            assertEquals(2, page.size)
            assertEquals(1, withdrawals.page(merchant, TESTNET, 2, page.last().withdrawalId).size)
        }
}
