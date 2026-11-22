package com.circaso.ramp.domain.models

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Partner approval. `NEW` is an account that has only signed in; `APPLIED` has given its name and phone and waits for
 * staff; going online is a separate gate.
 */
enum class MerchantState { NEW, APPLIED, APPROVED, SUSPENDED }

/**
 * Something at the merchant's payment provider that only the merchant can put right. While one is set the engine gives
 * them no order.
 */
enum class PaymentsIssue {
    /** The provider refused a prompt because the merchant's prepaid service balance there can't pay its fee. */
    SERVICE_BALANCE,
}

/**
 * A partner merchant: an account of its own, signed in to by [email]. [onlineUntil] is the end of the trading window,
 * null when offline. [callbackKey] is the end of the merchant's own callback address, null until they connect a
 * payment account. [paymentsIssue] is set when the provider refused a prompt for a reason the merchant must fix, and
 * cleared when they go online again or a later prompt goes through.
 */
data class Merchant(
    val merchantId: MerchantId,
    val email: String,
    val displayName: String,
    val contactPhone: String,
    val state: MerchantState,
    val callbackKey: String?,
    val onlineUntil: Instant?,
    val lastAssignedAt: Instant?,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val createdAt: Instant,
    val paymentsIssue: PaymentsIssue? = null,
    val paymentsIssueAt: Instant? = null,
) {
    /** Approved, and inside a trading window that lasts past [until]. */
    fun isTradingUntil(until: Instant): Boolean = state == MerchantState.APPROVED && onlineUntil?.isAfter(until) == true

    override fun toString(): String = "Merchant($merchantId, $state)"
}

/** A merchant's float wallet on one network: a developer-controlled wallet the ops process signs for. */
data class MerchantWallet(
    val merchantId: MerchantId,
    val network: Network,
    val blockchain: String,
    val custodyWalletId: String,
    val address: String,
)

/** Circaso's own ramp wallet on one network, where its share of the fees is swept to. */
data class RevenueWallet(
    val network: Network,
    val blockchain: String,
    val custodyWalletId: String,
    val address: String,
)

/** A merchant the engine may give an order to, with the wallet the float is in. */
data class Candidate(val merchant: Merchant, val wallet: MerchantWallet)

/**
 * A merchant's payment credentials as they are kept: AES-256-GCM under a random data key ([sealed]), the data key
 * wrapped under a key derived from the ramp's master key and the merchant's PIN ([wrappedKey], [pinSalt],
 * [kdfParams]). Nothing here opens without both. [channelId] is the till or paybill, shown on the portal.
 */
class SealedCredentials(
    val provider: String,
    val channelId: String,
    val sealed: ByteArray,
    val wrappedKey: ByteArray,
    val pinSalt: ByteArray,
    val kdfParams: String,
) {
    override fun toString(): String = "SealedCredentials($provider, ***)"
}

/**
 * A merchant's payment keys as a node holds them while the merchant is online: the credentials, and the [provider]
 * they are an account at. A prompt or a status query for another provider never uses them.
 */
class UnlockedKeys(val provider: String, val credentials: CollectionCredentials) {
    override fun toString(): String = "UnlockedKeys($provider, ***)"
}

/** What opening the envelope with a PIN gave. */
sealed interface Unsealed {
    class Opened(val credentials: CollectionCredentials) : Unsealed

    data object WrongPin : Unsealed

    /** The master key isn't on this server: nothing can be sealed or opened. */
    data object Unavailable : Unsealed
}

/** What re-wrapping the envelope under a new PIN gave. */
sealed interface Rewrapped {
    class Done(val sealed: SealedCredentials) : Rewrapped

    data object WrongPin : Rewrapped

    data object Unavailable : Rewrapped
}

/** A portal sign-in: the token itself is never kept, only its hash, which finds the row. */
data class MerchantSession(val merchantId: MerchantId, val expiresAt: Instant)

/** MA1's answer: the same whoever asked. */
data class CodeSent(val retryAfterSeconds: Long)

/** MA2's answer. [token] is shown once and never stored. */
class SignedIn(val token: String, val expiresAt: Instant, val merchant: MerchantOverview) {
    override fun toString(): String = "SignedIn(***)"
}

/** How a sign-in code check ended. */
enum class CodeCheck { MATCH, WRONG, LOCKED }

/** A merchant's float on one network, in USDC minor units. [reserved] is open orders plus what is held for Circaso. */
data class FloatView(val balanceMinor: Long, val reservedMinor: Long) {
    val freeMinor: Long get() = (balanceMinor - reservedMinor).coerceAtLeast(0)
}

data class PaymentsView(val provider: String, val channelId: String, val callbackUrl: String?)

