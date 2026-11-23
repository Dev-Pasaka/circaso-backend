package com.circaso.integrations.api

import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource

/**
 * Mobile-money collections and payouts (M-Pesa through PayHero, now FinoQ, first; decision 2026-11-22; payouts
 * 2026-11-23). **Credentials are passed per call**: each ramp merchant brings their own account at the provider, so
 * this is the one contract whose vendor keys don't come from `Secrets`. Nothing here is a provider's shape, so another
 * provider is a new implementation.
 */
interface MobileCollections {
    /**
     * Sends the payment prompt (M-Pesa STK push) to [CollectionRequest.payer]. Never retried by the client.
     *
     * The failures are told apart, because the caller acts differently on each: `Unavailable` is the provider out of
     * reach (the outcome is unknown; nothing else is tried), `Rejected` is the provider refusing **this merchant's**
     * request (nothing was sent; another merchant may be asked), and `ServiceBalanceLow` is that refusal when the
     * reason is the merchant's own prepaid balance at the provider (they must top it up first).
     */
    suspend fun requestPayment(
        credentials: CollectionCredentials,
        request: CollectionRequest,
    ): Resource<CollectionReceipt>

    /** What the provider itself says about a payment: the only thing a release may rest on. */
    suspend fun paymentStatus(credentials: CollectionCredentials, providerReference: String): Resource<CollectionStatus>

    /**
     * Pays [PayoutRequest.payee] from the merchant's balance at the provider (off-ramp). **Sent once**: the client
     * never repeats it, and neither may the caller until [lookUpPayout] has said the first one did not go out, because
     * no provider here promises that a repeated reference is paid once.
     *
     * The answer is not a `Resource`: each case asks something different of the caller, so every one must be handled.
     * See [PayoutResult]. `Accepted` is never "paid": only [lookUpPayout] says that.
     */
    suspend fun sendPayout(credentials: CollectionCredentials, request: PayoutRequest): PayoutResult =
        PayoutResult.Rejected(PayoutRejection.NOT_SUPPORTED)

    /**
     * What the provider itself says became of a payout: **the only thing an off-ramp may be completed, retried or
     * refunded on**. A callback is a doorbell that leads here; [PayoutResult.Accepted] and [PayoutResult.Unknown] both
     * lead here. See [PayoutStatus] for what each answer allows.
     */
    suspend fun lookUpPayout(credentials: CollectionCredentials, lookup: PayoutLookup): PayoutStatus =
        PayoutStatus.Unknown

    /**
     * The merchant's balances at the provider, each with where it was read: what payouts can spend
     * ([ProviderBalances.payout]) and what the provider's fees are taken from ([ProviderBalances.fees]). **A balance
     * that could not be read is null, never zero.** [accountId] is the merchant's account at the provider
     * ([PayoutRequest.accountId]); without it only the calls that need no account are made.
     *
     * An error only when nothing could be asked at all (credentials refused, provider out of reach).
     */
    suspend fun balances(credentials: CollectionCredentials, accountId: String? = null): Resource<ProviderBalances> =
        when (val read = balance(credentials)) {
            is Resource.Error -> read
            is Resource.Success -> Resource.success(ProviderBalances(null, BalanceReading(read.data.fees, "balance")))
        }

    /**
     * Superseded by [sendPayout], which tells "may have been sent" from "nothing was sent"; kept until the ramp has
     * moved over. No provider implements it.
     */
    suspend fun payout(credentials: CollectionCredentials, request: PayoutRequest): Resource<PayoutReceipt> =
        Resource.error(IntegrationError.NotImplemented("MobileCollections.payout", "use sendPayout"))

    /** Superseded by [lookUpPayout]; kept until the ramp has moved over. No provider implements it. */
    suspend fun payoutStatus(
        credentials: CollectionCredentials,
        providerReference: String,
    ): Resource<CollectionStatus> =
        Resource.error(IntegrationError.NotImplemented("MobileCollections.payoutStatus", "use lookUpPayout"))

    /**
     * The fee balance alone ([CollectionBalances.fees]: PayHero's service wallet), as the on-ramp's "go online" check
     * reads it. [CollectionBalances.payout] is **not** a reading here (it is zero whatever the merchant holds): the
     * off-ramp reads [balances].
     */
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
    /** Ours (the ramp's order id), echoed back in the callback and, where the provider gives it, the status. */
    val reference: String,
    val callbackUrl: String,
    /**
     * The merchant's account at the provider, as the provider numbers it (PayHero/FinoQ: the account or "team" id, an
     * integer shown on its dashboard). It says whose balance pays. Not a secret, but the merchant's own.
     */
    val accountId: String,
) {
    override fun toString(): String = "PayoutRequest($reference, ***)"
}

/**
 * What the provider handed back for a payout it took. [providerReference] is the key [MobileCollections.lookUpPayout]
 * asks with; [secondaryReference] is any other id the provider gave (kept for support, and for a provider whose lookup
 * turns out to want it).
 */
data class PayoutReceipt(val providerReference: String, val secondaryReference: String? = null)

