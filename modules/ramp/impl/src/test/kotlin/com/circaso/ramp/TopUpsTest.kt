package com.circaso.ramp

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.TopUpRejection
import com.circaso.integrations.api.TopUpResult
import com.circaso.integrations.api.TopUpStatus
import com.circaso.integrations.api.TopUpWallet
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.ProfileCommand
import com.circaso.ramp.domain.models.TopUp
import com.circaso.ramp.domain.models.TopUpCommand
import com.circaso.ramp.domain.models.TopUpConfirmation
import com.circaso.ramp.domain.models.TopUpFailure
import com.circaso.ramp.domain.models.TopUpState
import com.circaso.ramp.domain.models.TopUpTarget
import com.circaso.shared.DomainError
import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A merchant topping up their own FinoQ wallets with an M-Pesa prompt to their own phone (RM19, 2026-11-23): sent once,
 * one open per wallet, confirmed only by the provider's status or the wallet's balance, and a callback only a doorbell.
 */
class TopUpsTest {
    private val ramp = Harness()
    private val crynce = ramp.crynce.merchantId
    private val user = "Crynce-user"

    private fun <T> Resource<T>.error(): DomainError = (this as Resource.Error).error

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun balances(service: Long = 5_000, payment: Long = 20_000) {
        ramp.collections.serviceBalances[user] = service
        ramp.collections.payoutBalances[user] = payment
    }

    private suspend fun start(
        target: TopUpTarget = TopUpTarget.PAYHERO_SERVICE,
        amountMinor: Long = 50_000,
        merchantId: MerchantId = crynce,
        pin: String? = null,
    ): Resource<TopUp> = ramp.topUps.start(merchantId, TESTNET, TopUpCommand(target, amountMinor, pin))

    /** Reads the top-up as the portal does, once its last look is old enough to ask again. */
    private suspend fun read(topUp: TopUp): TopUp {
        ramp.clock.advance(4)
        return ramp.topUps.view(topUp.merchantId, topUp.topUpId).data()
    }

    // ---- sending ----

    @Test
    fun `the prompt goes to the merchant's own contact phone, once, for the wallet asked`() =
        runTest {
            ramp.seller(ramp.crynce)
            balances()

            val service = start(TopUpTarget.PAYHERO_SERVICE, 50_000).data()
            val payment = start(TopUpTarget.FINOQ_PAYMENT, 100_000).data()

            val (first, second) = ramp.collections.topUpCalls
            assertEquals(PhoneNumber.parse("+254700000000"), first.payer, "the merchant's own contact phone")
            assertEquals(TopUpWallet.COLLECTION_FEES to FiatAmount(50_000, "KES"), first.wallet to first.amount)
            assertEquals(TopUpWallet.PAYOUT to "4242", second.wallet to second.accountId)
            assertEquals(service.topUpId.toString(), first.reference)
            assertEquals("https://api.test/v1/hooks/payhero/crynce-0123456789abcdef", second.callbackUrl)
            assertEquals(TopUpState.REQUESTED to "TU-1", service.state to service.providerReference)
            assertEquals(5_000L to 20_000L, service.balanceBeforeMinor to payment.balanceBeforeMinor)
            assertEquals(2, ramp.collections.topUpCalls.size)
        }

    @Test
    fun `FinoQ's service wallet is its own wallet, topped up and confirmed by its own balance`() =
        runTest {
            ramp.seller(ramp.crynce)
            balances(service = 5_000)
            ramp.collections.payoutFeeBalances[user] = 700

            val topUp = start(TopUpTarget.FINOQ_SERVICE, 20_000).data()

            val sent = ramp.collections.topUpCalls.single()
            assertEquals(TopUpWallet.PAYOUT_FEES to "4242", sent.wallet to sent.accountId)
            assertEquals(700L, topUp.balanceBeforeMinor, "FinoQ's service wallet, not PayHero's KES 50")
            assertEquals(TopUpState.REQUESTED, start(TopUpTarget.PAYHERO_SERVICE).data().state, "each wallet its own")

            ramp.collections.topUpStatuses.remove(topUp.topUpId.toString())
            ramp.collections.serviceBalances[user] = 5_000 + 20_000
            assertEquals(TopUpState.REQUESTED, read(topUp).state, "PayHero's wallet rising says nothing of FinoQ's")
            ramp.collections.payoutFeeBalances[user] = 700 + 19_000
            val done = read(topUp)
            assertEquals(TopUpState.CONFIRMED to TopUpConfirmation.BALANCE, done.state to done.confirmedBy)
            assertEquals(
                19_700L,
                ramp.merchants
                    .balances(crynce)
                    .finoqService
                    ?.minor,
            )
        }

