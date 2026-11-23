package com.circaso.ramp

import com.circaso.integrations.api.PayoutReceipt
import com.circaso.integrations.api.PayoutRejection
import com.circaso.integrations.api.PayoutResult
import com.circaso.integrations.api.PayoutStatus
import com.circaso.ramp.api.OffRampCompleted
import com.circaso.ramp.api.OrderFailed
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PayoutFeeBand
import com.circaso.ramp.domain.models.PayoutIssue
import com.circaso.ramp.domain.models.QuoteCommand
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.Resolution
import com.circaso.ramp.domain.models.ResolveCommand
import com.circaso.ramp.domain.models.StartOffRampCommand
import com.circaso.ramp.domain.support.SaleFlow
import com.circaso.ramp.domain.support.SalePayouts
import com.circaso.ramp.domain.support.SaleRefunds
import com.circaso.ramp.domain.support.Stats
import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The off-ramp (decision 2026-11-23), over the in-memory fakes: a user sells USDC and is paid shillings to their own
 * M-Pesa from a merchant's FinoQ account. Money is never lost, never paid twice and the USDC is never the merchant's
 * before the provider itself says the user was paid.
 *
 * The harness: 130 shillings a dollar, Circaso's fee 2 % split 70/30, the provider's payout fee KES 5. Selling 10
 * USDC is worth KES 1 300; the fee is 26 and the payout fee 5, so the user receives KES 1 269 and the merchant holds
 * KES 1 274 at the provider for it.
 */
class OffRampTest {
    private val ramp = Harness()

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private fun <T> Resource<T>.data() = (this as Resource.Success).data

    private fun ended(order: Order) = ramp.now(order).let { it.state to it.failureReason }

    private fun outcomeOf(order: Order) = ramp.now(order).let { it.state to it.payoutOutcome }

    private fun holdOf(order: Order) = ramp.payoutHolds.rows[order.orderId]?.state

    private fun usdcHoldOf(order: Order) = ramp.reservations.rows[order.orderId]

    // ---- the quote ----

    @Test
    fun `a sale is priced from the USDC sent - Circaso's fee as on a purchase, the provider's fee on top`() =
        runTest {
            ramp.openSelling()

            val quote = ramp.saleQuote(usdcMinor = 10_000_000).data()

            assertEquals(Direction.OFF_RAMP, quote.direction)
            assertEquals(10_000_000, quote.assetMinor)
            assertEquals(FiatAmount(126_900, "KES"), quote.fiat, "1300 - 26 - 5, rounded down to the shilling")
            assertEquals(2_600L to 500L, quote.feeMinor to quote.providerFeeMinor)
            assertEquals("LIVE_TABLE", quote.providerFeeSource)
            assertEquals(listOf(127_400L), ramp.providerFees.asked, "the payout fee is asked of the amount before it")
        }

    @Test
    fun `a sale is priced from the shillings wanted - exactly that is paid out, the rounding goes to the fee`() =
        runTest {
            ramp.openSelling()

            val quote = ramp.createQuote(QuoteCommand(ramp.amina, TESTNET, Direction.OFF_RAMP, CHAIN, 100_000, null))

            val sale = quote.data()
            assertEquals(FiatAmount(100_000, "KES"), sale.fiat)
            assertEquals(7_888_616, sale.assetMinor, "rounded up to the smallest unit")
            assertEquals(500, sale.providerFeeMinor)
            assertEquals(2_052, sale.feeMinor, "what is left of 102 552.00 after the payout and its fee")
            assertTrue(sale.feeMinor >= 102_552 * 200 / 10_000, "never less than the settings' rate")
            assertEquals(
                "ramp.amount_invalid",
                ramp
                    .createQuote(QuoteCommand(ramp.amina, TESTNET, Direction.OFF_RAMP, CHAIN, 100_050, null))
                    .error()
                    .code,
            )
        }

    @Test
    fun `the provider's fee comes from its own table, then staff's, and is never invented`() =
        runTest {
            ramp.openSelling()
            ramp.providerFees.payoutFeeMinor = null

            assertEquals("ramp.payout_fee_unavailable", ramp.saleQuote().error().code, "no table at all: no quote")

            ramp.settingsRows.set(TESTNET) {
                it.copy(payoutFeeBands = listOf(PayoutFeeBand(50_000, 300), PayoutFeeBand(200_000, 1_100)))
            }
            ramp.book.invalidate()
            val quote = ramp.saleQuote().data()
            assertEquals(1_100L to "ADMIN_TABLE", quote.providerFeeMinor to quote.providerFeeSource)
            assertEquals(FiatAmount(126_300, "KES"), quote.fiat)

            val tooBig = ramp.createQuote(QuoteCommand(ramp.amina, TESTNET, Direction.OFF_RAMP, CHAIN, 300_000, null))
            assertEquals("ramp.payout_fee_unavailable", tooBig.error().code, "above staff's last band: no fee there")
        }

