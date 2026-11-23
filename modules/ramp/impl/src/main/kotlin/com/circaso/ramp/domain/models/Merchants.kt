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
 * cleared when they go online again or a later prompt goes through. [merchantType] and [orderLimitMinor] are staff's;
 * [offersOnramp] and [offersOfframp] are the merchant's own (decision 2026-11-23).
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
    /** Set by staff: decides the order limit the merchant starts with. */
    val merchantType: MerchantType = MerchantType.INDIVIDUAL,
    /** Staff's own limit for this merchant, KES minor units; null: their type's default from the engine settings. */
    val orderLimitMinor: Long? = null,
    /** The merchant's own switches on the portal: which directions they take orders in. */
    val offersOnramp: Boolean = true,
    val offersOfframp: Boolean = false,
    /** The merchant's account at the payout provider (FinoQ's account id, digits): payouts need it. Not a secret. */
    val payoutAccountId: String? = null,
    /** Set when the provider refused a payout for something only the merchant can fix; cleared when one goes out. */
    val payoutIssue: PayoutIssue? = null,
    val payoutIssueAt: Instant? = null,
    /**
     * When the merchant last set their provider's webhook signing secret; null when none is set. The secret itself is
     * never part of the merchant: it is read sealed, only by the callback check ([StoredWebhookSecret]).
     */
    val webhookSecretUpdatedAt: Instant? = null,
) {
    /** Approved, and inside a trading window that lasts past [until]. */
    fun isTradingUntil(until: Instant): Boolean = state == MerchantState.APPROVED && onlineUntil?.isAfter(until) == true

    /** Whether the merchant takes orders in [direction]: a sale also needs their payout account. */
    fun offers(direction: Direction): Boolean =
        when (direction) {
            Direction.ON_RAMP -> offersOnramp
            Direction.OFF_RAMP -> offersOfframp && payoutAccountId != null
        }

    /** Whether something at the provider stands in the way of an order in [direction] until the merchant fixes it. */
    fun hasIssue(direction: Direction): Boolean =
        when (direction) {
            Direction.ON_RAMP -> paymentsIssue != null
            Direction.OFF_RAMP -> paymentsIssue != null || payoutIssue != null
        }

    /** The most one order may be for this merchant: their own limit, else their type's under [settings]. */
    fun orderLimit(settings: EngineSettings): Long = orderLimitMinor ?: settings.tierLimit(merchantType)

    override fun toString(): String = "Merchant($merchantId, $state)"
}

/**
 * A merchant's webhook signing secret as kept: sealed under a key derived from the ramp's master key, bound to
 * [merchantId]. Found by the callback address it guards. Its string form shows nothing.
 */
class StoredWebhookSecret(val merchantId: MerchantId, val sealed: ByteArray) {
    override fun toString(): String = "StoredWebhookSecret($merchantId, ***)"
}

/** The webhook signing secret's rules (2026-11-23): what FinoQ shows is pasted as it is, one line, no spaces. */
object WebhookSecret {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 512
    private val PRINTABLE = Regex("[\\x21-\\x7E]+")

    fun isValid(secret: String): Boolean = secret.length in MIN_LENGTH..MAX_LENGTH && PRINTABLE.matches(secret)
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
    /** The merchant's identity verification as [network]'s settings see it. */
    val kyc: KycView = KycView(KycState.NONE, required = false, canStart = true, reason = null, retryAt = null),
    /** Selling: the payout account, the balances at the provider and what stands in the way (2026-11-23). */
    val offRamp: OffRampView = OffRampView.NONE,
) {
    /** A PIN exists once a payment account has been sealed under one. */
    val pinSet: Boolean get() = payments != null

    /**
     * `kyc` is identity verification not passed where the network requires it. A float the custodian can't report
     * isn't counted as missing. `provider_balance` is the provider having refused a prompt for the merchant's
     * service balance.
     */
    val missing: List<String>
        get() =
            listOfNotNull(
                "profile".takeIf { merchant.state == MerchantState.NEW },
                KYC.takeIf { kyc.blocksTrading },
                "approval".takeIf { merchant.state != MerchantState.APPROVED },
                "wallet".takeIf { wallet == null },
                "payments".takeIf { payments == null || !paymentsCurrent },
                // USDC float is what buying sells; a merchant who only takes sales needs none (2026-11-23).
                "float".takeIf {
                    merchant.offersOnramp && float != null &&
                        (float.freeMinor <= 0 || float.freeMinor < floatFloorMinor)
                },
                PROVIDER_BALANCE.takeIf { merchant.paymentsIssue == PaymentsIssue.SERVICE_BALANCE },
            )

    /**
     * Whether RM6 is worth trying. A provider balance issue doesn't stand in the way: going online is what checks
     * the balance again and clears it.
     */
    val canGoOnline: Boolean get() = missing.all { it == PROVIDER_BALANCE }

    companion object {
        const val PROVIDER_BALANCE = "provider_balance"
        const val KYC = "kyc"
    }
}

data class OnlineView(val online: Boolean, val until: Instant?)

/** RM5's and RM14's answer. */
data class CallbackView(val callbackUrl: String)

/** RM1's input, already shape-checked. */
data class ProfileCommand(val displayName: String, val contactPhone: PhoneNumber)

/**
 * RM5's input. [payoutAccountId] is the account id payouts need (FinoQ's, digits; 2026-11-23), null to leave it as it
 * is. The string form hides all of it.
 */
class PaymentAccountCommand(
    val credentials: CollectionCredentials,
    val pin: String,
    val payoutAccountId: String? = null,
) {
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
    /** `ON_RAMP`: [paidMinor] shillings in, [releasedMinor] USDC out. `OFF_RAMP`: shillings paid out, USDC received. */
    val direction: Direction = Direction.ON_RAMP,
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
    /** Buying: the shillings paid into the merchant's till, and the USDC released to buyers. */
    val collectedMinor: Long,
    val soldMinor: Long,
    val feesMinor: Long,
    val merchantMinor: Long,
    val platformMinor: Long,
    val platformAssetMinor: Long,
    val sweptAssetMinor: Long,
    /** Selling (2026-11-23): the shillings the merchant paid out to sellers, and the USDC they received for them. */
    val paidOutMinor: Long = 0,
    val boughtMinor: Long = 0,
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
            paidOutMinor = paidOutMinor + other.paidOutMinor,
            boughtMinor = boughtMinor + other.boughtMinor,
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

/**
 * A merchant as staff see it: the account, its float wallets, whether a payment account is stored, and their
 * identity verification in each provider environment they have one.
 */
data class MerchantRecord(
    val merchant: Merchant,
    val wallets: List<MerchantWallet>,
    val paymentsConnected: Boolean,
    val verifications: List<Verification> = emptyList(),
)
