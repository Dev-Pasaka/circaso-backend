package com.circaso.ramp

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryAccess
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryWalletRequest
import com.circaso.ramp.api.OnRampCompleted
import com.circaso.ramp.domain.models.FeeSplit
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.SettlementState
import com.circaso.ramp.domain.support.FeeSweeps
import com.circaso.ramp.domain.support.Fees
import com.circaso.ramp.domain.support.FloatWallets
import com.circaso.ramp.domain.support.Periods
import com.circaso.ramp.domain.support.Releases
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.getOrNull
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ops process's side: the release of a paid order, the wallets it makes, the daily sweep of Circaso's share, and
 * the merchant's overview and statement that are read from the same rows.
 */
class ReleaseTest {
    private val ramp = Harness()

    private fun phoneOf(number: String) = PhoneNumber.parse(number)!!

    // The release

    @Test
    fun `a paid order is sent once from the merchant's wallet, and the chain's confirmation completes it`() =
        runTest {
            val order = ramp.paid()
            val wallet = ramp.walletOf(ramp.crynce)

            ramp.release()

            val releasing = ramp.now(order)
            assertEquals(OrderState.RELEASING, releasing.state)
            val send = ramp.treasury.sends.single()
            assertEquals(wallet.custodyWalletId, send.walletId)
            assertEquals("0xuser1", send.destinationAddress)
            assertEquals(9_800_000.toBigInteger(), send.amountMinor)
            assertEquals("usdc", send.tokenId)
            assertEquals(order.orderId.toString(), send.refId)
            assertEquals(Releases.releaseKey(order.orderId), send.idempotencyKey)
            assertTrue(ramp.bus.published.isEmpty(), "nothing is announced until the chain confirms")

            ramp.release()
            assertEquals(OrderState.RELEASING, ramp.now(order).state, "still in flight")

            ramp.treasury.settle(releasing.custodyTxId!!)
            ramp.release()
            ramp.release()

            val completed = ramp.now(order)
            assertEquals(OrderState.COMPLETED, completed.state)
            assertEquals("0xhash-tx-1", completed.txHash)
            assertEquals(1, ramp.treasury.sends.size)
            assertEquals(
                "CONSUMED",
                ramp.reservations.rows
                    .getValue(order.orderId)
                    .state,
            )
            assertEquals(
                FeeSplit(1_820, 780, 60_000),
                ramp.ledger.commissions
                    .getValue(order.orderId)
                    .split,
            )
            assertEquals(listOf(OnRampCompleted.TYPE), ramp.bus.types())
            assertEquals(ramp.outbox.written, ramp.bus.published)
            assertEquals(
                listOf(OrderState.PAYMENT_REQUESTED, OrderState.PAID, OrderState.RELEASING, OrderState.COMPLETED),
                ramp.frames.states,
            )
        }

