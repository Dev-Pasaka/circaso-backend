package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.BalanceReading
import com.circaso.integrations.api.CollectionBalances
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionNotifications
import com.circaso.integrations.api.CollectionReceipt
import com.circaso.integrations.api.CollectionRequest
import com.circaso.integrations.api.CollectionState
import com.circaso.integrations.api.CollectionStatus
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MobileCollections
import com.circaso.integrations.api.PayoutLookup
import com.circaso.integrations.api.PayoutReceipt
import com.circaso.integrations.api.PayoutReference
import com.circaso.integrations.api.PayoutRejection
import com.circaso.integrations.api.PayoutRequest
import com.circaso.integrations.api.PayoutResult
import com.circaso.integrations.api.PayoutStatus
import com.circaso.integrations.api.ProviderBalances
import com.circaso.integrations.api.TopUpLookup
import com.circaso.integrations.api.TopUpReceipt
import com.circaso.integrations.api.TopUpRequest
import com.circaso.integrations.api.TopUpResult
import com.circaso.integrations.api.TopUpStatus
import com.circaso.integrations.api.TopUpWallet
import com.circaso.shared.FiatAmount
import com.circaso.shared.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests: an in-memory collections provider that records every prompt. A prompt is `QUEUED` until the test says what
 * the payer did ([pay], [fail]); [statuses] may be set directly to answer anything at all (a wrong amount, another
 * payer). While [failure] is set, every call fails with it. [feeBalanceMinor] is the service balance [balance]
 * reports.
 *
 * **Payouts** are scripted the same way. [nextPayout] says what the next [sendPayout] does (see [FakePayout]); a
 * payout the provider took is `Pending` until the test says what became of it ([completePayout], [failPayout]);
 * [ringPayout] rings a listener's doorbell as the provider's callback would, as often as asked (a duplicate callback
 * is `times = 2`). Every payout that reached the provider is in [payouts], so a test can prove one was sent once.
 *
 * **Top-ups** (2026-11-23): [nextTopUp] scripts the next [requestTopUp] (accepted when unset); a prompt that went out is
 * `Pending` until [completeTopUp] (which credits the wallet it was for) or [failTopUp].
 */
class FakeCollections : MobileCollections {
    val prompts: MutableList<CollectionRequest> = CopyOnWriteArrayList()
    val statuses = ConcurrentHashMap<String, CollectionStatus>()
    val statusQueries: MutableList<String> = CopyOnWriteArrayList()

    /** The payouts that reached the provider, including one whose answer was lost ([FakePayout.UNKNOWN_SENT]). */
    val payouts: MutableList<PayoutRequest> = CopyOnWriteArrayList()
    val payoutLookups: MutableList<PayoutLookup> = CopyOnWriteArrayList()

    /** What the provider says of each payout, by its own reference; set directly to answer anything at all. */
    val payoutStatuses = ConcurrentHashMap<String, PayoutStatus>()

    @Volatile
    var failure: IntegrationError? = null

    @Volatile
    var feeBalanceMinor: Long = DEFAULT_FEE_BALANCE_MINOR

    /** What payouts can spend; null is a provider whose payout balance can't be read. */
    @Volatile
    var payoutBalanceMinor: Long? = DEFAULT_PAYOUT_BALANCE_MINOR

    @Volatile
    var nextPayout: FakePayout = FakePayout.ACCEPT

    /** The top-up prompts that reached the provider, and what it says of each, by our reference. */
    val topUps: MutableList<TopUpRequest> = CopyOnWriteArrayList()
    val topUpStatuses = ConcurrentHashMap<String, TopUpStatus>()

    /** What the next [requestTopUp] answers; unset, the prompt goes out. */
    @Volatile
    var nextTopUp: TopUpResult? = null

    /** The payer approved the top-up [reference]: reported paid, and the wallet it was for credited. */
    fun completeTopUp(reference: String, receipt: String = "QT${reference.takeLast(8).uppercase()}") {
        val topUp = topUps.first { it.reference == reference }
        topUpStatuses[reference] = TopUpStatus.Paid(receipt, topUp.amount)
        when (topUp.wallet) {
            TopUpWallet.SERVICE -> feeBalanceMinor += topUp.amount.amountMinor
            TopUpWallet.PAYMENT -> payoutBalanceMinor = payoutBalanceMinor?.plus(topUp.amount.amountMinor)
        }
    }

    /** The payer cancelled the top-up [reference], or its prompt timed out. */
    fun failTopUp(reference: String, reason: String = "CANCELLED") {
        topUpStatuses[reference] = TopUpStatus.Failed(reason)
    }

    /** The provider's reference for the prompt we named [reference]. */
    fun referenceOf(reference: String): String = "PH-${prompts.indexOfFirst { it.reference == reference } + 1}"

    /** The provider's reference for the (first) payout we named [reference]. */
    fun payoutReferenceOf(reference: String): String = "PO-${payouts.indexOfFirst { it.reference == reference } + 1}"

