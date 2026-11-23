package com.circaso.ramp

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.data.cache.RampExpiry
import com.circaso.ramp.data.repositories.CommissionRepositoryImpl
import com.circaso.ramp.data.repositories.MerchantRepositoryImpl
import com.circaso.ramp.data.repositories.OrderEventRepositoryImpl
import com.circaso.ramp.data.repositories.OrderRepositoryImpl
import com.circaso.ramp.data.repositories.PayoutHoldRepositoryImpl
import com.circaso.ramp.data.repositories.QuoteRepositoryImpl
import com.circaso.ramp.data.repositories.ReservationRepositoryImpl
import com.circaso.ramp.data.repositories.SettingsRepositoryImpl
import com.circaso.ramp.data.repositories.StatementRepositoryImpl
import com.circaso.ramp.data.repositories.TestPayoutRepositoryImpl
import com.circaso.ramp.di.rampDatabaseSpec
import com.circaso.ramp.domain.models.BalanceRead
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.KesBalances
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PayoutFee
import com.circaso.ramp.domain.models.PayoutFeeBand
import com.circaso.ramp.domain.models.PayoutIssue
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.TestPayout
import com.circaso.ramp.domain.models.TestPayoutState
import com.circaso.ramp.domain.support.Fees
import com.circaso.ramp.domain.support.Periods
import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Selling's data layer on PostgreSQL (V7, decision 2026-11-23): the settings round trip with the fallback fee table,
 * one transfer funding one order, a sale's fields written by its moves, the shillings held per merchant, the test
 * payouts one at a time, staff's re-checks, and a statement that tells buying's money from selling's.
 */
class OffRampDataTest {
    private val database: Database = TestPostgres.database(rampDatabaseSpec)
    private val settingsRows = SettingsRepositoryImpl(database)
    private val merchants = MerchantRepositoryImpl(database)
    private val quotes = QuoteRepositoryImpl(database)
    private val orders = OrderRepositoryImpl(database)
    private val events = OrderEventRepositoryImpl(database)
    private val reservations = ReservationRepositoryImpl(database)
    private val holds = PayoutHoldRepositoryImpl(database)
    private val tests = TestPayoutRepositoryImpl(database)
    private val commissions = CommissionRepositoryImpl(database)
    private val statements = StatementRepositoryImpl(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val phone = PhoneNumber.parse("+254712345678")!!

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("ramp")
            RampExpiry(database).run()
        }

    private suspend fun merchant(): Merchant {
        val merchant =
            Merchant(
                merchantId = MerchantId.new(),
                email = "seller-${MerchantId.new()}@example.com",
                displayName = "Crynce",
                contactPhone = "+254700000000",
                state = MerchantState.APPROVED,
                callbackKey = "crynce-${UUID.randomUUID().toString().take(16)}",
                onlineUntil = now.plus(Duration.ofHours(8)),
                lastAssignedAt = null,
                approvedBy = "staff",
                approvedAt = now,
                createdAt = now,
            )
        assertTrue(merchants.insert(merchant))
        return merchant
    }

    private fun sale(
        merchantId: MerchantId,
        userId: UserId = UserId.new(),
        state: OrderState = OrderState.AWAITING_TRANSFER,
    ) = Order(
        orderId = RampOrderId.new(),
        userId = userId,
        merchantId = merchantId,
        quoteId = QuoteId.new(),
        network = TESTNET,
        direction = Direction.OFF_RAMP,
        state = state,
        blockchain = CHAIN,
        fiat = FiatAmount(126_900, "KES"),
        assetMinor = 10_000_000,
        rate = BigDecimal("130"),
        feeMinor = 2_600,
        feeBps = 200,
        merchantShareBps = 7_000,
        collectionsProvider = PAYHERO,
        payerPhone = phone,
        destinationAddress = "0xfloat",
        providerReference = null,
        checkoutId = null,
        mpesaReceipt = null,
        custodyTxId = null,
        txHash = null,
        failureReason = null,
        paymentDeadline = now.plusSeconds(900),
        createdAt = now,
        updatedAt = now,
        providerFeeMinor = 500,
        providerFeeSource = "LIVE_TABLE",
        refundAddress = "0xuser",
    )

