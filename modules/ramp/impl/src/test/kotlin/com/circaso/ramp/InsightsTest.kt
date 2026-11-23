package com.circaso.ramp

import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsRampOrderCursor
import com.circaso.ramp.api.OpsRampPeriod
import com.circaso.ramp.api.RampAmount
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.MerchantFigures
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.StaffOrderFilter
import com.circaso.ramp.domain.support.Periods
import com.circaso.ramp.domain.usecases.GetMerchantVerification
import com.circaso.ramp.domain.usecases.ListCollectionProviders
import com.circaso.ramp.domain.usecases.ListKycProviders
import com.circaso.ramp.domain.usecases.ListPreviewAccess
import com.circaso.ramp.domain.usecases.ResetMerchantVerification
import com.circaso.ramp.presentation.api.RampAdminImpl
import com.circaso.ramp.presentation.mappers.toResponse
import com.circaso.ramp.presentation.services.RampAdminService
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.getOrNull
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Staff's view of each merchant's activity. The rule it rests on: a merchant's figures for staff are the merchant's
 * own, from the same rows by the same computation as their earnings overview (RM8) and statement (RM15), so the admin
 * and the portal can never show two different numbers for one thing.
 */
class InsightsTest {
    private val ramp = Harness()
    private val admin =
        RampAdminImpl(
            RampAdminService(
                listMerchants = ramp.listMerchants,
                decideMerchant = ramp.decideMerchant,
                getSettings = ramp.getSettings,
                updateSettingsUseCase = ramp.updateSettings,
                listPreviewAccess = ListPreviewAccess(ramp.preview),
                setPreviewAccessUseCase = ramp.setPreview,
                listReviewOrders = ramp.reviewOrders,
                listCollectionProviders = ListCollectionProviders(ramp.providers),
                getRampOverview = ramp.overview,
                getMerchantSummary = ramp.merchantSummary,
                listStaffMerchantOrders = ramp.staffOrders,
                listKycProviders = ListKycProviders(ramp.kycProviders),
                getUserVerifications = ramp.userVerifications,
                getMerchantVerification = GetMerchantVerification(ramp.verificationRows, ramp.book),
                listBlocks = ramp.listBlocks,
                blockUser = ramp.blockUser,
                unblockUser = ramp.unblockUser,
                resetVerification = ramp.resetVerification,
                resetMerchantVerificationUseCase = ResetMerchantVerification(ramp.merchants, ramp.verificationRows),
                resolveOrder = ramp.resolveOrder,
            ),
        )
    private var buyers = 1

    private fun phoneOf(number: String) = PhoneNumber.parse(number)!!

    /** A new buyer each time: a user has one open order, and the daily limit is theirs. */
    private fun buyer(): Pair<com.circaso.shared.UserId, PhoneNumber> {
        buyers++
        val number = "+2547000000${buyers.toString().padStart(2, '0')}"
        return ramp.user(number) to phoneOf(number)
    }

    private suspend fun completed(shillings: Long = 1_300): Order {
        val (user, phone) = buyer()
        return ramp.completed(user, phone, shillings).also { ramp.clock.advance(60) }
    }

    private suspend fun failed(): Order {
        val (user, phone) = buyer()
        val order = ramp.prompted(user, phone)
        ramp.collections.fail(order)
        ramp.callback(order)
        ramp.clock.advance(60)
        return ramp.now(order)
    }

    private suspend fun open(): Order {
        val (user, phone) = buyer()
        return ramp.prompted(user, phone).also { ramp.clock.advance(60) }
    }

    private suspend fun sweep() {
        ramp.settle()
        ramp.treasury.settleAll()
        ramp.settle()
    }

    /** Two merchants taking turns: completed orders either side of a sweep, a failed one and one still open. */
    private suspend fun busyMonth(): List<MerchantId> {
        val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
        completed(1_300)
        completed(2_600)
        completed(1_900)
        sweep()
        completed(3_100)
        completed(1_000)
        failed()
        open()
        return listOf(ramp.crynce.merchantId, bidii.merchantId)
    }

