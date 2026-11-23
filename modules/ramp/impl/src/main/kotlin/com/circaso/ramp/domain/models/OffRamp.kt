package com.circaso.ramp.domain.models

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.PhoneNumber
import java.time.Instant
import java.util.UUID

/**
 * One line of staff's payout fee table (decision 2026-11-23): an amount up to [upToMinor] (KES minor units, inclusive)
 * costs [feeMinor]. Used only when the provider's own published table can't be read; an amount above every band has
 * no fee there, and a sale of it is not quoted.
 */
data class PayoutFeeBand(val upToMinor: Long, val feeMinor: Long)

/** FinoQ numbers its accounts ("teams"): an integer, as its dashboard shows it. Not a secret. */
object PayoutAccount {
    val PATTERN = Regex("^[0-9]{1,18}$")
}

/** Something the payout provider refused that only the merchant can put right; no sale is routed to them meanwhile. */
enum class PayoutIssue {
    /** Their payout balance couldn't cover a payout: they top up. */
    PAYOUT_BALANCE,

    /** The provider refused their payout outright (credentials, account, a reason it didn't give): they check it. */
    PAYOUT_REFUSED,
}

/** The provider's payout fee for one amount, and where it came from (`LIVE_TABLE`, `CACHED` or `ADMIN_TABLE`). */
data class PayoutFee(val feeMinor: Long, val source: String) {
    companion object {
        const val ADMIN_TABLE = "ADMIN_TABLE"
    }
}

/** One wallet's balance as read, in KES minor units, with the call that gave it and when. */
data class WalletBalance(val minor: Long, val source: String, val readAt: Instant)

/**
 * A merchant's three wallets at the provider (2026-11-24, the owner's "the PayHero service wallet is for on-ramp,
 * the FinoQ service wallet is for off-ramp"), each read from itself and never filled in from another. A null wallet
 * is one that couldn't be read, which is never the same as zero. A reading that couldn't read a wallet keeps the
 * last one that did, with its own time; [readAt] is the last reading of any of them (null: never read).
 */
data class KesBalances(
    /** PayHero's service wallet: an M-Pesa prompt's fee (buying) is taken from it. */
    val payheroService: WalletBalance? = null,
    /** FinoQ's service wallet: a payout's fee (selling) is taken from it. */
    val finoqService: WalletBalance? = null,
    /** FinoQ's payment wallet: what payouts to sellers spend. */
    val finoqPayment: WalletBalance? = null,
    val readAt: Instant? = null,
) {
    val payoutMinor: Long? get() = finoqPayment?.minor

    /** The wallet [target] fills. */
    fun of(target: TopUpTarget): WalletBalance? =
        when (target) {
            TopUpTarget.PAYHERO_SERVICE -> payheroService
            TopUpTarget.FINOQ_SERVICE -> finoqService
            TopUpTarget.FINOQ_PAYMENT -> finoqPayment
        }

    /** This reading over [older]: each wallet this one couldn't read keeps the older reading. */
    fun over(older: KesBalances): KesBalances =
        KesBalances(
            payheroService = payheroService ?: older.payheroService,
            finoqService = finoqService ?: older.finoqService,
            finoqPayment = finoqPayment ?: older.finoqPayment,
            readAt = readAt ?: older.readAt,
        )

    companion object {
        val UNREAD = KesBalances()
    }
}

/**
 * One of the merchant's three wallets at the provider as RM2 and staff show it: its last reading ([balance] null:
 * never read, unknown, never zero), whether it is [low], and the line it is held against ([lowBelowMinor], KES minor
 * units; null when the network sets none).
 */
data class ProviderWalletView(
    val target: TopUpTarget,
    val balance: WalletBalance?,
    val low: Boolean,
    val lowBelowMinor: Long?,
)

/** Where a merchant's test payout stands. */
enum class TestPayoutState {
    /** Recorded and being sent: if the call never answered, it may have gone out. */
    SENDING,

    /** The provider took it; only its lookup says it was paid. */
    ACCEPTED,

    /** No usable answer came back: it may have gone out. Looked up, never sent again. */
    UNKNOWN,
    PAID,
    FAILED,

    /** Refused, or the provider never reached: nothing went out. */
    NOT_SENT,

    /** The provider says it knows no such payout. A person checks the M-Pesa statement. */
    NOT_FOUND,
    ;

    val isOpen: Boolean get() = this == SENDING || this == ACCEPTED || this == UNKNOWN
}

/**
 * A merchant's test payout (decision 2026-11-23): KES 10 to 100 from their own account to their own contact phone, sent
 * once and followed exactly as a sale's payout, with the balances before and after so the provider's real charge can
 * be read. Never logged in full.
 */
data class TestPayout(
    val testId: UUID,
    val merchantId: MerchantId,
    val provider: String,
    val amountMinor: Long,
    val payee: PhoneNumber,
    val state: TestPayoutState,
    val outcome: String?,
    val providerReference: String?,
    val secondaryReference: String?,
    val receipt: String?,
    val paidAmountMinor: Long?,
    val providerFee: PayoutFee?,
    val balanceBefore: BalanceRead?,
    val balanceAfter: BalanceRead?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "TestPayout($testId, $state)"
}

/** One payout-balance reading: the amount and the call that gave it. */
data class BalanceRead(val amountMinor: Long, val source: String)

/** How staff resolve an order in `REVIEW` (RO4, decision 2026-11-23). */
enum class Resolution {
    /** The money reached the person: a sale's payout (with its M-Pesa receipt), or a purchase's payment. */
    MARK_PAID,

    /** The person gets their money back: a sale's USDC from the merchant's float, or a purchase closed unreleased. */
    REFUND,

    /** Ask the provider again, now, and apply what it says. */
    RECHECK,
}

/** RO4's input. [receipt] is the M-Pesa receipt staff saw, needed to mark paid. */
data class ResolveCommand(
    val orderId: RampOrderId,
    val resolution: Resolution,
    val receipt: String?,
    val by: String,
)

/** RO4's answer: the order as it stands afterwards (with its merchant's name), and what the provider said. */
data class Resolved(val view: OrderView, val providerSaid: String?) {
    val order: Order get() = view.order
}

/**
 * RM2's off-ramp card (decision 2026-11-23): whether the merchant sells, their payout account, their balances at the
 * provider (read live while they are online on this node, else the last reading with its time; a balance that can't be
 * read is null, "unknown", never zero), what their open sales hold, and the flags the portal words.
 */
data class OffRampView(
    val offered: Boolean,
    val accountId: String?,
    val balances: KesBalances,
    /** Whether [balances] was read from the provider just now (true) or is the last reading kept (false). */
    val live: Boolean,
    /** What the merchant's open sales hold of the payout balance (the payouts and their fees). */
    val heldMinor: Long,
    val payoutIssue: PayoutIssue?,
    val payoutIssueAt: Instant?,
    val flags: List<String>,
) {
    companion object {
        val NONE = OffRampView(false, null, KesBalances.UNREAD, false, 0, null, null, emptyList())

        /** The payout balance is below the network's line (`payout_balance_low_minor`). */
        const val PAYOUT_BALANCE_LOW = "payout_balance_low"

        /** FinoQ's service wallet (a payout's fee) is below the network's provider-fee floor. */
        const val SERVICE_BALANCE_LOW = "service_balance_low"

        /** No payout account id: payouts can't be sent. */
        const val PAYOUT_ACCOUNT_MISSING = "payout_account_missing"
    }
}
