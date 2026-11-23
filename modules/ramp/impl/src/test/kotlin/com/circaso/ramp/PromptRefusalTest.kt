package com.circaso.ramp

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.MerchantOverview
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.models.StartOnRampCommand
import com.circaso.ramp.domain.support.OrderFlow
import com.circaso.ramp.domain.support.Prompted
import com.circaso.ramp.presentation.mappers.toResponse
import com.circaso.shared.ErrorKind
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.getOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the seller's provider refuses the prompt (the first real test: PayHero's `insufficient balance`, the merchant's
 * prepaid service wallet being empty): the next merchant is asked, the buyer is told plainly when nobody can take it,
 * and a merchant whose balance is the reason gets no more orders until they act.
 */
class PromptRefusalTest {
    private val ramp = Harness()
    private val pin = "482913"
    private val account = CollectionCredentials("api-user", "api-pass", "911")

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private val crynce get() = ramp.crynce.merchantId

    private fun refuse(name: String, with: IntegrationError = IntegrationError.Rejected("The channel is not active.")) {
        ramp.collections.refusals["$name-user"] = with
    }

    /** Crynce with a payment account sealed under [pin], so RM6 can be asked for them. */
    private suspend fun connected() {
        assertIs<Resource.Success<*>>(ramp.storePayments(crynce, TESTNET, PaymentAccountCommand(account, pin)))
    }

    @Test
    fun `a merchant whose provider refuses the prompt is passed over, and the next one takes the same quote`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            refuse("Crynce")
            val quote = (ramp.quote() as Resource.Success).data
            val command = StartOnRampCommand(ramp.amina, TESTNET, quote.quoteId, ramp.phone)

            val placed = (ramp.startOnRamp(command) as Resource.Success).data

            assertEquals(bidii.merchantId, placed.order.merchantId)
            assertEquals(OrderState.PAYMENT_REQUESTED to 2, placed.order.state to placed.order.attempt)
            assertEquals("Bidii", placed.merchantName)
            val refused =
                ramp.orders.rows.values
                    .single { it.merchantId == crynce }
            assertEquals(OrderState.FAILED to 1, refused.state to refused.attempt)
            assertEquals(OrderFlow.PROMPT_REFUSED, refused.failureReason)
            assertEquals(quote.quoteId, refused.quoteId)
            assertEquals(0, ramp.reservations.openTotal(crynce, TESTNET), "the refused merchant's float is free again")
            assertEquals(quote.assetMinor, ramp.reservations.openTotal(bidii.merchantId, TESTNET))
            assertEquals(1, ramp.collections.prompts.size, "one prompt reached the phone")
            assertEquals(quote.fiat.amountMinor, ramp.counters.daily.getValue(ramp.amina to TESTNET), "counted once")
            assertEquals(1, ramp.counters.prompts.getValue(ramp.amina))
            assertEquals(
                listOf(OrderState.PAYMENT_REQUESTED),
                ramp.frames.states,
                "the buyer never hears of the order they never saw",
            )
            assertNull(ramp.merchants.find(crynce)!!.paymentsIssue, "an ordinary refusal marks nobody")