    /** The payer approved the prompt for [reference]: the provider reports it paid, as asked. */
    fun pay(reference: String, receipt: String = "QK${reference.takeLast(8).uppercase()}") {
        val prompt = prompts.first { it.reference == reference }
        statuses[referenceOf(reference)] =
            CollectionStatus(CollectionState.SUCCESS, prompt.amount, prompt.payer, prompt.reference, receipt)
    }

    /** The payer cancelled, or the prompt timed out on their phone. */
    fun fail(reference: String) {
        statuses[referenceOf(reference)] = CollectionStatus(CollectionState.FAILED, null, null, reference, null)
    }

    /** The money reached the payee: the provider reports the payout for [reference] paid, as asked. */
    fun completePayout(reference: String, receipt: String = "QP${reference.takeLast(8).uppercase()}") {
        val payout = payouts.first { it.reference == reference }
        payoutStatuses[payoutReferenceOf(reference)] = PayoutStatus.Paid(receipt, payout.amount, payout.reference)
    }

    /** The provider could not pay [reference] and gave up; the amount goes back to the payout balance. */
    fun failPayout(reference: String, reason: String = "FAILED") {
        val payout = payouts.first { it.reference == reference }
        payoutStatuses[payoutReferenceOf(reference)] = PayoutStatus.Failed(reason)
        payoutBalanceMinor = payoutBalanceMinor?.plus(payout.amount.amountMinor)
    }

    /** The provider's callback for the payout [reference], [times] over, on the address that ends in [callbackKey]. */
    suspend fun ringPayout(
        listener: CollectionNotifications,
        callbackKey: String,
        reference: String,
        times: Int = 1,
    ): List<Resource<Unit>> = List(times) { listener.onPayoutCallback(callbackKey, reference) }

    override suspend fun requestPayment(
        credentials: CollectionCredentials,
        request: CollectionRequest,
    ): Resource<CollectionReceipt> {
        failure?.let { return Resource.error(it) }
        prompts += request
        val providerReference = "PH-${prompts.size}"
        statuses[providerReference] = CollectionStatus(CollectionState.QUEUED, null, null, request.reference, null)
        return Resource.success(CollectionReceipt(providerReference, "ws_CO_${prompts.size}"))
    }

    override suspend fun paymentStatus(
        credentials: CollectionCredentials,
        providerReference: String,
    ): Resource<CollectionStatus> {
        failure?.let { return Resource.error(it) }
        statusQueries += providerReference
        return statuses[providerReference]?.let { Resource.success(it) }
            ?: Resource.error(IntegrationError.Rejected("No such payment."))
    }

    override suspend fun sendPayout(credentials: CollectionCredentials, request: PayoutRequest): PayoutResult =
        when (nextPayout) {
            FakePayout.UNAVAILABLE -> PayoutResult.Unavailable
            FakePayout.REJECT -> PayoutResult.Rejected(PayoutRejection.REFUSED)
            FakePayout.INSUFFICIENT_BALANCE -> PayoutResult.InsufficientBalance
            FakePayout.UNKNOWN_NOT_SENT -> PayoutResult.Unknown
            FakePayout.UNKNOWN_SENT -> take(request)?.let { PayoutResult.Unknown } ?: PayoutResult.InsufficientBalance
            FakePayout.ACCEPT -> take(request)?.let { PayoutResult.Accepted(it) } ?: PayoutResult.InsufficientBalance
        }

    /** The provider takes the payout out of the payout balance, or null when that can't cover it. */
    private fun take(request: PayoutRequest): PayoutReceipt? {
        val balance = payoutBalanceMinor
        if (balance != null && balance < request.amount.amountMinor) return null
        payoutBalanceMinor = balance?.minus(request.amount.amountMinor)
        payouts += request
        val providerReference = "PO-${payouts.size}"
        payoutStatuses[providerReference] = PayoutStatus.Pending
        return PayoutReceipt(providerReference, "conv-${payouts.size}")
    }

    /** By the provider's reference when the caller has it, else by ours, as a real lookup after `Unknown` must. */
    override suspend fun lookUpPayout(credentials: CollectionCredentials, lookup: PayoutLookup): PayoutStatus {
        if (failure != null) return PayoutStatus.Unknown
        payoutLookups += lookup
        val by = if (lookup.providerReference != null) PayoutReference.PROVIDER else PayoutReference.OURS
        val providerReference =
            lookup.providerReference
                ?: payoutReferenceOf(lookup.reference).takeIf { payouts.any { it.reference == lookup.reference } }
        return providerReference?.let { payoutStatuses[it] } ?: PayoutStatus.NotFound(by)
    }

    override suspend fun requestTopUp(credentials: CollectionCredentials, request: TopUpRequest): TopUpResult {
        if (failure != null) return TopUpResult.Unavailable
        val scripted = nextTopUp.also { nextTopUp = null }
        if (scripted != null && scripted !is TopUpResult.Accepted && scripted != TopUpResult.Unknown) return scripted
        topUps += request
        topUpStatuses[request.reference] = TopUpStatus.Pending
        return scripted ?: TopUpResult.Accepted(TopUpReceipt("TU-${topUps.size}", "ws_CO_TU_${topUps.size}"))
    }