/** What became of sending a payout. Exactly one of these, and each one tells the caller what it may do next. */
sealed interface PayoutResult {
    /** The provider took the request and queued it. Not paid yet: ask [MobileCollections.lookUpPayout]. */
    data class Accepted(val receipt: PayoutReceipt) : PayoutResult

    /**
     * The provider (or this client, before calling it) refused the request for good: **nothing was sent**, and the
     * same request will be refused again. [cost] is the provider's own charge when it named one in the refusal
     * ([PayoutRejection.AMOUNT_BELOW_COST]).
     */
    data class Rejected(val reason: PayoutRejection, val cost: FiatAmount? = null) : PayoutResult

    /** Refused because the merchant's balance at the provider can't cover it: nothing was sent; they must top up. */
    data object InsufficientBalance : PayoutResult

    /**
     * The request left and no usable answer came back (a timeout, a broken connection, a server error, an answer that
     * names no payout). **It may have been sent.** The caller must ask [MobileCollections.lookUpPayout] before
     * anything else, and must not send again or refund on this alone.
     */
    data object Unknown : PayoutResult

    /**
     * The provider could not be reached at all, or turned the call away before reading it (a rate limit): the request
     * was never taken, so **nothing was sent** and sending again later is safe. Only answered when that is certain;
     * anything less is [Unknown].
     */
    data object Unavailable : PayoutResult
}

/** Why a payout was refused for good, in our words: a provider's wording never crosses the contract. */
enum class PayoutRejection {
    /** The request itself is wrong (a field the provider won't take, an amount with cents, no account id). */
    INVALID_REQUEST,

    /** The amount is too small to carry the provider's own charge. */
    AMOUNT_BELOW_COST,

    /** The provider doesn't accept the merchant's username and password. */
    CREDENTIALS_REFUSED,

    /** This provider pays nothing out. */
    NOT_SUPPORTED,

    /** Refused for a reason this client doesn't tell apart. */
    REFUSED,
}

/**
 * Which payout to ask about: by the provider's reference when we have it ([PayoutResult.Accepted]), else by ours alone
 * (after [PayoutResult.Unknown], when the provider's reference never arrived).
 */
data class PayoutLookup(val reference: String, val providerReference: String? = null)

/** What the provider says became of a payout. */
sealed interface PayoutStatus {
    /** Known to the provider and not finished. Ask again later. */
    data object Pending : PayoutStatus

    /**
     * The provider says the money was paid. A null field is one it didn't give; where [amount] or [reference] (ours,
     * as the provider echoes it) is given, the caller checks it against its own order before completing.
     */
    data class Paid(val providerReceipt: String?, val amount: FiatAmount?, val reference: String? = null) :
        PayoutStatus {
        override fun toString(): String = "Paid(***)"
    }

    /** The provider says it did not pay and will not. [reason] is its status word (`FAILED`, `CANCELLED`). */
    data class Failed(val reason: String) : PayoutStatus

    /**
     * The provider answered that it knows no such payout. Asked [by] [PayoutReference.PROVIDER]'s reference, that is
     * the provider denying its own receipt. Asked [by] [PayoutReference.OURS] alone it is weaker: a provider whose
     * lookup doesn't search by our reference says the same of a payout it did send, so the caller holds the order for
     * staff instead of sending again, until that provider's lookup is known to search by ours.
     */
    data class NotFound(val by: PayoutReference) : PayoutStatus

    /** No usable answer (out of reach, an error, a status word this client doesn't know). Nothing may rest on it. */
    data object Unknown : PayoutStatus
}

enum class PayoutReference { PROVIDER, OURS }

/** The on-ramp's reading ([MobileCollections.balance]): only [fees] is real. */
data class CollectionBalances(val payout: FiatAmount, val fees: FiatAmount)

/** The merchant's balances at the provider. Null is "could not be read", which is never the same as zero. */
data class ProviderBalances(val payout: BalanceReading?, val fees: BalanceReading?)

/** One balance and the call that gave it ([source]: a short name for logs and for staff, never a URL). */
data class BalanceReading(val amount: FiatAmount, val source: String)

/** Implemented by the ramp, wired in `app`; integrations never depend on the ramp. */
interface CollectionNotifications {
    /**
     * The provider called back, on the callback address that ends in [callbackKey], about the payment we named
     * [reference]. Only a doorbell: a callback that carries no signature decides nothing, and the listener asks the
     * provider itself before it acts. The listener ignores a key it never gave out, and a reference that isn't a
     * payment of the merchant the key belongs to.
     */
    suspend fun onPaymentCallback(callbackKey: String, reference: String): Resource<Unit>

    /**
     * The same doorbell for a payout we named [reference]: the listener checks the key and the reference are its own,
     * then asks [MobileCollections.lookUpPayout]. It may ring more than once for one payout, and it may ring for a
     * reference that is a payment (a provider whose callback doesn't say which it is rings both bells).
     */
    suspend fun onPayoutCallback(callbackKey: String, reference: String): Resource<Unit> =
        Resource.error(IntegrationError.NotImplemented("Payout callbacks", "this listener"))
}