    @Test
    fun `selling has its own switch and limits`() =
        runTest {
            assertEquals("ramp.offramp_unavailable", ramp.saleQuote().error().code, "selling starts OFF")
            ramp.openSelling()
            ramp.settingsRows.set(TESTNET) { it.copy(offRampMinMinor = 200_000) }
            ramp.book.invalidate()

            assertEquals("ramp.limit_exceeded", ramp.saleQuote().error().code)
            ramp.settingsRows.set(TESTNET) { it.copy(offRampState = RampState.PREVIEW, offRampMinMinor = 1_000) }
            ramp.book.invalidate()
            assertEquals("ramp.offramp_unavailable", ramp.saleQuote().error().code, "not on the preview list")
        }

    // ---- the order (R7) ----

    @Test
    fun `R7 routes the sale to a seller, holds their shillings and says where to send the USDC`() =
        runTest {
            ramp.openSelling()

            val view = ramp.sell().data()

            val order = view.order
            assertEquals(OrderState.AWAITING_TRANSFER, order.state)
            assertEquals(ramp.crynce.merchantId, order.merchantId)
            assertEquals(ramp.walletOf(ramp.crynce).address, view.depositAddress)
            assertEquals("0xuser1", order.refundAddress, "the user's own wallet: where the USDC comes from")
            assertEquals(ramp.clock.instant().plusSeconds(900), order.paymentDeadline)
            assertEquals(
                "OPEN" to 127_400L,
                holdOf(order) to
                    ramp.payoutHolds.rows
                        .getValue(order.orderId)
                        .amountMinor,
            )
            assertNull(usdcHoldOf(order), "no USDC is held before it arrives")
            assertEquals(127_400, ramp.payoutHolds.openTotal(ramp.crynce.merchantId))
            val routed = ramp.lastRouting()
            assertEquals(Direction.OFF_RAMP, routed.order.direction)
            assertEquals(127_400, routed.order.needMinor, "the payout and its fee")
            assertEquals(listOf("held"), routed.steps.map { it.result })
            assertTrue(ramp.collections.payoutCalls.isEmpty(), "nothing is paid before the USDC arrives")
        }

    @Test
    fun `a repeated R7 finds the same sale, and only the account's own number is paid`() =
        runTest {
            ramp.openSelling()
            val quote = ramp.saleQuote().data()
            val first = ramp.startOffRamp(StartOffRampCommand(ramp.amina, TESTNET, quote.quoteId, ramp.phone)).data()

            val again = ramp.startOffRamp(StartOffRampCommand(ramp.amina, TESTNET, quote.quoteId, ramp.phone)).data()

            assertEquals(first.order.orderId, again.order.orderId)
            assertEquals(1, ramp.payoutHolds.rows.size)
            val bob = ramp.user("+254733000111")
            val other = ramp.saleQuote(bob).data()
            val notTheirs = ramp.startOffRamp(StartOffRampCommand(bob, TESTNET, other.quoteId, ramp.phone))
            assertEquals("ramp.phone_not_yours", notTheirs.error().code)
        }

    @Test
    fun `a seller whose payout balance is unknown, short after open sales, or who has no account takes no sale`() =
        runTest {
            ramp.openSelling(payoutMinor = 200_000)
            ramp.sale()

            val bob = ramp.user("+254733000111")
            assertEquals(
                "ramp.no_merchant_available",
                ramp.sell(bob, PhoneNumber.parse("+254733000111")!!).error().code,
            )
            assertEquals(
                "FLOAT_SHORT",
                ramp
                    .lastRouting()
                    .plan.candidates
                    .single()
                    .exclusion
                    ?.name,
                "200 000 - 127 400 held",
            )

            ramp.collections.payoutBalances.remove("Crynce-user")
            val carol = ramp.user("+254733000222")
            assertEquals(
                "ramp.no_merchant_available",
                ramp.sell(carol, PhoneNumber.parse("+254733000222")!!).error().code,
            )
            assertEquals(
                "FLOAT_UNKNOWN",
                ramp
                    .lastRouting()
                    .plan.candidates
                    .single()
                    .exclusion
                    ?.name,
                "unknown is never enough",
            )

            ramp.merchants.rows[ramp.crynce.merchantId] =
                ramp.merchants.rows
                    .getValue(ramp.crynce.merchantId)
                    .copy(payoutAccountId = null)
            val dan = ramp.user("+254733000333")
            assertEquals(
                "ramp.no_merchant_available",
                ramp.sell(dan, PhoneNumber.parse("+254733000333")!!).error().code,
            )
            assertEquals(
                "DIRECTION_OFF",
                ramp
                    .lastRouting()
                    .plan.candidates
                    .single()
                    .exclusion
                    ?.name,
            )
        }