    override suspend fun lookUpTopUp(credentials: CollectionCredentials, lookup: TopUpLookup): TopUpStatus {
        if (failure != null) return TopUpStatus.Unknown
        return topUpStatuses[lookup.reference] ?: TopUpStatus.NotFound
    }

    override suspend fun balances(credentials: CollectionCredentials, accountId: String?): Resource<ProviderBalances> {
        failure?.let { return Resource.error(it) }
        return Resource.success(
            ProviderBalances(
                payout = payoutBalanceMinor?.let { BalanceReading(FiatAmount(it, KES), SOURCE) },
                fees = BalanceReading(FiatAmount(feeBalanceMinor, KES), SOURCE),
            ),
        )
    }

    override suspend fun balance(credentials: CollectionCredentials): Resource<CollectionBalances> =
        Resource.success(CollectionBalances(FiatAmount(0, KES), FiatAmount(feeBalanceMinor, KES)))

    override suspend fun verify(credentials: CollectionCredentials): Resource<Unit> {
        failure?.let { return Resource.error(it) }
        return Resource.success(Unit)
    }

    private companion object {
        const val KES = "KES"
        const val SOURCE = "fake"

        /** KES 1 000: above any floor a network is likely to set, so a local merchant can always go online. */
        const val DEFAULT_FEE_BALANCE_MINOR = 100_000L

        /** KES 100 000: enough for a developer's off-ramps. */
        const val DEFAULT_PAYOUT_BALANCE_MINOR = 10_000_000L
    }
}

/** What [FakeCollections.sendPayout] does next. */
enum class FakePayout {
    /** The provider takes it (unless the payout balance can't cover it): `Accepted`, then `Pending`. */
    ACCEPT,

    /** Refused for good; nothing sent. */
    REJECT,

    /** Refused for the merchant's balance, whatever [FakeCollections.payoutBalanceMinor] says. */
    INSUFFICIENT_BALANCE,

    /** The answer was lost but the provider took it: `Unknown`, and a lookup by our reference finds it. */
    UNKNOWN_SENT,

    /** The answer was lost and so was the request: `Unknown`, and a lookup finds nothing. */
    UNKNOWN_NOT_SENT,

    /** The provider was never reached; nothing sent. */
    UNAVAILABLE,
}

/**
 * Local runs: no prompt reaches a phone and no shilling moves. Every prompt is reported paid, exactly as asked, the
 * next time its status is read, so an on-ramp completes on a developer's machine. A **payout** is accepted, stays
 * pending for [payoutDelay], is then reported paid as asked, and rings the payout doorbell on the callback address it
 * was sent with (when something listens), so an off-ramp completes too without anyone polling. It prints that it was
 * asked (never a number or an amount).
 */
class LogCollections(
    private val delegate: FakeCollections = FakeCollections(),
    private val payoutDelay: Duration = DEFAULT_PAYOUT_DELAY,
    private val notifications: () -> CollectionNotifications? = { null },
) : MobileCollections by delegate {
    private val log = LoggerFactory.getLogger(LogCollections::class.java)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun requestPayment(
        credentials: CollectionCredentials,
        request: CollectionRequest,
    ): Resource<CollectionReceipt> =
        delegate.requestPayment(credentials, request).also {
            delegate.pay(request.reference)
            log.info("collections (log): payment prompt for reference={}, reported as paid", request.reference)
        }

    override suspend fun sendPayout(credentials: CollectionCredentials, request: PayoutRequest): PayoutResult =
        delegate.sendPayout(credentials, request).also { result ->
            log.info("collections (log): payout for reference={}: {}", request.reference, result::class.simpleName)
            if (result is PayoutResult.Accepted) background.launch { settle(request) }
        }

    override suspend fun requestTopUp(credentials: CollectionCredentials, request: TopUpRequest): TopUpResult =
        delegate.requestTopUp(credentials, request).also { result ->
            log.info("collections (log): top-up for reference={}: {}", request.reference, result::class.simpleName)
            if (result is TopUpResult.Accepted) background.launch { settleTopUp(request) }
        }

    /** The pretend payer approves the prompt after [payoutDelay]; the payment doorbell rings when there is an address. */
    private suspend fun settleTopUp(request: TopUpRequest) {
        delay(payoutDelay.toMillis())
        delegate.completeTopUp(request.reference)
        log.info("collections (log): top-up for reference={} reported as paid", request.reference)
        val callbackUrl = request.callbackUrl ?: return
        try {
            notifications()?.onPaymentCallback(keyOf(callbackUrl), request.reference)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("collections (log): the top-up doorbell failed: error={}", e::class.simpleName)
        }
    }

    private fun keyOf(callbackUrl: String): String =
        callbackUrl
            .substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')

    private suspend fun settle(request: PayoutRequest) {
        delay(payoutDelay.toMillis())
        delegate.completePayout(request.reference)
        log.info("collections (log): payout for reference={} reported as paid", request.reference)
        try {
            notifications()?.onPayoutCallback(keyOf(request.callbackUrl), request.reference)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("collections (log): the payout doorbell failed: error={}", e::class.simpleName)
        }
    }

    private companion object {
        val DEFAULT_PAYOUT_DELAY: Duration = Duration.ofSeconds(3)
    }
}