            // the same request again finds the quote's latest order and prompts nobody
            val again = (ramp.startOnRamp(command) as Resource.Success).data
            assertEquals(placed.order.orderId, again.order.orderId)
            assertEquals(1, ramp.collections.prompts.size)
            assertEquals(2, ramp.orders.rows.size)
        }

    @Test
    fun `when every merchant's provider refuses, the buyer is told nothing was taken, with its own code`() =
        runTest {
            ramp.merchant("Bidii", floatMinor = 100_000_000)
            refuse("Crynce")
            refuse("Bidii")

            val error = ramp.buy().error()

            assertEquals(RampError.MerchantUnavailable, error)
            assertEquals("ramp.merchant_unavailable" to ErrorKind.CONFLICT, error.code to error.kind)
            assertEquals(
                "We couldn't start this payment right now. Nothing was taken. Please try again in a moment.",
                error.message,
            )
            assertEquals(
                listOf(OrderState.FAILED to 1, OrderState.FAILED to 2),
                ramp.orders.rows.values
                    .map { it.state to it.attempt },
            )
            assertTrue(ramp.collections.prompts.isEmpty())
            assertTrue(ramp.frames.states.isEmpty())
            assertEquals(0, ramp.counters.daily.getValue(ramp.amina to TESTNET), "the day's limit got it all back")
            assertNull(ramp.counters.prompts[ramp.amina], "a prompt that never reached the phone isn't counted")
            assertNull(ramp.orders.openOf(ramp.amina), "nothing stands in the way of trying again")
        }

    @Test
    fun `the provider out of reach is still a 502, and nobody else is asked on a guess`() =
        runTest {
            ramp.merchant("Bidii", floatMinor = 100_000_000)
            refuse("Crynce", IntegrationError.Unavailable("PayHero"))

            val error = ramp.buy().error()

            assertEquals(RampError.CollectionsUnavailable, error)
            assertEquals(ErrorKind.UPSTREAM, error.kind)
            val order =
                ramp.orders.rows.values
                    .single()
            assertEquals(OrderState.FAILED to "prompt_unconfirmed", order.state to order.failureReason)
            assertTrue(ramp.collections.prompts.isEmpty(), "a prompt may have gone out: a second one never follows it")
            assertEquals(listOf(OrderState.FAILED), ramp.frames.states)
            assertTrue("prompt_unconfirmed" in ramp.alerts.stuck, "a prompt may be open: people are told")
        }

    @Test
    fun `a refusal for the merchant's service balance marks them, takes them offline and routes around them`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            refuse("Crynce", IntegrationError.ServiceBalanceLow())

            val placed = ramp.prompted()

            assertEquals(bidii.merchantId, placed.merchantId)
            val marked = ramp.merchants.find(crynce)!!
            assertEquals(PaymentsIssue.SERVICE_BALANCE, marked.paymentsIssue)
            assertEquals(ramp.clock.instant(), marked.paymentsIssueAt)
            assertNull(marked.onlineUntil, "offline until they act")
            assertNull(ramp.sessions.of(crynce), "nothing of theirs waits for a payment, so their keys go too")
            assertEquals(
                OrderFlow.PROVIDER_BALANCE,
                ramp.orders.rows.values
                    .single { it.merchantId == crynce }
                    .failureReason,
            )
            assertEquals(listOf(OrderFlow.PROVIDER_BALANCE), ramp.alerts.stuck)

            // the portal is told what to say, and that going online again is the way out
            val overview = ramp.getMerchant(crynce, TESTNET).getOrNull()!!
            assertTrue(MerchantOverview.PROVIDER_BALANCE in overview.missing)
            val response = overview.toResponse()
            assertEquals("SERVICE_BALANCE", response.paymentsIssue!!.code)
            assertEquals(ramp.clock.instant().toString(), response.paymentsIssue!!.since)
            assertTrue("provider_balance" in response.missing)
            assertFalse(response.online.online)

            // staff see it on the merchant's summary
            assertEquals(
                PaymentsIssue.SERVICE_BALANCE,
                ramp
                    .merchantSummary(crynce, TESTNET, null)
                    .getOrNull()!!
                    .merchant.paymentsIssue,
            )

            // even back inside a trading window, a marked merchant is nobody's candidate
            ramp.merchants.setOnlineUntil(crynce, ramp.clock.instant().plusSeconds(3_600))
            ramp.sessions.keys[crynce] = ramp.sessions.keys.getValue(bidii.merchantId)
            ramp.merchants.rows.remove(bidii.merchantId)
            val bob = ramp.user("+254700000002")
            val refused = ramp.buy(bob, PhoneNumber.parse("+254700000002")!!).error()
            assertEquals(RampError.NoMerchantAvailable, refused)
        }

    @Test
    fun `a prompt that goes through clears a balance issue the merchant has put right`() =
        runTest {
            // an order reserved with Crynce just before the mark was made
            val bob = ramp.user("+254700000002")
            val reserved =
                ramp.prompted().copy(
                    orderId = RampOrderId.new(),
                    userId = bob,
                    state = OrderState.RESERVED,
                    providerReference = null,
                )
            ramp.orders.rows[reserved.orderId] = reserved
            ramp.merchants.setPaymentsIssue(crynce, PaymentsIssue.SERVICE_BALANCE, ramp.clock.instant())

            assertIs<Prompted.Sent>(ramp.flow.requestPayment(reserved))

            assertNull(ramp.merchants.find(crynce)!!.paymentsIssue)
        }

    // RM6: the check before going online

    @Test
    fun `going online is refused while the provider's service wallet is under the floor, naming the step`() =
        runTest {
            connected()
            ramp.collections.serviceBalances["api-user"] = 4_999

            val error = ramp.goOnline(crynce, TESTNET, pin, 60).error()

            assertEquals(RampError.ProviderBalanceLow, error)
            assertEquals("ramp.merchant_setup_incomplete" to ErrorKind.CONFLICT, error.code to error.kind)
            assertEquals(
                listOf("provider_balance"),
                error.details!!
                    .fields!!
                    .map { it.field },
            )
            assertEquals(
                "api-user",
                ramp.collections.balanceQueries
                    .single()
                    .username,
                "asked with their own keys",
            )
            assertNotEquals(RampError.MerchantSetupIncomplete.details, error.details)

            ramp.collections.serviceBalances["api-user"] = 5_000
            assertTrue(ramp.goOnline(crynce, TESTNET, pin, 60).getOrNull()!!.online, "at the floor is enough")
        }

    @Test
    fun `a wrong PIN is answered before the provider is asked anything`() =
        runTest {
            connected()
            ramp.collections.serviceBalances["api-user"] = 0

            assertEquals(RampError.PinInvalid, ramp.goOnline(crynce, TESTNET, "000000", 60).error())
            assertTrue(ramp.collections.balanceQueries.isEmpty())
        }

    @Test
    fun `a floor of 0 switches the check off, and a balance that can't be read never blocks going online`() =
        runTest {
            connected()
            assertTrue(ramp.goOnline(crynce, TESTNET, pin, 60).getOrNull()!!.online, "the provider couldn't say")
            assertEquals(1, ramp.collections.balanceQueries.size)

            ramp.settingsRows.set(TESTNET) { it.copy(providerFeeFloorMinor = 0) }
            ramp.book.invalidate()
            ramp.collections.serviceBalances["api-user"] = 0
            assertTrue(ramp.goOnline(crynce, TESTNET, pin, 60).getOrNull()!!.online)
            assertEquals(1, ramp.collections.balanceQueries.size, "not even asked")
        }

    @Test
    fun `a marked merchant goes online again once the wallet holds something, and that clears the mark`() =
        runTest {
            connected()
            ramp.settingsRows.set(TESTNET) { it.copy(providerFeeFloorMinor = 0) }
            ramp.book.invalidate()
            ramp.collections.refusals["api-user"] = IntegrationError.ServiceBalanceLow()
            assertEquals(RampError.MerchantUnavailable, ramp.buy().error())
            assertEquals(PaymentsIssue.SERVICE_BALANCE, ramp.merchants.find(crynce)!!.paymentsIssue)
            assertTrue(ramp.getMerchant(crynce, TESTNET).getOrNull()!!.canGoOnline, "RM6 is the way to clear it")

            ramp.collections.serviceBalances["api-user"] = 0
            assertEquals(
                RampError.ProviderBalanceLow,
                ramp.goOnline(crynce, TESTNET, pin, 60).error(),
                "still empty: the next prompt would be refused again, whatever the floor",
            )
            assertEquals(PaymentsIssue.SERVICE_BALANCE, ramp.merchants.find(crynce)!!.paymentsIssue)

            ramp.collections.serviceBalances["api-user"] = 20_000
            ramp.collections.refusals.clear()
            assertTrue(ramp.goOnline(crynce, TESTNET, pin, 60).getOrNull()!!.online)

            val cleared = ramp.merchants.find(crynce)!!
            assertNull(cleared.paymentsIssue)
            assertNull(cleared.paymentsIssueAt)
            assertTrue(
                ramp
                    .getMerchant(crynce, TESTNET)
                    .getOrNull()!!
                    .missing
                    .isEmpty(),
            )
            assertEquals(crynce, ramp.prompted().merchantId, "and they take orders again")
        }
}