    // ---- the happy path ----

    @Test
    fun `the USDC arrives, the payout is sent once, and only the provider's word completes the sale`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()

            ramp.transfer(order)

            val paying = ramp.now(order)
            assertEquals(OrderState.PAYING_OUT, paying.state)
            assertEquals(10_000_000L to "OPEN", usdcHoldOf(order)!!.amountMinor to usdcHoldOf(order)!!.state)
            assertEquals("HOLD", usdcHoldOf(order)!!.kind, "the USDC is held in the merchant's float")
            val payout = ramp.collections.payouts.single()
            assertEquals(ramp.phone, payout.payee)
            assertEquals(FiatAmount(126_900, "KES"), payout.amount)
            assertEquals(order.orderId.toString(), payout.reference)
            assertEquals("4242", payout.accountId)
            assertEquals("https://api.test/v1/hooks/payhero/crynce-0123456789abcdef", payout.callbackUrl)
            assertEquals("ACCEPTED" to "PO-1", paying.payoutOutcome to paying.payoutProviderReference)
            assertEquals(500, paying.providerFeeActualMinor, "the tariff when it was sent")

            ramp.payoutCallback(order)
            assertEquals(OrderState.PAYING_OUT, ramp.now(order).state, "the provider says pending: nothing moves")

            ramp.collections.payoutPaid(order.orderId.toString(), receipt = "QP77XY")
            ramp.payoutCallback(order)