    @Test
    fun `a merchant's figures for staff are exactly the merchant's own earnings, for the same period`() =
        runTest {
            val merchants = busyMonth()
            val today = Periods.merchantDay(ramp.clock.instant())
            val periods = listOf(null, Period(today, today), Period(today.minusDays(9), today.minusDays(1)))

            for (id in merchants) {
                for (period in periods) {
                    val own = ramp.earnings(id, TESTNET, period).getOrNull()!!
                    val staff = ramp.merchantSummary(id, TESTNET, period).getOrNull()!!

                    assertEquals(own.period, staff.period)
                    assertEquals(own.totals, staff.figures.totals, "one computation, read twice")
                    assertEquals(own.sweeps, staff.sweeps)
                    assertEquals(own.totals.orders, staff.figures.completed)
                }
            }
            val all = merchants.map { ramp.merchantSummary(it, TESTNET, null).getOrNull()!!.figures }
            assertEquals(5, all.sumOf { it.completed })
            assertEquals(7, all.sumOf { it.orders })
            assertTrue(all.sumOf { it.totals.sweptAssetMinor } > 0, "the sweep collected the first three")
            assertTrue(all.sumOf { it.totals.heldAssetMinor } > 0, "and the float still holds the last two")
        }

    @Test
    fun `on the wire staff and the merchant read the same amounts, to the last digit`() =
        runTest {
            for (id in busyMonth()) {
                val own = ramp.earnings(id, TESTNET, null).getOrNull()!!.toResponse()
                val staff = (admin.merchantSummary(id, TESTNET, null) as Resource.Success).data

                assertEquals(own.from to own.to, staff.period.from.toString() to staff.period.to.toString())
                assertEquals(own.orders, staff.orders.completed)
                assertEquals(
                    listOf(own.collected, own.sold, own.fees, own.yourShare, own.circasoShare),
                    staff.money.run { listOf(collected, sold, fees, merchantShare, circasoShare) },
                )
                assertEquals(
                    listOf(own.circasoShareUsdc, own.swept, own.held),
                    staff.money.run { listOf(circasoShareUsdc, swept, held) },
                )
                assertEquals(
                    own.sweeps.map { listOf(it.settlementId, it.day, it.amount, it.state, it.orders, it.txHash) },
                    staff.sweeps.map {
                        listOf(it.settlementId.toString(), it.day.toString(), it.amount, it.state, it.orders, it.txHash)
                    },
                )
            }
        }