    @Test
    fun `a FinoQ service top-up needs the FinoQ account id`() =
        runTest {
            balances()
            assertEquals(RampError.TopUpAccountMissing, start(TopUpTarget.FINOQ_SERVICE).error())
            assertTrue(ramp.collections.topUpCalls.isEmpty())
        }

    @Test
    fun `one open top-up per wallet, the other wallet still free`() =
        runTest {
            ramp.seller(ramp.crynce)
            balances()
            val first = start(TopUpTarget.PAYHERO_SERVICE).data()

            assertEquals(RampError.TopUpInProgress, start(TopUpTarget.PAYHERO_SERVICE).error())
            assertEquals(TopUpState.REQUESTED, start(TopUpTarget.FINOQ_PAYMENT).data().state)
            assertEquals(2, ramp.collections.topUpCalls.size, "the refused one asked the provider nothing")

            ramp.collections.topUpPaid(first.topUpId.toString(), user)
            assertEquals(TopUpState.CONFIRMED, read(first).state)
            assertEquals(
                TopUpState.REQUESTED,
                start(TopUpTarget.PAYHERO_SERVICE).data().state,
                "free again once it closed",
            )
        }

    @Test
    fun `amounts are KES 10 to 150,000 in whole shillings, and the merchant must be approved and set up`() =
        runTest {
            balances()
            listOf(999L, 15_000_100L, 1_050L, 0L).forEach { amount ->
                val error = start(amountMinor = amount).error()
                assertEquals(
                    "ramp.amount_invalid" to "amountMinor",
                    error.code to
                        error.details
                            ?.fields
                            ?.single()
                            ?.field,
                )
            }
            assertEquals(TopUpState.REQUESTED, start(amountMinor = 1_000).data().state, "KES 10")

            val payment = start(TopUpTarget.FINOQ_PAYMENT).error()
            assertEquals(RampError.TopUpAccountMissing, payment, "the payment wallet needs the FinoQ account id")
            ramp.seller(ramp.crynce)
            assertEquals(TopUpState.REQUESTED, start(TopUpTarget.FINOQ_PAYMENT, 15_000_000).data().state, "KES 150,000")

            ramp.merchants.rows[crynce] =
                ramp.merchants.rows
                    .getValue(crynce)
                    .copy(state = MerchantState.SUSPENDED)
            assertEquals(RampError.MerchantNotApproved, start(TopUpTarget.PAYHERO_SERVICE, 2_000).error())
            assertEquals(2, ramp.collections.topUpCalls.size)
        }

    // ---- what the provider answered ----

    @Test
    fun `a refusal or an unreachable provider fails at once and says no prompt was sent`() =
        runTest {
            ramp.seller(ramp.crynce)
            balances()
            ramp.collections.nextTopUps += TopUpResult.Rejected(TopUpRejection.SERVICE_BALANCE_LOW)
            ramp.collections.nextTopUps += TopUpResult.Unavailable
            ramp.collections.nextTopUps += TopUpResult.Rejected(TopUpRejection.PAYER_NOT_ALLOWED)

            val low = start(TopUpTarget.FINOQ_PAYMENT).data()
            val unreachable = start(TopUpTarget.FINOQ_PAYMENT).data()
            val notOwn = start(TopUpTarget.FINOQ_PAYMENT).data()

            assertEquals(TopUpState.FAILED to TopUpFailure.SERVICE_BALANCE_LOW, low.state to low.failure)
            assertEquals(TopUpFailure.PROVIDER_UNAVAILABLE, unreachable.failure)
            assertEquals(TopUpFailure.PAYER_NOT_ALLOWED, notOwn.failure)
            assertNotNull(low.closedAt)
            assertTrue(ramp.collections.topUps.isEmpty())
        }

