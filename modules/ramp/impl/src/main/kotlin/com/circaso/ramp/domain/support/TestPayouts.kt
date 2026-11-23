package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.MobileCollections
import com.circaso.integrations.api.PayoutLookup
import com.circaso.integrations.api.PayoutRequest
import com.circaso.integrations.api.PayoutResult
import com.circaso.integrations.api.PayoutStatus
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.BalanceRead
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.TestPayout
import com.circaso.ramp.domain.models.TestPayoutState
import com.circaso.ramp.domain.ports.PayoutJournal
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.TestPayoutRepository
import com.circaso.shared.FiatAmount
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * A merchant's test payout (decision 2026-11-23): KES 10 to 100 from their own account at the provider to **their
 * own contact phone, never any other number**, so a real payout can be tried end to end before anyone sells. It obeys
 * every rule a sale's payout does: written down before it is sent (`SENDING`), sent once by a client that never
 * retries, never sent again on an unclear answer, and only the provider's lookup says it was paid. The payout balance
 * is read before it is sent and after it is paid, so the provider's real charge can be read off the difference.
 *
 * What the integration logs (layouts, never values) is what the first real test is read from; this class logs one
 * line per step with ids and outcomes only.
 */
class TestPayouts(
    private val tests: TestPayoutRepository,
    private val merchants: MerchantRepository,
    private val sessions: TradingSessions,
    private val providers: CollectionProviders,
    private val balances: ProviderBalancesReader,
    private val fees: PayoutFees,
    private val settings: SettingsBook,
    private val clock: Clock,
    private val journal: PayoutJournal,
) {
    /** Sends a test payout of [amountMinor] to the merchant's own contact phone. */
    suspend fun start(
        merchantId: MerchantId,
        network: Network,
        amountMinor: Long,
    ): Resource<TestPayout> {
        if (amountMinor !in MIN_MINOR..MAX_MINOR || amountMinor % FiatAmount.MINOR_PER_UNIT != 0L) {
            return Resource.error(RampError.AmountInvalid)
        }
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        val phone = PhoneNumber.parse(merchant.contactPhone) ?: return incomplete()
        val accountId = merchant.payoutAccountId ?: return incomplete()
        val callbackKey = merchant.callbackKey ?: return incomplete()
        val keys = sessions.of(merchantId) ?: return Resource.error(RampError.MerchantOffline)
        val collections = providers.named(keys.provider) ?: return Resource.error(RampError.CollectionsUnavailable)
        tests
            .latest(
                merchantId,
            )?.takeIf { it.state.isOpen }
            ?.let { return Resource.error(RampError.TestPayoutInProgress) }

        val current = settings.of(network)
        val before = balances.read(merchant)
        val now = clock.instant()
        val test =
            TestPayout(
                testId = Uuid7.generate(now),
                merchantId = merchantId,
                provider = keys.provider,
                amountMinor = amountMinor,
                payee = phone,
                state = TestPayoutState.SENDING,
                outcome = null,
                providerReference = null,
                secondaryReference = null,
                receipt = null,
                paidAmountMinor = null,
                providerFee = fees.of(current.copy(collectionsProvider = keys.provider), amountMinor),
                balanceBefore = before?.finoqPayment?.let { BalanceRead(it.minor, it.source) },
                balanceAfter = null,
                createdAt = now,
                updatedAt = now,
            )
        // Written before it is sent, under the one-in-flight key: from here on the row says a payout may be out.
        if (!tests.insert(test)) return Resource.error(RampError.TestPayoutInProgress)
        val request =
            PayoutRequest(
                payee = phone,
                amount = FiatAmount(amountMinor, RampSettings.FIAT),
                reference = test.testId.toString(),
                callbackUrl = Callbacks.url(current.callbackBaseUrl, callbackKey),
                accountId = accountId,
            )
        val result = send(collections, keys.credentials, request)
        journal.step("test payout sent", "${test.testId} merchant=$merchantId", said(result))
        return Resource.success(sent(test, result))
    }

    /** The merchant's test ([testId], or their latest), asking the provider first while it is still in flight. */
    suspend fun view(merchantId: MerchantId, testId: UUID?): Resource<TestPayout> {
        val test =
            (if (testId == null) tests.latest(merchantId) else tests.find(testId))
                ?.takeIf { it.merchantId == merchantId }
                ?: return Resource.error(RampError.TestPayoutNotFound)
        if (!test.state.isOpen || test.updatedAt.plus(ASK_EVERY).isAfter(clock.instant())) return Resource.success(test)
        return Resource.success(lookUp(test))
    }

    /** The provider's callback named [reference]: if it is this merchant's test in flight, it is looked up. */
    suspend fun rung(merchantId: MerchantId, reference: String) {
        val testId = runCatching { UUID.fromString(reference.trim()) }.getOrNull() ?: return
        val test = tests.find(testId)?.takeIf { it.merchantId == merchantId && it.state.isOpen } ?: return
        lookUp(test)
    }

    /** Tests still in flight, looked up (the job, for a callback that never came). */
    suspend fun sweep(): Int {
        val due = tests.open(clock.instant().minus(ASK_EVERY), LIMIT)
        due.forEach { lookUp(it) }
        return due.size
    }

    /**
     * Asks the provider what became of [test] and records it. A test still `SENDING` is asked about only once its call
     * can't still be running. Without the merchant's keys here, nothing changes.
     */
    suspend fun lookUp(test: TestPayout): TestPayout {
        if (!test.state.isOpen) return test
        val now = clock.instant()
        if (test.state == TestPayoutState.SENDING && test.updatedAt.plus(SalePayouts.SENDING_STALE).isAfter(now)) {
            return test
        }
        val keys = sessions.of(test.merchantId)?.takeIf { it.provider == test.provider } ?: return test
        val collections = providers.named(test.provider) ?: return test
        val lookup = PayoutLookup(test.testId.toString(), test.providerReference)
        val status =
            try {
                collections.lookUpPayout(keys.credentials, lookup)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PayoutStatus.Unknown
            }
        journal.step("test payout looked up", test.testId.toString(), SalePayouts.said(status))
        val after =
            when (status) {
                is PayoutStatus.Paid -> {
                    paid(test, status)
                }

                is PayoutStatus.Failed -> {
                    test.copy(state = TestPayoutState.FAILED, outcome = "failed:${status.reason}")
                }

                is PayoutStatus.NotFound -> {
                    test.copy(
                        state = TestPayoutState.NOT_FOUND,
                        outcome = SalePayouts.said(status),
                    )
                }

                PayoutStatus.Pending, PayoutStatus.Unknown -> {
                    test.copy(
                        outcome = "looked_up:${SalePayouts.said(status)}",
                    )
                }
            }
        return record(test, after.copy(updatedAt = now))
    }

    private suspend fun paid(test: TestPayout, status: PayoutStatus.Paid): TestPayout {
        val after = merchants.find(test.merchantId)?.let { balances.read(it) }
        return test.copy(
            state = TestPayoutState.PAID,
            outcome = "paid",
            receipt = status.providerReceipt,
            paidAmountMinor = status.amount?.amountMinor,
            balanceAfter = after?.finoqPayment?.let { BalanceRead(it.minor, it.source) },
        )
    }

    private suspend fun sent(test: TestPayout, result: PayoutResult): TestPayout {
        val now = clock.instant()
        val after =
            when (result) {
                is PayoutResult.Accepted -> {
                    test.copy(
                        state = TestPayoutState.ACCEPTED,
                        outcome = "accepted",
                        providerReference = result.receipt.providerReference,
                        secondaryReference = result.receipt.secondaryReference,
                    )
                }

                PayoutResult.Unknown -> {
                    test.copy(state = TestPayoutState.UNKNOWN, outcome = "unknown")
                }

                is PayoutResult.Rejected -> {
                    val cost = result.cost?.let { ":cost=${it.amountMinor}" }.orEmpty()
                    test.copy(state = TestPayoutState.NOT_SENT, outcome = "rejected:${result.reason.name}$cost")
                }

                PayoutResult.InsufficientBalance -> {
                    test.copy(state = TestPayoutState.NOT_SENT, outcome = "insufficient_balance")
                }

                PayoutResult.Unavailable -> {
                    test.copy(state = TestPayoutState.NOT_SENT, outcome = "unavailable")
                }
            }
        val recorded = record(test, after.copy(updatedAt = now))
        // An unclear answer is looked up at once (by our reference), never sent again.
        return if (recorded.state == TestPayoutState.UNKNOWN) lookUp(recorded) else recorded
    }

    private suspend fun record(before: TestPayout, after: TestPayout): TestPayout =
        if (tests.move(after, setOf(before.state))) after else tests.find(before.testId) ?: before

    private fun said(result: PayoutResult): String = result::class.simpleName.orEmpty()

    /** A call that was cancelled or threw is `Unknown`: the request may have left. */
    private suspend fun send(
        collections: MobileCollections,
        keys: CollectionCredentials,
        request: PayoutRequest,
    ): PayoutResult =
        try {
            collections.sendPayout(keys, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            journal.step("test payout call failed", request.reference, e::class.simpleName.orEmpty())
            PayoutResult.Unknown
        }

    private fun <T> incomplete(): Resource<T> = Resource.error(RampError.MerchantSetupIncomplete)

    companion object {
        /** KES 10 to KES 100, whole shillings. */
        const val MIN_MINOR = 1_000L
        const val MAX_MINOR = 10_000L
        private const val LIMIT = 50
        private val ASK_EVERY: Duration = Duration.ofSeconds(3)
    }
}