    @Test
    fun `the overview's totals are the sum of its merchant rows, and each row is that merchant's earnings`() =
        runTest {
            val merchants = busyMonth()

            val overview = ramp.overview(TESTNET, null).getOrNull()!!

            assertEquals(Periods.thisMonth(ramp.clock.instant()), overview.period)
            assertEquals(merchants.toSet(), overview.merchants.map { it.merchant.merchantId }.toSet())
            val rows = overview.merchants
            for (row in rows) {
                val own = ramp.earnings(row.merchant.merchantId, TESTNET, null).getOrNull()!!
                assertEquals(own.totals, row.figures.totals)
                assertEquals(
                    ramp.merchantSummary(row.merchant.merchantId, TESTNET, null).getOrNull()!!.figures,
                    row.figures,
                )
                assertTrue(row.online)
                assertNotNull(row.lastOrderAt)
            }
            val totals = overview.totals
            assertEquals(rows.sumOf { it.figures.orders }, totals.orders)
            assertEquals(rows.sumOf { it.figures.completed }, totals.completed)
            assertEquals(rows.sumOf { it.figures.unfinished.failed }, totals.unfinished.failed)
            assertEquals(rows.sumOf { it.figures.unfinished.open }, totals.unfinished.open)
            assertEquals(rows.sumOf { it.figures.totals.collectedMinor }, totals.totals.collectedMinor)
            assertEquals(rows.sumOf { it.figures.totals.soldMinor }, totals.totals.soldMinor)
            assertEquals(rows.sumOf { it.figures.totals.feesMinor }, totals.totals.feesMinor)
            assertEquals(rows.sumOf { it.figures.totals.merchantMinor }, totals.totals.merchantMinor)
            assertEquals(rows.sumOf { it.figures.totals.platformMinor }, totals.totals.platformMinor)
            assertEquals(rows.sumOf { it.figures.totals.platformAssetMinor }, totals.totals.platformAssetMinor)
            assertEquals(rows.sumOf { it.figures.totals.sweptAssetMinor }, totals.totals.sweptAssetMinor)
            assertEquals(rows.sumOf { it.figures.totals.heldAssetMinor }, totals.totals.heldAssetMinor)
            assertEquals(7 to 5, totals.orders to totals.completed)
            assertEquals(1 to 1, totals.unfinished.failed to totals.unfinished.open)
            assertEquals(1_300L + 2_600 + 1_900 + 3_100 + 1_000, totals.totals.collectedMinor / 100)
            assertEquals(totals.totals.feesMinor, totals.totals.merchantMinor + totals.totals.platformMinor)

            // and the same on the wire: every total is the sum of the rows' own amounts
            val wire = admin.overview(TESTNET, null)

            fun sum(of: (com.circaso.ramp.api.OpsRampMoney) -> RampAmount) =
                wire.merchants.sumOf { of(it.money).amountMinor.toLong() }.toString()
            assertEquals(sum { it.collected }, wire.money.collected.amountMinor)
            assertEquals(sum { it.sold }, wire.money.sold.amountMinor)
            assertEquals(sum { it.fees }, wire.money.fees.amountMinor)
            assertEquals(sum { it.merchantShare }, wire.money.merchantShare.amountMinor)
            assertEquals(sum { it.circasoShare }, wire.money.circasoShare.amountMinor)
            assertEquals(sum { it.circasoShareUsdc }, wire.money.circasoShareUsdc.amountMinor)
            assertEquals(sum { it.held }, wire.money.held.amountMinor)
            assertEquals(wire.merchants.sumOf { it.orders.total }, wire.orders.total)
            assertEquals(wire.merchants.sumOf { it.orders.completed }, wire.orders.completed)
            assertEquals("KES" to "USDC", wire.money.collected.unit to wire.money.sold.unit)
            assertEquals("71.4", MerchantFigures.successRate(5, 7))
        }

    @Test
    fun `the overview lists approved merchants and anyone else who traded, on the network asked for`() =
        runTest {
            completed()
            ramp.verifyCodeFor("new@example.com")
            val quiet = ramp.merchant("Quiet", floatMinor = 1_000_000)
            ramp.clock.advance(60)
            val gone = ramp.merchant("Gone", floatMinor = 100_000_000)
            ramp.merchants.rows.values
                .filter { it.merchantId != gone.merchantId }
                .forEach { ramp.merchants.setOnlineUntil(it.merchantId, null) }
            completed()
            ramp.decideMerchant(gone.merchantId, approve = false, by = "staff")

            val rows =
                ramp
                    .overview(TESTNET, null)
                    .getOrNull()!!
                    .merchants
                    .associateBy { it.merchant.displayName }

            assertEquals(setOf("Crynce", "Quiet", "Gone"), rows.keys, "not the account that only signed in")
            assertEquals(0 to "0", rows.getValue("Quiet").figures.let { it.orders to it.successRatePercent })
            assertNull(rows.getValue("Quiet").lastOrderAt)
            assertEquals(MerchantState.SUSPENDED, rows.getValue("Gone").merchant.state)
            assertEquals(1 to "100", rows.getValue("Gone").figures.let { it.orders to it.successRatePercent })
            assertFalse(rows.getValue("Crynce").online, "the trading window is over")

            val mainnet = ramp.overview(Network.MAINNET, null).getOrNull()!!
            assertEquals(0, mainnet.totals.orders)
            assertEquals(setOf("Crynce", "Quiet"), mainnet.merchants.map { it.merchant.displayName }.toSet())
        }