    @Test
    fun `the provider's paid confirms it, with the balance read again`() =
        runTest {
            balances(service = 5_000)
            val topUp = start(amountMinor = 50_000).data()

            assertEquals(TopUpState.REQUESTED, read(topUp).state, "pending: nothing moves")
            ramp.collections.topUpPaid(topUp.topUpId.toString(), user)
            val done = read(topUp)

            assertEquals(TopUpState.CONFIRMED to TopUpConfirmation.STATUS, done.state to done.confirmedBy)
            assertEquals(5_000L to 55_000L, done.balanceBeforeMinor to done.balanceAfterMinor)
            assertEquals(
                "QT" +
                    topUp.topUpId
                        .toString()
                        .takeLast(6)
                        .uppercase(),
                done.receipt,
            )
            assertEquals(
                55_000L,
                ramp.merchants
                    .balances(crynce)
                    .payheroService
                    ?.minor,
                "RM2's reading is refreshed",
            )
        }

    @Test
    fun `the balance rising by the amount confirms it when the status can't say`() =
        runTest {
            balances(service = 5_000)
            val topUp = start(amountMinor = 50_000).data()
            ramp.collections.topUpStatuses.remove(topUp.topUpId.toString())

            ramp.collections.serviceBalances[user] = 5_000 + 44_000
            assertEquals(TopUpState.REQUESTED, read(topUp).state, "less than 90 % of the amount isn't it")
            ramp.collections.serviceBalances[user] = 5_000 + 49_500
            val done = read(topUp)

            assertEquals(TopUpState.CONFIRMED to TopUpConfirmation.BALANCE, done.state to done.confirmedBy)
            assertEquals(54_500L, done.balanceAfterMinor)
        }

    @Test
    fun `a cancelled or failed prompt fails, and a paid status beats a failed word`() =
        runTest {
            ramp.seller(ramp.crynce)
            balances()
            val cancelled = start(TopUpTarget.PAYHERO_SERVICE).data()
            val failed = start(TopUpTarget.FINOQ_PAYMENT).data()

            ramp.collections.topUpStatuses[cancelled.topUpId.toString()] = TopUpStatus.Failed("CANCELLED")
            ramp.collections.topUpStatuses[failed.topUpId.toString()] = TopUpStatus.Failed("FAILED")

            assertEquals(TopUpState.FAILED to TopUpFailure.CANCELLED, read(cancelled).let { it.state to it.failure })
            assertEquals(TopUpState.FAILED to TopUpFailure.FAILED, read(failed).let { it.state to it.failure })
        }

    @Test
    fun `an unclear send stays open and is looked up, never sent again, and closes UNKNOWN after five minutes`() =
        runTest {
            balances()
            ramp.collections.nextTopUps += TopUpResult.Unknown
            val topUp = start().data()
            assertEquals(TopUpState.REQUESTED to "unknown", topUp.state to topUp.outcome)
            ramp.collections.topUpStatuses.remove(topUp.topUpId.toString())

            assertEquals(TopUpState.REQUESTED, read(topUp).state)
            ramp.clock.advance(5 * 60)
            val unknown = read(topUp)

            assertEquals(TopUpState.UNKNOWN, unknown.state)
            assertNull(unknown.failure)
            assertEquals(1, ramp.collections.topUpCalls.size, "never sent twice")
            assertEquals(TopUpState.REQUESTED, start().data().state, "an UNKNOWN one no longer blocks the wallet")
        }

    @Test
    fun `a late paid from the provider still confirms an UNKNOWN top-up`() =
        runTest {
            balances()
            val topUp = start().data()
            ramp.clock.advance(5 * 60)
            assertEquals(TopUpState.UNKNOWN, read(topUp).state)

            ramp.collections.topUpPaid(topUp.topUpId.toString(), user)
            ramp.paymentCallback(ramp.crynce.callbackKey!!, topUp.topUpId.toString())

            assertEquals(
                TopUpState.CONFIRMED,
                ramp.topUpRows.rows
                    .getValue(topUp.topUpId)
                    .state,
            )
        }