    @Test
    fun `selling's settings round trip, the fallback fee table typed both ways`() =
        runBlocking<Unit> {
            settingsRows.seed(RampSettings.defaults(TESTNET, now))
            val seeded = settingsRows.load().getValue(TESTNET)
            assertEquals(RampState.OFF, seeded.offRampState)
            assertEquals(
                Triple(900, 1_800, 100_000L),
                Triple(seeded.transferWindowSeconds, seeded.payoutWindowSeconds, seeded.payoutBalanceLowMinor),
            )
            assertTrue(seeded.payoutFeeBands.isEmpty())

            val bands = listOf(PayoutFeeBand(10_000, 100), PayoutFeeBand(500_000, 1_500))
            val changed =
                seeded.copy(offRampState = RampState.PREVIEW, offRampMinMinor = 2_000, payoutFeeBands = bands)
            val saved = settingsRows.update(changed, seeded.version, "staff", now)!!

            assertEquals(RampState.PREVIEW to 2_000L, saved.offRampState to saved.offRampMinMinor)
            assertEquals(bands, saved.payoutFeeBands)
            assertEquals(bands, settingsRows.load().getValue(TESTNET).payoutFeeBands)
        }

    @Test
    fun `a sale's quote keeps the provider's fee, and a sale's moves write its fields and timeline`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val quote =
                Quote(
                    QuoteId.new(),
                    UserId.new(),
                    TESTNET,
                    Direction.OFF_RAMP,
                    CHAIN,
                    FiatAmount(126_900, "KES"),
                    10_000_000,
                    BigDecimal("130"),
                    2_600,
                    200,
                    7_000,
                    now.plusSeconds(60),
                    providerFeeMinor = 500,
                    providerFeeSource = "ADMIN_TABLE",
                )
            quotes.insert(quote)
            assertEquals(
                500L to "ADMIN_TABLE",
                quotes.find(quote.quoteId, now)!!.let { it.providerFeeMinor to it.providerFeeSource },
            )

            val order = sale(merchant.merchantId)
            assertTrue(orders.insert(order))
            assertEquals("0xuser" to 500L, orders.find(order.orderId)!!.let { it.refundAddress to it.providerFeeMinor })
            assertTrue(orders.needsKeys(merchant.merchantId), "a sale may still have to be paid out")

