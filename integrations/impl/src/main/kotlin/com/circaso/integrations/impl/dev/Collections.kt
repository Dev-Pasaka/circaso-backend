package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.CollectionBalances
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionReceipt
import com.circaso.integrations.api.CollectionRequest
import com.circaso.integrations.api.CollectionState
import com.circaso.integrations.api.CollectionStatus
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MobileCollections
import com.circaso.integrations.api.PayoutReceipt
import com.circaso.integrations.api.PayoutRequest
import com.circaso.shared.FiatAmount
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests: an in-memory collections provider that records every prompt. A prompt is `QUEUED` until the test says what
 * the payer did ([pay], [fail]); [statuses] may be set directly to answer anything at all (a wrong amount, another
 * payer). While [failure] is set, every call fails with it. [feeBalanceMinor] is the service balance [balance]
 * reports.
 */
class FakeCollections : MobileCollections {
    val prompts: MutableList<CollectionRequest> = CopyOnWriteArrayList()
    val statuses = ConcurrentHashMap<String, CollectionStatus>()
    val statusQueries: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    var failure: IntegrationError? = null

    @Volatile
    var feeBalanceMinor: Long = DEFAULT_FEE_BALANCE_MINOR

    /** The provider's reference for the prompt we named [reference]. */
    fun referenceOf(reference: String): String = "PH-${prompts.indexOfFirst { it.reference == reference } + 1}"

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

    override suspend fun payout(credentials: CollectionCredentials, request: PayoutRequest): Resource<PayoutReceipt> =
        Resource.error(IntegrationError.NotImplemented("MobileCollections payouts", "fake"))

    override suspend fun payoutStatus(
        credentials: CollectionCredentials,
        providerReference: String,
    ): Resource<CollectionStatus> = Resource.error(IntegrationError.NotImplemented("MobileCollections payouts", "fake"))

    override suspend fun balance(credentials: CollectionCredentials): Resource<CollectionBalances> =
        Resource.success(CollectionBalances(FiatAmount(0, "KES"), FiatAmount(feeBalanceMinor, "KES")))

    override suspend fun verify(credentials: CollectionCredentials): Resource<Unit> {
        failure?.let { return Resource.error(it) }
        return Resource.success(Unit)
    }

    private companion object {
        /** KES 1 000: above any floor a network is likely to set, so a local merchant can always go online. */
        const val DEFAULT_FEE_BALANCE_MINOR = 100_000L
    }
}

/**
 * Local runs: no prompt reaches a phone and no shilling moves. Every prompt is reported paid, exactly as asked, the
 * next time its status is read, so an on-ramp completes on a developer's machine. It prints that it was asked (never
 * a number or an amount).
 */
class LogCollections(private val delegate: FakeCollections = FakeCollections()) : MobileCollections by delegate {
    private val log = LoggerFactory.getLogger(LogCollections::class.java)

    override suspend fun requestPayment(
        credentials: CollectionCredentials,
        request: CollectionRequest,
    ): Resource<CollectionReceipt> =
        delegate.requestPayment(credentials, request).also {
            delegate.pay(request.reference)
            log.info("collections (log): payment prompt for reference={}, reported as paid", request.reference)
        }
}