/** RM2: the merchant, what is set up and what is still missing before they can go online on [network]. */
data class MerchantOverview(
    val merchant: Merchant,
    val network: Network,
    val wallet: MerchantWallet?,
    /** Null without a wallet, or when the custodian can't be asked. */
    val float: FloatView?,
    val payments: PaymentsView?,
    /** Whether the stored payment account is at the provider the network uses now; false means reconnect. */
    val paymentsCurrent: Boolean,
    val online: Boolean,
    /** The least free float worth going online with: what the smallest order buys. Zero when it can't be priced. */
    val floatFloorMinor: Long,
) {
    /** A PIN exists once a payment account has been sealed under one. */
    val pinSet: Boolean get() = payments != null

    /**
     * `kyc` joins this list with Didit; a float the custodian can't report isn't counted as missing.
     * `provider_balance` is the provider having refused a prompt for the merchant's service balance.
     */
    val missing: List<String>
        get() =
            listOfNotNull(
                "profile".takeIf { merchant.state == MerchantState.NEW },
                "approval".takeIf { merchant.state != MerchantState.APPROVED },
                "wallet".takeIf { wallet == null },
                "payments".takeIf { payments == null || !paymentsCurrent },
                "float".takeIf { float != null && (float.freeMinor <= 0 || float.freeMinor < floatFloorMinor) },
                PROVIDER_BALANCE.takeIf { merchant.paymentsIssue == PaymentsIssue.SERVICE_BALANCE },
            )

    /**
     * Whether RM6 is worth trying. A provider balance issue doesn't stand in the way: going online is what checks
     * the balance again and clears it.
     */
    val canGoOnline: Boolean get() = missing.all { it == PROVIDER_BALANCE }

    companion object {
        const val PROVIDER_BALANCE = "provider_balance"
    }
}

data class OnlineView(val online: Boolean, val until: Instant?)

/** RM5's and RM14's answer. */
data class CallbackView(val callbackUrl: String)

/** RM1's input, already shape-checked. */
data class ProfileCommand(val displayName: String, val contactPhone: PhoneNumber)

/** RM5's input. The string form hides all of it. */
class PaymentAccountCommand(val credentials: CollectionCredentials, val pin: String) {
    override fun toString(): String = "PaymentAccountCommand(***)"
}

enum class SettlementState { PENDING, CONFIRMED, FAILED }

/** One daily sweep of what a merchant's float owes Circaso, in USDC minor units. */
data class Settlement(
    val settlementId: UUID,
    val merchantId: MerchantId,
    val network: Network,
    val day: LocalDate,
    val amountMinor: Long,
    val orders: Int,
    val state: SettlementState,
    val custodyTxId: String?,
    val txHash: String?,
    val failureReason: String?,
    val createdAt: Instant,
)

/** What a sweep took over: the unswept commissions it now names. */
data class Stamped(val totalMinor: Long, val orders: Int)

/** One completed exchange on a merchant's statement: the order's own figures and its commission's. */
data class StatementLine(
    val orderId: RampOrderId,
    val completedAt: Instant,
    val payerPhone: PhoneNumber,
    val paidMinor: Long,
    val releasedMinor: Long,
    val rate: BigDecimal,
    val feeMinor: Long,
    val merchantMinor: Long,
    val platformMinor: Long,
    val platformAssetMinor: Long,
    val settlementId: UUID?,
    val txHash: String?,
) {
    override fun toString(): String = "StatementLine($orderId)"
}

/**
 * A period's totals, added up from the same order and commission rows the lines show. [sweptAssetMinor] is the part
 * of Circaso's USDC share a confirmed sweep has collected, [heldAssetMinor] the part the float still holds; together
 * they are [platformAssetMinor].
 */
data class StatementTotals(
    val orders: Int,
    val collectedMinor: Long,
    val soldMinor: Long,
    val feesMinor: Long,
    val merchantMinor: Long,
    val platformMinor: Long,
    val platformAssetMinor: Long,
    val sweptAssetMinor: Long,
) {
    val heldAssetMinor: Long get() = platformAssetMinor - sweptAssetMinor

    operator fun plus(other: StatementTotals) =
        StatementTotals(
            orders = orders + other.orders,
            collectedMinor = collectedMinor + other.collectedMinor,
            soldMinor = soldMinor + other.soldMinor,
            feesMinor = feesMinor + other.feesMinor,
            merchantMinor = merchantMinor + other.merchantMinor,
            platformMinor = platformMinor + other.platformMinor,
            platformAssetMinor = platformAssetMinor + other.platformAssetMinor,
            sweptAssetMinor = sweptAssetMinor + other.sweptAssetMinor,
        )
}

/** The days a statement or an overview covers, both inclusive: Nairobi calendar days. */
data class Period(val from: LocalDate, val to: LocalDate)

/** RM8. */
data class Earnings(
    val period: Period,
    val network: Network,
    val totals: StatementTotals,
    val feeBps: Int,
    val merchantShareBps: Int,
    val sweeps: List<Settlement>,
)

/** RM15: a page of lines, and the totals of the whole period. */
data class Statement(
    val period: Period,
    val network: Network,
    val lines: List<StatementLine>,
    val totals: StatementTotals,
)

/** A merchant as staff see it: the account, its float wallets, and whether a payment account is stored. */
data class MerchantRecord(val merchant: Merchant, val wallets: List<MerchantWallet>, val paymentsConnected: Boolean)
