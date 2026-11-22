package com.circaso.integrations.api

import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource

/**
 * Mobile-money collections and payouts (M-Pesa through PayHero first; decision 2026-11-22). **Credentials are passed
 * per call**: each ramp merchant brings their own account at the provider, so this is the one contract whose vendor
 * keys don't come from `Secrets`. Nothing here is a provider's shape, so another provider is a new implementation.
 */
interface MobileCollections {
    /** Sends the payment prompt (M-Pesa STK push) to [CollectionRequest.payer]. Never retried by the client. */
    suspend fun requestPayment(
        credentials: CollectionCredentials,
        request: CollectionRequest,
    ): Resource<CollectionReceipt>

    /** What the provider itself says about a payment: the only thing a release may rest on. */
    suspend fun paymentStatus(credentials: CollectionCredentials, providerReference: String): Resource<CollectionStatus>

    /** Pays out to a phone (off-ramp). Never retried by the client. No provider implements it yet. */
    suspend fun payout(credentials: CollectionCredentials, request: PayoutRequest): Resource<PayoutReceipt>

    suspend fun payoutStatus(credentials: CollectionCredentials, providerReference: String): Resource<CollectionStatus>

    /** The merchant's balances at the provider: what payouts can spend, and what the provider's fees are paid from. */
    suspend fun balance(credentials: CollectionCredentials): Resource<CollectionBalances>

    /** Whether the credentials work, for the portal's "connect your account" step. */
    suspend fun verify(credentials: CollectionCredentials): Resource<Unit>
}

/**
 * Every collections provider this server offers, by name (decision 2026-11-22: which one the ramp uses is not a
 * start-up line but a setting staff switch in the admin, per network, at any time). Integrations binds them all at
 * once; the ramp names the one its settings say on each call, so a switch needs no restart and integrations never
 * depends on the ramp.
 *
 * Real providers (`payhero`) are always offered. `log`, which reports every prompt as paid, is offered only where
 * the server runs in the local environment: anywhere else it would release USDC for nothing.
 */
interface CollectionProviders {
    /** The names offered here, real providers first. */
    val names: List<String>

    /** The provider called [name], or null when this server doesn't offer it. */
    fun named(name: String): MobileCollections?

    companion object {
        const val PAYHERO = "payhero"
        const val LOG = "log"
    }
}

/** A merchant's account at the provider. The string form hides everything: it never reaches a log line. */
class CollectionCredentials(
    val username: String,
    val password: String,
    /** The merchant's own till or paybill, as the provider names it. */
    val collectionChannelId: String,
) {
    override fun toString(): String = "CollectionCredentials(***)"
}

data class CollectionRequest(
    val payer: PhoneNumber,
    val amount: FiatAmount,
    /** Ours (the ramp's order id), echoed back in the callback and the status. */
    val reference: String,
    val callbackUrl: String,
)

data class CollectionReceipt(val providerReference: String, val checkoutId: String)

enum class CollectionState { QUEUED, SUCCESS, FAILED }

/** A payment as the provider reports it. A null field is one the provider didn't give. */
data class CollectionStatus(
    val state: CollectionState,
    val amount: FiatAmount?,
    val payer: PhoneNumber?,
    /** Ours, as the provider echoes it. */
    val reference: String?,
    /** The mobile-money receipt number (M-Pesa's code). */
    val receipt: String?,
) {
    override fun toString(): String = "CollectionStatus($state, ***)"
}

data class PayoutRequest(
    val payee: PhoneNumber,
    val amount: FiatAmount,
    val reference: String,
    val callbackUrl: String,
)

data class PayoutReceipt(val providerReference: String)

data class CollectionBalances(val payout: FiatAmount, val fees: FiatAmount)

/** Implemented by the ramp, wired in `app`; integrations never depend on the ramp. */
interface CollectionNotifications {
    /**
     * The provider called back, on the callback address that ends in [callbackKey], about the payment we named
     * [reference]. Only a doorbell: a callback that carries no signature decides nothing, and the listener asks the
     * provider itself before it acts. The listener ignores a key it never gave out, and a reference that isn't a
     * payment of the merchant the key belongs to.
     */
    suspend fun onPaymentCallback(callbackKey: String, reference: String): Resource<Unit>
}