    @Test
    fun `a summary's days are Nairobi days, add up to the period, and count unfinished orders when made`() =
        runTest {
            // 20:30 UTC on the 21st is 23:30 in Nairobi; 21:30 UTC is 00:30 on the 22nd there
            ramp.clock.now = Instant.parse("2026-11-21T20:30:00Z")
            completed(1_300)
            failed()
            ramp.clock.now = Instant.parse("2026-11-21T21:30:00Z")
            completed(2_600)
            completed(1_000)
            val id = ramp.crynce.merchantId
            val both = Period(LocalDate.parse("2026-11-21"), LocalDate.parse("2026-11-22"))

            val summary = ramp.merchantSummary(id, TESTNET, both).getOrNull()!!

            assertEquals(listOf("2026-11-21", "2026-11-22"), summary.daily.map { it.totals.day.toString() })
            assertEquals(listOf(2 to 1, 2 to 2), summary.daily.map { it.orders to it.totals.completed })
            assertEquals(listOf(130_000L, 360_000L), summary.daily.map { it.totals.collectedMinor })
            val totals = summary.figures.totals
            assertEquals(totals.orders, summary.daily.sumOf { it.totals.completed })
            assertEquals(summary.figures.orders, summary.daily.sumOf { it.orders })
            assertEquals(totals.collectedMinor, summary.daily.sumOf { it.totals.collectedMinor })
            assertEquals(totals.soldMinor, summary.daily.sumOf { it.totals.soldMinor })
            assertEquals(totals.feesMinor, summary.daily.sumOf { it.totals.feesMinor })
            assertEquals(totals.merchantMinor, summary.daily.sumOf { it.totals.merchantMinor })
            assertEquals(totals.platformMinor, summary.daily.sumOf { it.totals.platformMinor })
            assertEquals("75", summary.figures.successRatePercent)

            val the22nd = Period(both.to, both.to)
            val day = ramp.merchantSummary(id, TESTNET, the22nd).getOrNull()!!
            assertEquals(2 to 0, day.figures.orders to day.figures.unfinished.total)
            assertEquals(ramp.earnings(id, TESTNET, the22nd).getOrNull()!!.totals, day.figures.totals)
            assertEquals(listOf("2026-11-22"), day.daily.map { it.totals.day.toString() })
        }

    @Test
    fun `the summary shows the account, and the float with what orders hold apart from what is held for Circaso`() =
        runTest {
            completed()
            val waiting = open()
            val id = ramp.crynce.merchantId

            val summary = ramp.merchantSummary(id, TESTNET, null).getOrNull()!!

            val float = summary.float!!
            assertEquals(100_000_000L - 9_800_000, float.balanceMinor)
            assertEquals(waiting.assetMinor, float.reservedMinor)
            assertEquals(60_000, float.owedMinor)
            assertEquals(float.balanceMinor - float.reservedMinor - float.owedMinor, float.freeMinor)
            assertEquals(ramp.walletOf(ramp.crynce), summary.wallet)
            assertTrue(summary.online)
            assertNull(summary.paymentsProvider, "no payment account is stored for him")
            assertEquals(2 to 1, summary.figures.orders to summary.figures.unfinished.open)

            val wire = (admin.merchantSummary(id, TESTNET, null) as Resource.Success).data
            assertEquals("crynce@example.com" to "APPROVED", wire.merchant.email to wire.merchant.state)
            assertFalse(wire.merchant.paymentsConnected)
            assertEquals(ramp.crynce.onlineUntil, wire.merchant.onlineUntil)
            assertEquals("0.06", wire.float!!.owedToCircaso.amount)
            assertEquals("9.8", wire.float!!.reserved.amount)
            assertEquals(CHAIN, wire.wallet!!.blockchain)

            ramp.treasury.failure =
                com.circaso.integrations.api.IntegrationError
                    .Unavailable("Circle")
            assertNull(ramp.merchantSummary(id, TESTNET, null).getOrNull()!!.float, "never a zero nobody measured")
            ramp.treasury.failure = null
            assertNull(ramp.merchantSummary(id, Network.MAINNET, null).getOrNull()!!.wallet)
            assertEquals(
                RampError.MerchantNotFound,
                (ramp.merchantSummary(MerchantId.new(), TESTNET, null) as Resource.Error).error,
            )
        }