    @Test
    fun `nothing is released that the provider didn't confirm - unpaid, failed and review orders are never sent`() =
        runTest {
            val unpaid = ramp.prompted()
            val bob = ramp.user("+254700000002")
            val failed = ramp.prompted(bob, phoneOf("+254700000002"))
            ramp.collections.fail(failed)
            ramp.callback(failed)

            repeat(3) { ramp.release() }

            assertTrue(ramp.treasury.sends.isEmpty())
            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(unpaid).state)
            assertEquals(OrderState.FAILED, ramp.now(failed).state)
        }

    @Test
    fun `with the custodian down the order stays paid, and the next run sends it with the same key`() =
        runTest {
            val order = ramp.paid()
            ramp.treasury.failure = IntegrationError.Unavailable("Circle")

            ramp.release()
            assertEquals(OrderState.PAID, ramp.now(order).state)

            ramp.treasury.failure = null
            ramp.release()

            assertEquals(OrderState.RELEASING, ramp.now(order).state)
            assertEquals(
                Releases.releaseKey(order.orderId),
                ramp.treasury.sends
                    .single()
                    .idempotencyKey,
            )
        }

    @Test
    fun `a release the custodian refuses, or that fails on chain, goes to a person with the float still held`() =
        runTest {
            val short = ramp.paid()
            ramp.treasury.balances[ramp.walletOf(ramp.crynce).custodyWalletId] = 1_000_000
            ramp.release()
            assertEquals(OrderState.REVIEW, ramp.now(short).state)
            assertEquals("release_refused", ramp.now(short).failureReason)

            ramp.treasury.balances[ramp.walletOf(ramp.crynce).custodyWalletId] = 100_000_000
            val bob = ramp.user("+254700000002")
            val failed = ramp.paid(bob, phoneOf("+254700000002"))
            ramp.release()
            ramp.treasury.settle(ramp.now(failed).custodyTxId!!, TreasuryTransactionState.FAILED)
            ramp.release()
            assertEquals(OrderState.REVIEW, ramp.now(failed).state)
            assertEquals("release_failed", ramp.now(failed).failureReason)

            assertEquals(listOf("release_refused", "release_failed"), ramp.alerts.reasons)
            assertEquals(2 * 9_800_000L, ramp.reservations.openTotal(ramp.crynce.merchantId, TESTNET))
            assertTrue(ramp.bus.published.isEmpty())
            assertTrue(ramp.ledger.commissions.isEmpty())
        }

    @Test
    fun `only the USDC contract the settings name is ever sent - a token that calls itself USDC is ignored`() =
        runTest {
            val order = ramp.paid()
            val wallet = ramp.walletOf(ramp.crynce).custodyWalletId
            ramp.treasury.balances.remove(wallet)
            ramp.treasury.impostors[wallet] = 100_000_000

            ramp.release()

            assertEquals(OrderState.REVIEW, ramp.now(order).state)
            assertEquals("float_short", ramp.now(order).failureReason)
            assertTrue(ramp.treasury.sends.isEmpty())
        }

    @Test
    fun `a chain with no USDC contract in the settings releases nothing and leaves the order to be tried again`() =
        runTest {
            val order = ramp.paid()
            ramp.usdc.contract = null

            ramp.release()
            assertEquals(OrderState.PAID, ramp.now(order).state)
            assertTrue(ramp.alerts.stuck.isNotEmpty())

            ramp.usdc.contract = USDC_CONTRACT.lowercase()
            ramp.release()
            assertEquals(OrderState.RELEASING, ramp.now(order).state, "EVM addresses compare without case")
        }

    @Test
    fun `a server that can't sign releases nothing`() =
        runTest {
            val order = ramp.paid()
            ramp.treasury.access = TreasuryAccess.READ_ONLY

            assertEquals(0, ramp.release())
            assertEquals(0, ramp.settle())
            assertEquals(OrderState.PAID, ramp.now(order).state)
            assertEquals(0, ramp.releaseOrders(Network.MAINNET))
        }

    // Wallets

    @Test
    fun `the ops process makes the wallet set, Circaso's ramp wallet and an approved merchant's float wallet, once`() =
        runTest {
            ramp.revenue.rows.clear()
            ramp.verifyCodeFor("new@example.com")
            val applied = ramp.merchants.byEmail("new@example.com")!!
            ramp.merchants.rows[applied.merchantId] = applied.copy(state = MerchantState.APPLIED)

            ramp.release()
            assertNull(ramp.merchantWallets.find(applied.merchantId, TESTNET), "not approved: no wallet")
            assertEquals(
                "set-testnet",
                ramp.settingsRows.rows
                    .getValue(TESTNET)
                    .walletSetId,
            )
            assertEquals(
                FloatWallets.REVENUE_REF,
                ramp.treasury.walletRequests
                    .single()
                    .refId,
            )

            ramp.decideMerchant(applied.merchantId, approve = true, by = "staff")
            ramp.release()
            ramp.release()

            val wallet = ramp.merchantWallets.find(applied.merchantId, TESTNET)!!
            assertEquals(CHAIN, wallet.blockchain)
            assertEquals(2, ramp.treasury.walletRequests.size, "nothing is made twice")
            assertEquals(listOf("Circaso ramp"), ramp.treasury.walletSets)
            val request = ramp.treasury.walletRequests.last()
            assertEquals(applied.merchantId.toString(), request.refId)
            assertEquals(TreasuryWalletRequest.ACCOUNT_SCA, request.accountType, "gas is sponsored for a smart account")
            assertEquals("set-testnet", request.walletSetId)
            assertNotEquals(
                ramp.treasury.walletRequests
                    .first()
                    .idempotencyKey,
                request.idempotencyKey,
            )
        }

    // Fees

    @Test
    fun `the fee is split by the order's own ratio, and Circaso's part is taken in USDC at the order's rate`() =
        runTest {
            val order = ramp.completed()

            // 26 shillings at 70/30: 18.20 to the merchant, 7.80 to Circaso, which is 0.06 USDC at 130
            assertEquals(FeeSplit(1_820, 780, 60_000), Fees.split(order))
            assertEquals(FeeSplit(2_600, 0, 0), Fees.split(order.copy(merchantShareBps = 10_000)))
            assertEquals(FeeSplit(0, 2_600, 200_000), Fees.split(order.copy(merchantShareBps = 0)))
            assertEquals(FeeSplit(0, 0, 0), Fees.split(order.copy(feeMinor = 0)))
            // rounding never asks the float for more than the fee was worth
            assertEquals(FeeSplit(0, 1, 76), Fees.split(order.copy(feeMinor = 1, merchantShareBps = 5_000)))
        }

    @Test
    fun `Circaso's share is swept once a day in one transfer per merchant, and marks the orders it covered`() =
        runTest {
            val bob = ramp.user("+254700000002")
            val first = ramp.completed()
            val second = ramp.completed(bob, phoneOf("+254700000002"), shillings = 2_600)
            val wallet = ramp.walletOf(ramp.crynce).custodyWalletId
            assertEquals(180_000, ramp.ledger.owedTotal(ramp.crynce.merchantId, TESTNET))

            // 12:00 UTC: yesterday's 21:00 has passed, so the sweep is due
            ramp.settle()

            val sweep =
                ramp.ledger.settlements.values
                    .single()
            assertEquals(SettlementState.PENDING, sweep.state)
            assertEquals(180_000L to 2, sweep.amountMinor to sweep.orders)
            assertEquals(LocalDate.parse("2026-11-21"), sweep.day, "the Nairobi day that ended as it fell due")
            val send = ramp.treasury.sends.last()
            assertEquals(
                Triple(wallet, "0xrevenue", 180_000.toBigInteger()),
                Triple(send.walletId, send.destinationAddress, send.amountMinor),
            )
            assertEquals(FeeSweeps.sweepKey(sweep.settlementId), send.idempotencyKey)
            assertEquals(sweep.settlementId.toString(), send.refId)
            assertEquals(180_000, ramp.ledger.owedTotal(ramp.crynce.merchantId, TESTNET), "held until confirmed")

            ramp.settle()
            assertEquals(3, ramp.treasury.sends.size, "a pending sweep is followed, not sent again")

            ramp.treasury.settleAll()
            ramp.settle()

            val confirmed =
                ramp.ledger.settlements.values
                    .single()
            assertEquals(SettlementState.CONFIRMED, confirmed.state)
            assertEquals("0xhash-tx-3", confirmed.txHash)
            assertEquals(0, ramp.ledger.owedTotal(ramp.crynce.merchantId, TESTNET))
            assertEquals(180_000, ramp.treasury.balances.getValue("w-revenue"))
            listOf(first, second).forEach {
                assertEquals(
                    confirmed.settlementId,
                    ramp.ledger.commissions
                        .getValue(it.orderId)
                        .settlementId,
                )
            }

            // the same day again: one more order, but today's sweep has been made
            val carol = ramp.user("+254700000003")
            ramp.completed(carol, phoneOf("+254700000003"))
            ramp.settle()
            assertEquals(1, ramp.ledger.settlements.size)

            // tomorrow, after 21:00 UTC, it is collected
            ramp.clock.now = Instant.parse("2026-11-22T21:05:00Z")
            ramp.settle()
            assertEquals(2, ramp.ledger.settlements.size)
            assertEquals(
                LocalDate.parse("2026-11-22"),
                ramp.ledger.settlements.values
                    .last()
                    .day,
            )
        }

    @Test
    fun `a sweep the custodian can't take stays pending and is tried again with the same key`() =
        runTest {
            ramp.completed()
            ramp.treasury.failure = IntegrationError.Unavailable("Circle")

            ramp.settle()
            ramp.settle()
            val sweep =
                ramp.ledger.settlements.values
                    .single()
            assertEquals(SettlementState.PENDING, sweep.state)
            assertEquals(1, ramp.treasury.sends.size, "only the release so far")

            ramp.treasury.failure = null
            ramp.settle()
            ramp.treasury.settleAll()
            ramp.settle()

            assertEquals(
                SettlementState.CONFIRMED,
                ramp.ledger.settlements.values
                    .single()
                    .state,
            )
            assertEquals(
                FeeSweeps.sweepKey(sweep.settlementId),
                ramp.treasury.sends
                    .last()
                    .idempotencyKey,
            )
        }

    @Test
    fun `a refused sweep fails, frees its orders for the next one, and what is owed stays held meanwhile`() =
        runTest {
            val order = ramp.completed()
            val wallet = ramp.walletOf(ramp.crynce).custodyWalletId
            val held = ramp.treasury.balances.getValue(wallet)
            ramp.treasury.balances[wallet] = 10_000

            ramp.settle()

            val failed =
                ramp.ledger.settlements.values
                    .single()
            assertEquals(SettlementState.FAILED to "refused", failed.state to failed.failureReason)
            assertNull(
                ramp.ledger.commissions
                    .getValue(order.orderId)
                    .settlementId,
            )
            assertEquals(60_000, ramp.ledger.owedTotal(ramp.crynce.merchantId, TESTNET))

            ramp.treasury.balances[wallet] = held
            ramp.settle()
            assertEquals(1, ramp.ledger.settlements.size, "not retried at once")

            ramp.clock.advance(3_601)
            ramp.settle()
            ramp.treasury.settleAll()
            ramp.settle()
            assertEquals(
                SettlementState.CONFIRMED,
                ramp.ledger.settlements.values
                    .last()
                    .state,
            )
            assertEquals(0, ramp.ledger.owedTotal(ramp.crynce.merchantId, TESTNET))
        }

    @Test
    fun `an order whose share of the fee is nothing in USDC is never swept`() =
        runTest {
            ramp.settingsRows.set(TESTNET) { it.copy(merchantShareBps = 10_000) }
            val order = ramp.completed()

            assertEquals(0, ramp.settle())
            assertTrue(ramp.ledger.settlements.isEmpty())
            assertEquals(
                ramp.clock.instant(),
                ramp.ledger.commissions
                    .getValue(order.orderId)
                    .settledAt,
            )
        }

    // What the merchant sees

    @Test
    fun `the overview's figures are the sum of the statement's lines, and swept plus held is Circaso's USDC share`() =
        runTest {
            val buyers = (2..6).map { "+25470000000$it" }
            buyers.forEachIndexed { index, number ->
                ramp.completed(ramp.user(number), phoneOf(number), shillings = 1_000L + index * 700)
                ramp.clock.advance(120)
                if (index == 2) {
                    ramp.settle()
                    ramp.treasury.settleAll()
                    ramp.settle()
                }
            }
            val id = ramp.crynce.merchantId

            val earnings = ramp.earnings(id, TESTNET, null).getOrNull()!!
            val page1 = ramp.statement(id, TESTNET, null, 3, null).getOrNull()!!
            val last = page1.lines.last()
            val cursor = PageCursor(last.completedAt, last.orderId.value)
            val page2 = ramp.statement(id, TESTNET, null, 3, cursor).getOrNull()!!
            val lines = page1.lines + page2.lines
            val totals = earnings.totals

            assertEquals(Periods.thisMonth(ramp.clock.instant()), earnings.period)
            assertEquals(5, lines.size)
            assertEquals(lines.map { it.orderId }.distinct(), lines.map { it.orderId })
            assertEquals(lines.sortedByDescending { it.completedAt }, lines, "newest first")
            assertEquals(page1.totals, totals)
            assertEquals(page2.totals, totals, "the totals are the period's, whatever the page")

            assertEquals(5, totals.orders)
            assertEquals(lines.sumOf { it.paidMinor }, totals.collectedMinor)
            assertEquals(lines.sumOf { it.releasedMinor }, totals.soldMinor)
            assertEquals(lines.sumOf { it.feeMinor }, totals.feesMinor)
            assertEquals(lines.sumOf { it.merchantMinor }, totals.merchantMinor)
            assertEquals(lines.sumOf { it.platformMinor }, totals.platformMinor)
            assertEquals(lines.sumOf { it.platformAssetMinor }, totals.platformAssetMinor)
            assertEquals(totals.feesMinor, totals.merchantMinor + totals.platformMinor)

            val swept = lines.filter { it.settlementId != null }.sumOf { it.platformAssetMinor }
            assertEquals(3, lines.count { it.settlementId != null }, "the three the sweep collected")
            assertEquals(swept, totals.sweptAssetMinor)
            assertEquals(totals.platformAssetMinor, totals.sweptAssetMinor + totals.heldAssetMinor)
            assertEquals(ramp.ledger.owedTotal(id, TESTNET), totals.heldAssetMinor)
            assertEquals(swept, ramp.treasury.balances.getValue("w-revenue"), "what the chain actually moved")

            assertEquals(200 to 7_000, earnings.feeBps to earnings.merchantShareBps)
            assertTrue(lines.all { it.txHash!!.startsWith("0xhash-") }, "the release's hash")
        }

    @Test
    fun `a period counts whole Nairobi days, and a sweep shows on the day it closed`() =
        runTest {
            // 20:30 UTC on the 21st is 23:30 in Nairobi; 21:30 UTC is 00:30 on the 22nd there
            ramp.clock.now = Instant.parse("2026-11-21T20:30:00Z")
            val late = ramp.completed()
            ramp.clock.now = Instant.parse("2026-11-21T21:30:00Z")
            val bob = ramp.user("+254700000002")
            val early = ramp.completed(bob, phoneOf("+254700000002"))
            ramp.settle()
            val id = ramp.crynce.merchantId
            val the21st = Period(LocalDate.parse("2026-11-21"), LocalDate.parse("2026-11-21"))
            val the22nd = Period(LocalDate.parse("2026-11-22"), LocalDate.parse("2026-11-22"))

            assertEquals(
                listOf(late.orderId),
                ramp.statement(id, TESTNET, the21st, 10, null).getOrNull()!!.lines.map {
                    it.orderId
                },
            )
            assertEquals(
                listOf(early.orderId),
                ramp.statement(id, TESTNET, the22nd, 10, null).getOrNull()!!.lines.map {
                    it.orderId
                },
            )
            assertEquals(
                1,
                ramp
                    .earnings(id, TESTNET, the21st)
                    .getOrNull()!!
                    .sweeps.size,
                "it closed the 21st",
            )
            assertTrue(
                ramp
                    .earnings(id, TESTNET, the22nd)
                    .getOrNull()!!
                    .sweeps
                    .isEmpty(),
            )
            assertTrue(
                ramp
                    .earnings(id, Network.MAINNET, the21st)
                    .getOrNull()!!
                    .totals.orders == 0,
            )
        }

    @Test
    fun `a line's sweep is shown only once that sweep is confirmed`() =
        runTest {
            ramp.completed()
            val id = ramp.crynce.merchantId

            ramp.settle()
            assertNull(
                ramp
                    .statement(id, TESTNET, null, 10, null)
                    .getOrNull()!!
                    .lines
                    .single()
                    .settlementId,
                "still held",
            )

            ramp.treasury.settleAll()
            ramp.settle()
            val line =
                ramp
                    .statement(id, TESTNET, null, 10, null)
                    .getOrNull()!!
                    .lines
                    .single()
            assertEquals(
                ramp.ledger.settlements.keys
                    .single(),
                line.settlementId,
            )
        }
}