            val done = ramp.now(order)
            assertEquals(OrderState.COMPLETED, done.state)
            assertEquals("QP77XY", done.mpesaReceipt)
            assertEquals("CONSUMED", usdcHoldOf(order)!!.state, "the USDC is the merchant's now")
            assertEquals("CONSUMED", holdOf(order))
            assertEquals(1, ramp.collections.payouts.size, "sent once")
            val commission = ramp.ledger.commissions.getValue(order.orderId)
            assertEquals(
                Triple(1_820L, 780L, 60_000L),
                commission.split.let { Triple(it.merchantMinor, it.platformMinor, it.platformAssetMinor) },
            )
            assertEquals(OffRampCompleted.TYPE, ramp.bus.types().last())
            assertEquals(
                listOf(OrderState.FUNDED, OrderState.PAYING_OUT, OrderState.COMPLETED),
                ramp.frames.states,
            )
        }

    @Test
    fun `a callback that rings twice completes the sale once`() =
        runTest {
            ramp.openSelling()
            val order = ramp.paying()
            ramp.collections.payoutPaid(order.orderId.toString())

            ramp.payoutCallback(order, times = 2)

            assertEquals(OrderState.COMPLETED, ramp.now(order).state)
            assertEquals(1, ramp.bus.types().count { it == OffRampCompleted.TYPE })
            assertEquals(1, ramp.ledger.commissions.size)
            assertEquals(1, ramp.collections.payouts.size)
        }

    @Test
    fun `a callback that never comes is made up for by the sweep, and a forged one changes nothing`() =
        runTest {
            ramp.openSelling()
            val order = ramp.paying()
            ramp.payoutCallback("not-a-key", order.orderId.toString())
            ramp.payoutCallback(
                ramp.merchants.rows
                    .getValue(order.merchantId)
                    .callbackKey!!,
                "garbage",
            )
            assertTrue(ramp.collections.payoutLookups.isEmpty())

            ramp.collections.payoutPaid(order.orderId.toString())
            ramp.clock.advance(15)
            ramp.payoutSweep()

            assertEquals(OrderState.COMPLETED, ramp.now(order).state)
        }

    // ---- what sending answers ----

    @Test
    fun `refused for the merchant - nothing sent, the same merchant is asked again later, and then it goes out`() =
        runTest {
            ramp.openSelling()
            ramp.collections.nextPayouts += PayoutResult.Rejected(PayoutRejection.CREDENTIALS_REFUSED)
            val order = ramp.paying()

            val back = ramp.now(order)
            assertEquals(OrderState.FUNDED, back.state)
            assertEquals("NOT_SENT:rejected:CREDENTIALS_REFUSED", back.payoutOutcome)
            assertEquals(
                PayoutIssue.PAYOUT_REFUSED,
                ramp.merchants.rows
                    .getValue(order.merchantId)
                    .payoutIssue,
            )
            assertTrue(ramp.collections.payouts.isEmpty())
            ramp.payoutSweep()
            assertEquals(OrderState.FUNDED, ramp.now(order).state, "not before its retry time")

            ramp.clock.advance(121)
            ramp.payoutSweep()

            val retried = ramp.now(order)
            assertEquals(OrderState.PAYING_OUT, retried.state)
            assertEquals(2, retried.payoutAttempts)
            assertEquals(
                "${order.orderId}-2",
                ramp.collections.payouts
                    .single()
                    .reference,
                "a new reference each try",
            )
            assertNull(
                ramp.merchants.rows
                    .getValue(order.merchantId)
                    .payoutIssue,
                "a payout went out: cleared",
            )
        }

    @Test
    fun `a payout the provider can't carry is refunded at once - asking again can't change it`() =
        runTest {
            ramp.openSelling()
            ramp.collections.nextPayouts += PayoutResult.Rejected(PayoutRejection.AMOUNT_BELOW_COST)
            val order = ramp.paying()

            assertEquals(OrderState.REFUNDING to SaleFlow.PAYOUT_REJECTED, ended(order))
            assertEquals("RELEASED", holdOf(order), "the merchant's shillings are free again")
            assertEquals("OPEN", usdcHoldOf(order)!!.state, "the USDC stays held until it is back with the user")

            ramp.release()
            val send = ramp.treasury.sends.single()
            assertEquals("0xuser1", send.destinationAddress)
            assertEquals(BigInteger.valueOf(10_000_000), send.amountMinor)
            assertEquals(SaleRefunds.refundKey(order.orderId), send.idempotencyKey)
            ramp.release()
            assertEquals(1, ramp.treasury.sends.size, "sent once however often the job runs")
            ramp.treasury.settleAll()
            ramp.release()

            val refunded = ramp.now(order)
            assertEquals(OrderState.REFUNDED, refunded.state)
            assertEquals("RELEASED", usdcHoldOf(order)!!.state)
            assertEquals(OrderFailed.TYPE, ramp.bus.types().last())
            assertTrue(ramp.ledger.commissions.isEmpty(), "no commission on a sale that didn't happen")
        }

    @Test
    fun `no payout balance - nothing sent, retried within the window, then refunded, counted against them`() =
        runTest {
            ramp.openSelling()
            repeat(20) { ramp.collections.nextPayouts += PayoutResult.InsufficientBalance }
            val order = ramp.paying()

            assertEquals(OrderState.FUNDED, ramp.now(order).state)
            assertEquals(
                PayoutIssue.PAYOUT_BALANCE,
                ramp.merchants.rows
                    .getValue(order.merchantId)
                    .payoutIssue,
            )
            assertTrue("payout_balance" in ramp.alerts.stuck)

            repeat(16) {
                ramp.clock.advance(121)
                ramp.payoutSweep()
            }

            val refunding = ramp.now(order)
            assertEquals(OrderState.REFUNDING to SaleFlow.PAYOUT_FAILED, refunding.state to refunding.failureReason)
            assertTrue(ramp.collections.payouts.isEmpty(), "nothing ever went out")
            ramp.release()
            ramp.treasury.settleAll()
            ramp.release()
            assertEquals(OrderState.REFUNDED, ramp.now(order).state)
            val settings = ramp.engineBook.of(TESTNET, Direction.OFF_RAMP).settings
            val stats = ramp.statsBook.stats(order.merchantId, TESTNET, Direction.OFF_RAMP, settings)
            assertEquals(1, stats.merchantFailures, "payout_failed counts against the merchant")
        }

    @Test
    fun `the provider out of reach - nothing sent, tried again shortly`() =
        runTest {
            ramp.openSelling()
            ramp.collections.nextPayouts += PayoutResult.Unavailable
            val order = ramp.paying()

            assertEquals(OrderState.FUNDED to "NOT_SENT:unavailable", outcomeOf(order))
            ramp.clock.advance(31)
            ramp.payoutSweep()

            assertEquals(OrderState.PAYING_OUT, ramp.now(order).state)
            assertEquals(1, ramp.collections.payouts.size)
        }

    @Test
    fun `no clear answer - it is looked up and never sent again, and completes when the provider says paid`() =
        runTest {
            ramp.openSelling()
            ramp.collections.nextPayouts += PayoutResult.Unknown
            val order = ramp.paying()

            val unknown = ramp.now(order)
            assertEquals(OrderState.PAYING_OUT to "UNKNOWN", unknown.state to unknown.payoutOutcome)
            assertEquals(
                order.orderId.toString(),
                ramp.collections.payoutLookups
                    .single()
                    .reference,
                "looked up by ours",
            )
            repeat(5) {
                ramp.clock.advance(15)
                ramp.payoutSweep()
            }
            assertEquals(1, ramp.collections.payoutCalls.size, "never sent again")

            ramp.collections.payoutPaid(order.orderId.toString())
            ramp.clock.advance(15)
            ramp.payoutSweep()
            assertEquals(OrderState.COMPLETED, ramp.now(order).state)
        }

    @Test
    fun `a timeout after the payout left is an unknown - looked up, never resent, never refunded`() =
        runTest {
            ramp.openSelling()
            ramp.collections.timeOutAfterSend = true
            val order = ramp.paying()

            assertEquals(OrderState.PAYING_OUT to "UNKNOWN", outcomeOf(order))
            ramp.clock.advance(15)
            ramp.payoutSweep()
            assertEquals(1, ramp.collections.payoutCalls.size)
            assertEquals(OrderState.PAYING_OUT, ramp.now(order).state)
            assertTrue(ramp.treasury.sends.isEmpty())
        }

    @Test
    fun `an unknown the provider can't find by our reference goes to a person, not to a resend or a refund`() =
        runTest {
            ramp.openSelling()
            ramp.collections.unknownReached = false
            ramp.collections.nextPayouts += PayoutResult.Unknown
            val order = ramp.paying()

            val review = ramp.now(order)
            assertEquals(OrderState.REVIEW to SalePayouts.PAYOUT_UNCONFIRMED, review.state to review.failureReason)
            ramp.clock.advance(3_600)
            ramp.payoutSweep()
            ramp.release()
            assertEquals(1, ramp.collections.payoutCalls.size)
            assertTrue(ramp.treasury.sends.isEmpty(), "no refund while a payout may have gone out")
            assertEquals("OPEN" to "OPEN", usdcHoldOf(order)!!.state to holdOf(order))
        }

    // ---- what the lookup says ----

    @Test
    fun `failed once is sent again with a new reference, failed twice is refunded`() =
        runTest {
            ramp.openSelling()
            val order = ramp.paying()
            ramp.collections.payoutStatuses[order.orderId.toString()] = PayoutStatus.Failed("FAILED")

            ramp.payoutCallback(order)
            ramp.payoutSweep()

            val again = ramp.now(order)
            assertEquals(OrderState.PAYING_OUT, again.state)
            assertEquals("${order.orderId}-2" to 1, again.payoutReference to again.payoutFailures)
            ramp.collections.payoutStatuses["${order.orderId}-2"] = PayoutStatus.Failed("CANCELLED")
            ramp.payoutCallback(order)

            val refunding = ramp.now(order)
            assertEquals(OrderState.REFUNDING to SaleFlow.PAYOUT_FAILED, refunding.state to refunding.failureReason)
            assertEquals(2, ramp.collections.payouts.size)
        }

    @Test
    fun `the provider denying its own reference, or paying another amount, goes to a person`() =
        runTest {
            ramp.openSelling()
            val denied = ramp.paying()
            ramp.collections.payoutStatuses.remove(denied.orderId.toString())
            ramp.payoutCallback(denied)
            assertEquals(OrderState.REVIEW to SalePayouts.PAYOUT_NOT_FOUND, ended(denied))

            val bob = ramp.user("+254733000111")
            val other = ramp.paying(bob, PhoneNumber.parse("+254733000111")!!)
            ramp.collections.payoutStatuses[other.orderId.toString()] =
                PayoutStatus.Paid("QP1", FiatAmount(1_000, "KES"), other.orderId.toString())
            ramp.payoutCallback(other)
            assertEquals(OrderState.REVIEW to SalePayouts.PAYOUT_MISMATCH, ended(other))
            assertTrue(ramp.ledger.commissions.isEmpty())
        }

    @Test
    fun `a payout still pending when the window runs out goes to a person, never to a refund`() =
        runTest {
            ramp.openSelling()
            val order = ramp.paying()

            ramp.clock.advance(1_801)
            ramp.payoutSweep()

            assertEquals(OrderState.REVIEW to SalePayouts.PAYOUT_UNRESOLVED, ended(order))
            ramp.collections.payoutPaid(order.orderId.toString())
            ramp.payoutCallback(order)
            assertEquals(
                OrderState.COMPLETED,
                ramp.now(order).state,
                "a late payout confirmed by the provider still completes",
            )
        }

    @Test
    fun `a merchant offline for the whole payout window misses the payout - refunded and counted against them`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()
            ramp.sessions.keys.remove(order.merchantId)

            ramp.transfer(order)
            assertEquals(OrderState.FUNDED, ramp.now(order).state, "funded, waiting for the merchant's keys")
            ramp.clock.advance(1_801)
            ramp.payoutSweep()

            assertEquals(OrderState.REFUNDING to SaleFlow.PAYOUT_MISSED, ended(order))
            ramp.release()
            ramp.treasury.settleAll()
            ramp.release()
            val settings = ramp.engineBook.of(TESTNET, Direction.OFF_RAMP).settings
            assertEquals(
                1,
                ramp.statsBook.stats(order.merchantId, TESTNET, Direction.OFF_RAMP, settings).merchantExpiries,
            )
        }

    // ---- the transfer ----

    @Test
    fun `no transfer by the deadline - expired, the shillings freed, and the user never counts against anyone`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()

            ramp.clock.advance(901)
            ramp.payoutSweep()

            val expired = ramp.now(order)
            assertEquals(OrderState.EXPIRED to SaleFlow.NEVER_FUNDED, expired.state to expired.failureReason)
            assertEquals("RELEASED", holdOf(order))
            assertEquals(0L, ramp.counters.sold[ramp.amina to TESTNET], "the day is given back")
            assertTrue(!Stats.isCounted(OrderState.EXPIRED, SaleFlow.NEVER_FUNDED))
        }

    @Test
    fun `USDC that lands after the sale expired goes back to the user`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()
            ramp.clock.advance(901)
            ramp.payoutSweep()

            ramp.transfer(ramp.now(order))

            val late = ramp.now(order)
            assertEquals(OrderState.REFUNDING to SaleFlow.LATE_TRANSFER, late.state to late.failureReason)
            assertEquals(10_000_000L, late.fundedMinor)
            assertTrue(ramp.collections.payoutCalls.isEmpty())
            ramp.release()
            ramp.treasury.settleAll()
            ramp.release()
            assertEquals(OrderState.REFUNDED, ramp.now(order).state)
            assertEquals(
                "0xuser1",
                ramp.treasury.sends
                    .single()
                    .destinationAddress,
            )
        }

    @Test
    fun `the wrong amount is all refunded, the wrong coin or sender goes to a person`() =
        runTest {
            ramp.openSelling()
            val short = ramp.sale()
            ramp.transfer(short, amountMinor = 9_000_000)
            assertEquals(OrderState.REFUNDING to SaleFlow.AMOUNT_MISMATCH, ended(short))
            assertEquals(9_000_000L, ramp.now(short).fundedMinor, "what arrived is what goes back")

            val bob = ramp.user("+254733000111")
            val coin = ramp.sale(bob, PhoneNumber.parse("+254733000111")!!)
            ramp.transfer(coin, token = "0xbad")
            assertEquals(OrderState.REVIEW to "wrong_token", ended(coin))

            val carol = ramp.user("+254733000222")
            val stranger = ramp.sale(carol, PhoneNumber.parse("+254733000222")!!)
            ramp.transfer(stranger, from = "0xsomeone-else")
            assertEquals(OrderState.REVIEW to "transfer_mismatch", ended(stranger))
            assertTrue(ramp.collections.payoutCalls.isEmpty(), "nobody was paid on any of these")
        }

    @Test
    fun `a transfer the custodian hasn't recorded yet waits, is checked again, and funds the sale once it is`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()
            val transferId = ramp.transfer(order, recorded = false)
            assertEquals(OrderState.AWAITING_TRANSFER, ramp.now(order).state)
            assertEquals(transferId.value, ramp.now(order).walletTransferId)

            ramp.clock.advance(901)
            ramp.payoutSweep()
            assertEquals(OrderState.AWAITING_TRANSFER, ramp.now(order).state, "a known transfer is never expired")

            val hash =
                ramp.wallets.transfers
                    .getValue(transferId)
                    .txHash!!
            ramp.deposit(ramp.walletOf(ramp.crynce), hash, order.assetMinor, from = "0xuser1")
            ramp.clock.advance(11)
            ramp.payoutSweep()
            assertEquals(OrderState.PAYING_OUT, ramp.now(order).state)
        }

    @Test
    fun `a sale is cancelled only before its transfer is known`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()

            val cancelled = ramp.cancelOrder(ramp.amina, order.orderId).data()
            assertEquals(OrderState.CANCELLED, cancelled.order.state)
            assertEquals("RELEASED", holdOf(order))

            val bob = ramp.user("+254733000111")
            val sent = ramp.sale(bob, PhoneNumber.parse("+254733000111")!!)
            ramp.transfer(sent, recorded = false)
            assertEquals("ramp.order_not_cancellable", ramp.cancelOrder(bob, sent.orderId).error().code)
        }

    // ---- staff (RO4) ----

    @Test
    fun `staff mark a sale paid with its receipt, refund one, and re-check one through the app's process`() =
        runTest {
            ramp.openSelling()
            ramp.collections.unknownReached = false
            ramp.collections.nextPayouts += PayoutResult.Unknown
            val unconfirmed = ramp.paying()
            assertEquals(OrderState.REVIEW, ramp.now(unconfirmed).state)

            val refunded = ramp.resolveOrder(resolve(unconfirmed, "REFUND"))
            assertEquals(OrderState.REFUNDING, refunded.data().order.state)

            val bob = ramp.user("+254733000111")
            ramp.collections.nextPayouts += PayoutResult.Unknown
            val seen = ramp.paying(bob, PhoneNumber.parse("+254733000111")!!)
            assertEquals("ramp.receipt_required", ramp.resolveOrder(resolve(seen, "MARK_PAID")).error().code)
            val paid = ramp.resolveOrder(resolve(seen, "MARK_PAID", "QPSEEN1")).data()
            assertEquals(OrderState.COMPLETED to "QPSEEN1", paid.order.state to paid.order.mpesaReceipt)
            assertEquals("ramp.resolution_invalid", ramp.resolveOrder(resolve(seen, "REFUND")).error().code)

            val carol = ramp.user("+254733000222")
            ramp.collections.nextPayouts += PayoutResult.Unknown
            val asked = ramp.paying(carol, PhoneNumber.parse("+254733000222")!!)
            assertEquals("queued", ramp.resolveOrder(resolve(asked, "RECHECK")).data().providerSaid)
            ramp.collections.payoutStatuses[asked.orderId.toString()] =
                PayoutStatus.Paid("QPLATE", asked.fiat, asked.orderId.toString())
            ramp.payoutSweep()
            assertEquals(OrderState.COMPLETED, ramp.now(asked).state)
        }

    @Test
    fun `staff resolve a purchase too - paid after its expiry is released, an unpaid one is closed`() =
        runTest {
            val late = ramp.prompted()
            ramp.clock.advance(121)
            ramp.flow.review(ramp.now(late), "paid_after_expiry")
            ramp.reservations.release(late.orderId, ramp.clock.instant())

            val paid = ramp.resolveOrder(resolve(late, "MARK_PAID", "SKLSTAFF1")).data()
            assertEquals(OrderState.PAID, paid.order.state)
            assertEquals(
                "OPEN",
                ramp.reservations.rows
                    .getValue(late.orderId)
                    .state,
                "its float is held again",
            )

            val bob = ramp.user("+254733000111")
            val unpaid = ramp.prompted(bob, PhoneNumber.parse("+254733000111")!!)
            ramp.flow.review(unpaid, "payment_unresolved")
            val closed = ramp.resolveOrder(resolve(unpaid, "REFUND")).data()
            assertEquals(OrderState.FAILED, closed.order.state)
            assertEquals(
                "RELEASED",
                ramp.reservations.rows
                    .getValue(unpaid.orderId)
                    .state,
            )
        }

    private fun resolve(
        order: Order,
        resolution: String,
        receipt: String? = null,
    ) = ResolveCommand(
        order.orderId,
        Resolution.valueOf(resolution),
        receipt,
        "staff-1",
    )

    // ---- the merchant's test payout ----

    @Test
    fun `a test payout goes to the merchant's own phone only, once, and its outcome is read from the provider`() =
        runTest {
            ramp.openSelling()
            val merchantId = ramp.crynce.merchantId
            ramp.collections.payoutBalances["Crynce-user"] = 50_000

            val sent = ramp.testPayouts.start(merchantId, TESTNET, 1_000).data()

            val request = ramp.collections.payouts.single()
            assertEquals(PhoneNumber.parse("+254700000000"), request.payee, "the merchant's own contact phone")
            assertEquals(FiatAmount(1_000, "KES"), request.amount)
            assertEquals(sent.testId.toString(), request.reference)
            assertEquals("ACCEPTED" to 50_000L, sent.state.name to sent.balanceBefore?.amountMinor)
            assertEquals(
                "ramp.test_payout_in_progress",
                ramp.testPayouts
                    .start(merchantId, TESTNET, 1_000)
                    .error()
                    .code,
            )

            ramp.collections.payoutPaid(sent.testId.toString(), receipt = "QPTEST1")
            ramp.collections.payoutBalances["Crynce-user"] = 48_991
            ramp.clock.advance(4)
            val paid = ramp.testPayouts.view(merchantId, null).data()

            assertEquals("PAID" to "QPTEST1", paid.state.name to paid.receipt)
            assertEquals(48_991L, paid.balanceAfter?.amountMinor)
            assertEquals(1, ramp.collections.payouts.size)
        }

    @Test
    fun `a test payout is KES 10 to 100, needs the account and the merchant online, a refusal sent nothing`() =
        runTest {
            ramp.openSelling()
            val merchantId = ramp.crynce.merchantId

            assertEquals(
                "ramp.amount_invalid",
                ramp.testPayouts
                    .start(merchantId, TESTNET, 999)
                    .error()
                    .code,
            )
            assertEquals(
                "ramp.amount_invalid",
                ramp.testPayouts
                    .start(merchantId, TESTNET, 10_100)
                    .error()
                    .code,
            )
            assertEquals(
                "ramp.amount_invalid",
                ramp.testPayouts
                    .start(merchantId, TESTNET, 1_050)
                    .error()
                    .code,
            )

            ramp.collections.nextPayouts += PayoutResult.InsufficientBalance
            val refused = ramp.testPayouts.start(merchantId, TESTNET, 2_000).data()
            assertEquals("NOT_SENT" to "insufficient_balance", refused.state.name to refused.outcome)

            ramp.collections.unknownReached = false
            ramp.collections.nextPayouts += PayoutResult.Unknown
            val unclear = ramp.testPayouts.start(merchantId, TESTNET, 2_000).data()
            assertEquals("NOT_FOUND", unclear.state.name, "looked up at once by our reference, never sent again")
            assertEquals(2, ramp.collections.payoutCalls.size)

            ramp.sessions.keys.remove(merchantId)
            assertEquals(
                "ramp.merchant_offline",
                ramp.testPayouts
                    .start(merchantId, TESTNET, 2_000)
                    .error()
                    .code,
            )
            ramp.merchants.rows[merchantId] =
                ramp.merchants.rows
                    .getValue(merchantId)
                    .copy(payoutAccountId = null)
            assertEquals(
                "ramp.merchant_setup_incomplete",
                ramp.testPayouts
                    .start(merchantId, TESTNET, 2_000)
                    .error()
                    .code,
            )
        }

    @Test
    fun `a test payout rung by the callback is looked up, and a callback for another merchant's test is ignored`() =
        runTest {
            ramp.openSelling()
            val other = ramp.merchant("Bidii", floatMinor = 0)
            ramp.seller(other)
            val test = ramp.testPayouts.start(ramp.crynce.merchantId, TESTNET, 1_000).data()
            ramp.collections.payoutPaid(test.testId.toString())

            ramp.payoutCallback(other.callbackKey!!, test.testId.toString())
            assertEquals(
                "ACCEPTED",
                ramp.testPayoutRows.rows
                    .getValue(test.testId)
                    .state.name,
            )

            ramp.payoutCallback(ramp.crynce.callbackKey!!, test.testId.toString())
            assertEquals(
                "PAID",
                ramp.testPayoutRows.rows
                    .getValue(test.testId)
                    .state.name,
            )
        }

    @Test
    fun `R1 says whether selling is open and anyone could take a sale, on its own switch`() =
        runTest {
            ramp.settingsRows.set(TESTNET) { it.copy(state = RampState.OFF) }
            ramp.book.invalidate()
            assertEquals("ramp.unavailable", ramp.status(ramp.amina, TESTNET).error().code, "neither is open")

            ramp.openSelling()
            val status = ramp.status(ramp.amina, TESTNET).data()

            assertTrue(status.offRamp.open && status.offRamp.merchantOnline)
            assertTrue(!status.onRampOpen && !status.merchantOnline, "buying stays closed")
            assertEquals(1_000L to 500_000L, status.offRamp.limits.minOrderMinor to status.offRamp.limits.maxOrderMinor)
        }

    @Test
    fun `an accepted payout that never resolves is not completed by anything but the provider`() =
        runTest {
            ramp.openSelling()
            ramp.collections.nextPayouts += PayoutResult.Accepted(PayoutReceipt("PO-x"))
            val order = ramp.paying()
            ramp.payoutCallback(order, times = 3)
            assertIs<PayoutStatus.Pending>(ramp.collections.payoutStatuses[order.orderId.toString()])
            assertEquals(OrderState.PAYING_OUT, ramp.now(order).state)
            assertTrue(ramp.ledger.commissions.isEmpty() && ramp.treasury.sends.isEmpty())
        }

    @Test
    fun `a confirmed transfer naming an unknown order is told to the people on call and moves nothing`() =
        runTest {
            ramp.saleTransfer(UUID.randomUUID(), UUID.randomUUID())

            assertEquals(listOf("unknown_ramp_order"), ramp.alerts.stuck)
            assertTrue(ramp.collections.payoutCalls.isEmpty())
        }

    @Test
    fun `each payout step is written down with ids and outcomes only`() =
        runTest {
            ramp.openSelling()
            val order = ramp.paying()

            assertTrue("payout sent ${order.orderId}#1 Accepted" in ramp.journal.steps, ramp.journal.steps.toString())
            assertTrue(ramp.journal.steps.none { "126900" in it || "+254" in it })
        }
}