            val patch = OrderPatch(fundedMinor = 10_000_000, fundingTxHash = "0xtx", fundedAt = now)
            val funded =
                orders.move(
                    order.orderId,
                    OrderState.FUNDED,
                    setOf(OrderState.AWAITING_TRANSFER),
                    patch,
                    now.plusSeconds(1),
                )!!
            assertEquals(10_000_000L to "0xtx", funded.fundedMinor to funded.fundingTxHash)
            val sending =
                OrderPatch(
                    payoutAttempts = 1,
                    payoutReference = "${order.orderId}",
                    payoutOutcome = "SENDING",
                    payoutSentAt = now,
                )
            orders.move(order.orderId, OrderState.PAYING_OUT, setOf(OrderState.FUNDED), sending, now.plusSeconds(2))
            val accepted =
                orders.annotate(
                    order.orderId,
                    OrderState.PAYING_OUT,
                    OrderPatch(payoutOutcome = "ACCEPTED", payoutProviderReference = "PO-1"),
                    now.plusSeconds(3),
                )!!
            assertEquals(
                Triple(1, "ACCEPTED", "PO-1"),
                Triple(accepted.payoutAttempts, accepted.payoutOutcome, accepted.payoutProviderReference),
            )
            assertNull(
                orders.annotate(order.orderId, OrderState.FUNDED, OrderPatch(payoutOutcome = "x"), now),
                "it moved on",
            )
            assertEquals(
                listOf(OrderState.AWAITING_TRANSFER, OrderState.FUNDED, OrderState.PAYING_OUT),
                events.timeline(order.orderId).map { it.state },
                "an annotation is no move",
            )
        }

    @Test
    fun `a transfer funds one order once, and the sweeps find what is due`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val first = sale(merchant.merchantId)
            val second = sale(merchant.merchantId)
            assertTrue(orders.insert(first) && orders.insert(second))
            val transfer = UUID.randomUUID()

            assertEquals(transfer, orders.attachTransfer(first.orderId, transfer, now)!!.walletTransferId)
            assertEquals(
                transfer,
                orders.attachTransfer(first.orderId, UUID.randomUUID(), now)!!.walletTransferId,
                "kept",
            )
            assertNull(
                orders.attachTransfer(second.orderId, transfer, now)!!.walletTransferId,
                "orders_wallet_transfer_key",
            )
            assertEquals(
                listOf(first.orderId),
                orders.awaitingFunding(TESTNET, now.plusSeconds(1), 10).map { it.orderId },
            )

            orders.move(
                first.orderId,
                OrderState.FUNDED,
                setOf(OrderState.AWAITING_TRANSFER),
                OrderPatch(fundedMinor = 1),
                now,
            )
            assertTrue(orders.awaitingFunding(TESTNET, now.plusSeconds(1), 10).isEmpty())
            assertEquals(listOf(first.orderId), orders.dueForPayout(TESTNET, now, 10).map { it.orderId })
            orders.annotate(first.orderId, OrderState.FUNDED, OrderPatch(payoutRetryAt = now.plusSeconds(60)), now)
            assertTrue(orders.dueForPayout(TESTNET, now, 10).isEmpty(), "not before its retry")
            assertEquals(1, orders.dueForPayout(TESTNET, now.plusSeconds(60), 10).size)
        }

    @Test
    fun `the shillings held for sales close once, and only open ones count`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val one = sale(merchant.merchantId)
            val two = sale(merchant.merchantId)
            holds.insert(one, now)
            holds.insert(two, now)
            holds.insert(two, now)
            assertEquals(2 * 127_400L, holds.openTotal(merchant.merchantId))

            assertTrue(holds.consume(one.orderId, now))
            assertFalse(holds.release(one.orderId, now), "closed once")
            assertTrue(holds.release(two.orderId, now))
            assertEquals(0, holds.openTotal(merchant.merchantId))

            reservations.hold(one, 10_000_000, now)
            assertEquals(10_000_000, reservations.openTotal(merchant.merchantId, TESTNET), "the user's USDC is held")
            assertTrue(reservations.release(one.orderId, now))
            assertTrue(reservations.reopen(one.orderId, now))
            assertEquals(10_000_000, reservations.openTotal(merchant.merchantId, TESTNET))
        }

    @Test
    fun `a merchant's payout account, issue and balances are kept, the account digits only`() =
        runBlocking<Unit> {
            val merchant = merchant()
            merchants.setPayoutAccount(merchant.merchantId, "4242")
            merchants.setPayoutIssue(merchant.merchantId, PayoutIssue.PAYOUT_BALANCE, now)
            val balances = KesBalances(50_000, "payment_wallet", null, null, now)
            merchants.recordBalances(merchant.merchantId, balances)

            val read = merchants.find(merchant.merchantId)!!
            assertEquals("4242" to PayoutIssue.PAYOUT_BALANCE, read.payoutAccountId to read.payoutIssue)
            assertEquals(balances, merchants.balances(merchant.merchantId))
            merchants.setPayoutIssue(merchant.merchantId, null, now)
            assertNull(merchants.find(merchant.merchantId)!!.payoutIssue)
            val refused = runCatching { merchants.setPayoutAccount(merchant.merchantId, "12ab") }
            assertTrue(refused.isFailure, "payout_account_id is digits (CHECK)")
        }

    @Test
    fun `one test payout in flight per merchant, moved by its state, found while open`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val test =
                TestPayout(
                    testId = Uuid7.generate(now),
                    merchantId = merchant.merchantId,
                    provider = PAYHERO,
                    amountMinor = 1_000,
                    payee = PhoneNumber.parse("+254700000000")!!,
                    state = TestPayoutState.SENDING,
                    outcome = null,
                    providerReference = null,
                    secondaryReference = null,
                    receipt = null,
                    paidAmountMinor = null,
                    providerFee = PayoutFee(9, "LIVE_TABLE"),
                    balanceBefore = BalanceRead(50_000, "payment_wallet"),
                    balanceAfter = null,
                    createdAt = now,
                    updatedAt = now,
                )
            assertTrue(tests.insert(test))
            assertFalse(tests.insert(test.copy(testId = Uuid7.generate(now))), "test_payouts_open_key")
            assertEquals(listOf(test.testId), tests.open(now.plusSeconds(1), 10).map { it.testId })

            val accepted = test.copy(state = TestPayoutState.ACCEPTED, providerReference = "PO-1", updatedAt = now)
            assertTrue(tests.move(accepted, setOf(TestPayoutState.SENDING)))
            assertFalse(tests.move(accepted.copy(state = TestPayoutState.PAID), setOf(TestPayoutState.SENDING)))
            val paid =
                accepted.copy(
                    state = TestPayoutState.PAID,
                    receipt = "QP1",
                    balanceAfter = BalanceRead(48_991, "x"),
                )
            assertTrue(tests.move(paid, setOf(TestPayoutState.ACCEPTED)))
            assertEquals(paid, tests.find(test.testId))
            assertEquals(paid, tests.latest(merchant.merchantId))
            assertTrue(tests.open(now.plusSeconds(1), 10).isEmpty())
            assertTrue(tests.insert(test.copy(testId = Uuid7.generate(now.plusSeconds(1)))), "a new one once it ended")
        }

    @Test
    fun `staff's re-check is asked only of an order in review, and found until it is done`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val order = sale(merchant.merchantId)
            assertTrue(orders.insert(order))
            assertFalse(orders.requestRecheck(order.orderId, now))

            orders.move(
                order.orderId,
                OrderState.REVIEW,
                setOf(OrderState.AWAITING_TRANSFER),
                OrderPatch(failureReason = "x"),
                now,
            )
            assertTrue(orders.requestRecheck(order.orderId, now))
            assertEquals(listOf(order.orderId), orders.rechecks(10).map { it.orderId })
            orders.clearRecheck(order.orderId)
            assertTrue(orders.rechecks(10).isEmpty())

            orders.move(
                order.orderId,
                OrderState.COMPLETED,
                setOf(OrderState.REVIEW),
                OrderPatch(mpesaReceipt = "QP9"),
                now,
            )
            assertTrue(orders.receiptTaken("QP9", RampOrderId.new()))
            assertFalse(orders.receiptTaken("QP9", order.orderId))
        }

    @Test
    fun `a statement tells buying's shillings and USDC from selling's`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val order = sale(merchant.merchantId)
            assertTrue(orders.insert(order))
            orders.move(order.orderId, OrderState.REFUNDING, setOf(OrderState.AWAITING_TRANSFER), OrderPatch(), now)
            val completed =
                orders.move(order.orderId, OrderState.COMPLETED, setOf(OrderState.REFUNDING), OrderPatch(), now)!!
            assertTrue(commissions.insert(completed, Fees.split(completed), now))

            val around = Period(Periods.merchantDay(now).minusDays(1), Periods.merchantDay(now).plusDays(1))
            val totals = statements.totals(merchant.merchantId, TESTNET, around)

            assertEquals(1, totals.orders)
            assertEquals(0L to 0L, totals.collectedMinor to totals.soldMinor, "nothing was bought")
            assertEquals(126_900L to 10_000_000L, totals.paidOutMinor to totals.boughtMinor)
            assertEquals(2_600L to 60_000L, totals.feesMinor to totals.platformAssetMinor)
            val line = statements.lines(merchant.merchantId, TESTNET, around, null, 10).single()
            assertEquals(Direction.OFF_RAMP, line.direction)
            assertNotNull(line)
        }
}
