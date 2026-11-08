package com.circaso.wallet

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.DomainException
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.OpsTransferKind
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.support.HoldingPrices
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.StaffReports
import com.circaso.wallet.domain.usecases.GetStaffHoldings
import com.circaso.wallet.presentation.mappers.toOps
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The staff money reads' rules (decision 2026-11-08), and holdings over in-memory fakes of Circle. */
class WalletReportsTest {
    private val h = Harness()
    private val amina = UserId.new()
    private val holdings =
        GetStaffHoldings(h.wallets, HoldingsReader(h.custody, h.cache, h.warnings), HoldingPrices(h.prices, h.custody))

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun holding(
        amountMinor: BigInteger,
        decimals: Int,
        usdPrice: BigDecimal?,
    ) = Holding("X", "", decimals, usdPegged = false, amountMinor = amountMinor, usdPrice = usdPrice)

    // ---- micro-dollars ----

    @Test
    fun `a holding's worth is amount times price in micro-dollars, exactly, rounded half up once`() {
        assertEquals(12_345_678, StaffReports.usdMicros(holding(BigInteger.valueOf(12_345_678), 6, BigDecimal.ONE)))
        // 0.001 ETH at 3500 = 3.5 dollars
        assertEquals(3_500_000, StaffReports.usdMicros(holding(BigInteger.TEN.pow(15), 18, BigDecimal("3500"))))
        // 1 wei at 3500 is 0.0000000000000035 dollars: rounds to nothing; 5e11 wei at 1 dollar is 0.5 micro: up
        assertEquals(0, StaffReports.usdMicros(holding(BigInteger.ONE, 18, BigDecimal("3500"))))
        assertEquals(1, StaffReports.usdMicros(holding(BigInteger.valueOf(500_000_000_000), 18, BigDecimal.ONE)))
        assertNull(StaffReports.usdMicros(holding(BigInteger.TEN, 6, null)))
    }

    // ---- kinds ----

    private fun transfer() =
        Transfer(
            transferId = TransferId.new(),
            senderId = amina,
            senderWalletId = "w-1",
            blockchain = "BASE-SEPOLIA",
            idempotencyKey = UUID.randomUUID(),
            recipientId = UserId.new(),
            destinationAddress = "0xabc",
            amount = Money(1_000_000, Asset.USDC),
            state = TransferState.CONFIRMED,
            circleTxId = null,
            txHash = null,
            chatId = null,
            origin = TransferOrigin.APP,
            proposalId = null,
            failureReason = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `a transfer's kind says how it moved`() {
        val group = ChatId(UUID.randomUUID())
        assertEquals(OpsTransferKind.PERSON, StaffReports.kindOf(transfer()))
        assertEquals(OpsTransferKind.EXTERNAL, StaffReports.kindOf(transfer().copy(recipientId = null)))
        assertEquals(OpsTransferKind.GROUP_OUT, StaffReports.kindOf(transfer().copy(fromGroupId = group)))
        assertEquals(OpsTransferKind.GROUP_IN, StaffReports.kindOf(transfer().copy(toGroupId = group)))
        assertEquals(OpsTransferKind.GROUP_OUT, transfer().copy(fromGroupId = group).toOps().kind)
    }

    // ---- periods ----

    @Test
    fun `report periods are UTC days, ISO weeks from Monday and months, every one of the range`() {
        val from = Instant.parse("2026-10-29T22:00:00Z")
        val to = Instant.parse("2026-11-10T00:00:00Z")

        assertEquals(LocalDate.of(2026, 10, 29), StaffReports.periods(from, to, ReportBucket.DAY).first())
        assertEquals(12, StaffReports.periods(from, to, ReportBucket.DAY).size)
        assertEquals(
            listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9)),
            StaffReports.periods(from, to, ReportBucket.WEEK),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)),
            StaffReports.periods(from, to, ReportBucket.MONTH),
        )
    }

    @Test
    fun `a report range must run forwards and span at most 366 days`() {
        val from = Instant.parse("2026-01-01T00:00:00Z")
        StaffReports.checkRange(from, from.plusSeconds(366L * 86_400))
        assertFailsWith<DomainException> { StaffReports.checkRange(from, from.plusSeconds(366L * 86_400 + 1)) }
        assertFailsWith<DomainException> { StaffReports.checkRange(from, from) }
    }

    // ---- holdings ----

    @Test
    fun `holdings read Circle through the cache, price every coin, and never mark the holder active`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            val base = h.wallets.wallets(amina).single()
            h.custody.held[base.circleWalletId] =
                mapOf("USDC" to BigInteger.valueOf(2_500_000), "ETH" to BigInteger.TEN.pow(15))
            h.prices.prices["ETH"] = BigDecimal("3500")
            h.cache.values.clear()
            h.active.seen.clear()
            val listedBefore = h.custody.calls.count { it != "balances" }

            val seen = holdings(amina.value).data()

            assertTrue(seen.hasWallet)
            assertTrue(seen.balancesAvailable)
            val balances =
                seen.wallets
                    .single()
                    .balances
                    .associate { it.holding.symbol to it.usdMicros }
            assertEquals(mapOf("USDC" to 2_500_000L, "ETH" to 3_500_000L), balances)
            assertEquals(6_000_000, seen.totalUsdMicros)
            assertTrue(h.active.seen.isEmpty(), "a staff look doesn't mark the holder active")
            assertEquals(listedBefore, h.custody.calls.count { it != "balances" }, "no wallet sync, only balances")
        }

    @Test
    fun `when Circle can't be read the wallets are listed without balances, not as an error`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            h.cache.values.clear()
            h.custody.down = true

            val seen = holdings(amina.value).data()

            assertTrue(seen.hasWallet)
            assertFalse(seen.balancesAvailable)
            assertEquals(listOf("BASE-SEPOLIA"), seen.wallets.map { it.wallet.blockchain })
            assertTrue(
                seen.wallets
                    .single()
                    .balances
                    .isEmpty(),
            )
            assertNull(seen.totalUsdMicros)
        }

    @Test
    fun `no wallet is hasWallet false`() =
        runBlocking<Unit> {
            val seen = holdings(UUID.randomUUID()).data().toOps()

            assertFalse(seen.hasWallet)
            assertTrue(seen.wallets.isEmpty())
        }
}