    // ---- the doorbell and the sweep ----

    @Test
    fun `a callback only rings, the provider is asked, and a stranger's key or reference changes nothing`() =
        runTest {
            balances()
            val topUp = start().data()
            val reference = topUp.topUpId.toString()
            ramp.collections.topUpPaid(reference, user)

            ramp.paymentCallback("someone-else-key", reference)
            ramp.paymentCallback(ramp.crynce.callbackKey!!, "not-a-top-up")
            assertEquals(
                TopUpState.REQUESTED,
                ramp.topUpRows.rows
                    .getValue(topUp.topUpId)
                    .state,
            )

            val other = ramp.merchant("Other", floatMinor = 0)
            ramp.paymentCallback(other.callbackKey!!, reference)
            assertEquals(
                TopUpState.REQUESTED,
                ramp.topUpRows.rows
                    .getValue(topUp.topUpId)
                    .state,
                "not theirs",
            )

            ramp.paymentCallback(ramp.crynce.callbackKey!!, reference)
            ramp.paymentCallback(ramp.crynce.callbackKey!!, reference)
            assertEquals(
                TopUpState.CONFIRMED,
                ramp.topUpRows.rows
                    .getValue(topUp.topUpId)
                    .state,
            )
        }

    @Test
    fun `the sweep looks up open top-ups nobody is watching`() =
        runTest {
            balances()
            val topUp = start().data()
            ramp.collections.topUpPaid(topUp.topUpId.toString(), user)

            ramp.clock.advance(11)
            assertEquals(1, ramp.topUps.sweep())

            assertEquals(
                TopUpState.CONFIRMED,
                ramp.topUpRows.rows
                    .getValue(topUp.topUpId)
                    .state,
            )
            assertEquals(0, ramp.topUps.sweep())
        }

    // ---- offline ----

    @Test
    fun `offline, the PIN unlocks the account for the top-up without putting the merchant online`() =
        runTest {
            val pin = "482913"
            val merchantId = approvedOffline(pin)
            ramp.collections.serviceBalances["api-user"] = 0
            ramp.collections.payoutBalances["api-user"] = 0

            assertEquals(RampError.TopUpPinRequired, start(merchantId = merchantId).error())
            assertEquals(RampError.PinInvalid, start(merchantId = merchantId, pin = "000000").error())
            assertTrue(ramp.collections.topUpCalls.isEmpty())

            val topUp = start(merchantId = merchantId, pin = pin).data()

            assertEquals(
                PhoneNumber.parse("+254712000111"),
                ramp.collections.topUpCalls
                    .single()
                    .payer,
            )
            assertNotNull(ramp.sessions.of(merchantId), "held on this node")
            assertNull(ramp.sessions.marks[merchantId], "but not online")
            ramp.endSessions()
            assertNotNull(ramp.sessions.of(merchantId), "kept while the top-up is open")

            ramp.collections.topUpPaid(topUp.topUpId.toString(), "api-user")
            assertEquals(TopUpState.CONFIRMED, read(topUp).state)
            ramp.endSessions()
            assertNull(ramp.sessions.of(merchantId), "dropped once nothing needs it")
        }

    /** A merchant who signed up on the portal, was approved and stored their account under [pin], and is offline. */
    private suspend fun approvedOffline(pin: String): MerchantId {
        val id =
            ramp
                .verifyCodeFor("pay@offline.co.ke")
                .merchant.merchant.merchantId
        ramp.applyAsMerchant(id, TESTNET, ProfileCommand("Offline Ltd.", PhoneNumber.parse("+254712000111")!!))
        ramp.decideMerchant(id, approve = true, by = "staff")
        ramp.release()
        val account = PaymentAccountCommand(CollectionCredentials("api-user", "api-pass", "911"), pin)
        assertTrue(ramp.storePayments(id, TESTNET, account) is Resource.Success)
        return id
    }
}