    @Test
    fun `a merchant's orders page newest first with the payer masked, and the shares only once completed`() =
        runTest {
            val done = completed()
            sweep()
            val later = completed(2_600)
            val lost = failed()
            val waiting = open()
            val id = ramp.crynce.merchantId
            val everything = StaffOrderFilter(id, TESTNET, null, null)

            val first = ramp.staffOrders(everything, null, 3).getOrNull()!!
            val last = first.last().order
            val rest = ramp.staffOrders(everything, PageCursor(last.createdAt, last.orderId.value), 3).getOrNull()!!

            assertEquals(
                listOf(waiting, lost, later, done).map { it.orderId },
                (first + rest).map { it.order.orderId },
            )
            val byId = (first + rest).associateBy { it.order.orderId }
            assertNull(byId.getValue(lost.orderId).commission)
            assertNull(byId.getValue(waiting.orderId).commission)
            val swept = byId.getValue(done.orderId).commission!!
            assertEquals(
                Triple(1_820L, 780L, 60_000L),
                Triple(swept.merchantMinor, swept.platformMinor, swept.platformAssetMinor),
            )
            assertNotNull(swept.settlementId, "the confirmed sweep took its share")
            assertNull(byId.getValue(later.orderId).commission!!.settlementId, "still held in the float")

            val completedOnly = ramp.staffOrders(everything.copy(state = OrderState.COMPLETED), null, 10).getOrNull()!!
            assertEquals(listOf(later.orderId, done.orderId), completedOnly.map { it.order.orderId })
            val longAgo = Period(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))
            assertTrue(ramp.staffOrders(everything.copy(period = longAgo), null, 10).getOrNull()!!.isEmpty())
            val today = Periods.merchantDay(ramp.clock.instant())
            assertEquals(
                4,
                ramp.staffOrders(everything.copy(period = Period(today, today)), null, 10).getOrNull()!!.size,
            )
            assertTrue(ramp.staffOrders(everything.copy(network = Network.MAINNET), null, 10).getOrNull()!!.isEmpty())

            // on the wire: the payer's number never leaves the ramp whole
            val period = OpsRampPeriod(today, today)
            val wire = (admin.merchantOrders(id, TESTNET, period, null, null, 2) as Resource.Success).data
            assertEquals(2, wire.size)
            val next = OpsRampOrderCursor(wire.last().createdAt, wire.last().orderId)
            val more = (admin.merchantOrders(id, TESTNET, period, null, next, 10) as Resource.Success).data
            assertEquals(listOf(later.orderId, done.orderId), more.map { it.orderId })
            val line = more.last()
            assertTrue((wire + more).none { ramp.phone.e164.drop(4) in it.toString() + it.maskedPhone })
            assertTrue(line.maskedPhone.startsWith("+254 7") && "•" in line.maskedPhone)
            assertEquals("1300" to "9.8", line.paid.amount to line.released.amount)
            assertEquals("130" to "26", line.rate to line.fee.amount)
            assertEquals("18.2" to "7.8", line.merchantShare!!.amount to line.circasoShare!!.amount)
            assertEquals("0.06", line.circasoShareUsdc!!.amount)
            assertEquals(
                Triple("COMPLETED", "ON_RAMP", PAYHERO),
                Triple(line.state, line.direction, line.collectionsProvider),
            )
            assertNotNull(line.completedAt)
            assertTrue(line.txHash!!.startsWith("0xhash-"))
            val failedLine = wire.single { it.orderId == lost.orderId }
            assertEquals("FAILED" to "payment_failed", failedLine.state to failedLine.failureReason)
            assertNull(failedLine.completedAt)
            assertNull(failedLine.merchantShare)

            val onlyFailed = (admin.merchantOrders(id, TESTNET, null, "failed", null, 10) as Resource.Success).data
            assertEquals(listOf(lost.orderId), onlyFailed.map { it.orderId })
            val bad = admin.merchantOrders(id, TESTNET, null, "DONE", null, 10) as Resource.Error
            assertEquals("request.malformed", bad.error.code)
            val nobody = admin.merchantOrders(MerchantId.new(), TESTNET, null, null, null, 10) as Resource.Error
            assertEquals(RampError.MerchantNotFound, nobody.error)
        }
}
