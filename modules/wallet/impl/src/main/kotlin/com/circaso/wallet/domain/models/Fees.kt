package com.circaso.wallet.domain.models

import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.api.WalletOwnerType
import java.time.Duration
import java.time.Instant
import java.util.UUID

// Phase 2F, fees and limits (spaces-plan §7a, §7b, decided 2026-11-03). Every asset the wallet sends is a US dollar
// stablecoin with 6 decimals, so an amount's minor units are also micro-dollars: limits and fees in dollars are kept
// in those units here ("…Minor").

/** Between two Circaso wallets (any owner), or to an address outside Circaso. Money arriving from outside is free. */
enum class FeeKind { INTERNAL, EXTERNAL }

/**
 * What a fee rule matches on for one payment: its [kind], the owner types on both sides ([receiverType] null for an
 * outside address), the chain, the asset and the sending wallet.
 */
data class FeeParties(
    val kind: FeeKind,
    val senderType: WalletOwnerType,
    val receiverType: WalletOwnerType?,
    val blockchain: String,
    val asset: Asset,
    val walletId: WalletId,
)

/**
 * One version of a fee rule (`wallet.fee_rules`). A null match field matches anything. [rateBps] (0..200 by the
 * database's bounds) applies to at most [capBaseMinor] of a payment; [minFeeMinor] is a floor on top of the gas floor;
 * [gasShareMaxBps] is the most of the fee the network cost may be (2 500: 25 %), estimated with [gasMarginBps] on top.
 * A change is a new version: history is never overwritten.
 */
data class FeeRule(
    val ruleId: UUID,
    val version: Int,
    val kind: FeeKind?,
    val senderType: WalletOwnerType?,
    val receiverType: WalletOwnerType?,
    val blockchain: String?,
    val asset: Asset?,
    val walletId: WalletId?,
    val rateBps: Int,
    val capBaseMinor: Long,
    val minFeeMinor: Long?,
    val gasShareMaxBps: Int,
    val gasMarginBps: Int,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
) {
    fun inForce(at: Instant): Boolean = !effectiveFrom.isAfter(at) && (effectiveTo == null || at.isBefore(effectiveTo))

    fun matches(parties: FeeParties): Boolean =
        (kind == null || kind == parties.kind) &&
            (senderType == null || senderType == parties.senderType) &&
            (receiverType == null || receiverType == parties.receiverType) &&
            (blockchain == null || blockchain == parties.blockchain) &&
            (asset == null || asset == parties.asset) &&
            (walletId == null || walletId == parties.walletId)

    /** The most specific rule wins: a wallet override above a chain, above an asset, above owner types, above kind. */
    val specificity: Int
        get() =
            (if (walletId != null) 32 else 0) + (if (blockchain != null) 16 else 0) + (if (asset != null) 8 else 0) +
                (if (senderType != null) 2 else 0) + (if (receiverType != null) 2 else 0) + (if (kind != null) 1 else 0)
}

/**
 * The one Circaso fee for a payment, network cost included (§7b): [amountMinor] in the payment's asset, from rule
 * [ruleId] v[ruleVersion] at [rateBps]; [minimum] when the floor (gas or `min_fee_usd`) made it more than the rate
 * did. [gasMinor] is the network cost estimated for this exact call (Circaso sponsors it; null when none was
 * estimated). [feeAddress] is Circaso's fee wallet on the chain, null when there is no fee leg. [reason] says why a
 * fee is zero when a rule didn't decide it.
 */
data class Fee(
    val amountMinor: Long,
    val rateBps: Int,
    val minimum: Boolean,
    val ruleId: UUID?,
    val ruleVersion: Int?,
    val gasMinor: Long?,
    val gasShareMaxBps: Int,
    val parties: FeeParties,
    val feeAddress: String?,
    val reason: NoFeeReason? = null,
) {
    val charged: Boolean get() = amountMinor > 0 && feeAddress != null

    override fun toString(): String = "Fee(rule=$ruleId v$ruleVersion, $rateBps bps, minimum=$minimum)"
}

/** Why a payment carries no fee though no 0 % rule said so. */
enum class NoFeeReason {
    /** No rule matches the payment. */
    NO_RULE,

    /** The chain can't carry a fee leg in the same signature (Solana, until a second spike). */
    NO_SPLIT,

    /** No Circaso fee wallet is configured on the chain. */
    NO_FEE_WALLET,
}

/**
 * A fee shown to the person before signing (W17), held for [Transfer.SIGNING_WINDOW]: W4 with its [quoteId] charges
 * exactly this fee while it holds, for exactly this payment ([holder], [blockchain], [payeeKey], [amount]).
 */
data class FeeQuote(
    val quoteId: UUID,
    val holder: UserId,
    val signerId: UserId,
    val blockchain: String,
    val payeeKey: String,
    val amount: Money,
    val fee: Fee,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString(): String = "FeeQuote($quoteId, $blockchain)"
}

/**
 * One version of a limit rule (`wallet.limit_rules`, §7a): per owner type, optionally one wallet, optionally one
 * asset. [perPaymentMaxMinor] caps one payment's amount; [dailyMaxMinor] caps what leaves the wallet, fees included,
 * over a rolling [window]. A null cap is no cap.
 */
data class LimitRule(
    val ruleId: UUID,
    val version: Int,
    val ownerType: WalletOwnerType?,
    val walletId: WalletId?,
    val asset: Asset?,
    val perPaymentMaxMinor: Long?,
    val dailyMaxMinor: Long?,
    val window: Duration,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
) {
    fun inForce(at: Instant): Boolean = !effectiveFrom.isAfter(at) && (effectiveTo == null || at.isBefore(effectiveTo))

    fun matches(
        ownerType: WalletOwnerType,
        walletId: WalletId,
        asset: Asset,
    ): Boolean =
        (this.ownerType == null || this.ownerType == ownerType) &&
            (this.walletId == null || this.walletId == walletId) &&
            (this.asset == null || this.asset == asset)

    val specificity: Int
        get() = (if (walletId != null) 4 else 0) + (if (ownerType != null) 2 else 0) + (if (asset != null) 1 else 0)
}

/** One payment counted in a wallet's window: when it was prepared and what it takes out, fee included. */
data class Spent(val at: Instant, val totalMinor: Long)

/**
 * Where a wallet stands against its limits now: what has left in the window ([usedMinor]), what may still leave
 * ([leftMinor], null without a daily cap) and when more becomes available ([moreAt], the oldest counted payment's
 * end of window).
 */
data class Allowance(
    val perPaymentMaxMinor: Long?,
    val dailyMaxMinor: Long?,
    val usedMinor: Long,
    val window: Duration,
    val spent: List<Spent>,
) {
    val leftMinor: Long? get() = dailyMaxMinor?.let { (it - usedMinor).coerceAtLeast(0) }

    val moreAt: Instant? get() = spent.minByOrNull { it.at }?.at?.plus(window)

    /** Whether [totalMinor] more may leave now. */
    fun fits(totalMinor: Long): Boolean = dailyMaxMinor == null || usedMinor + totalMinor <= dailyMaxMinor

    /** When [totalMinor] fits as the oldest payments leave the window; now's null if it fits, never's null too. */
    fun fitsAt(totalMinor: Long): Instant? {
        val max = dailyMaxMinor ?: return null
        if (totalMinor > max || fits(totalMinor)) return null
        var used = usedMinor
        for (item in spent.sortedBy { it.at }) {
            used -= item.totalMinor
            if (used + totalMinor <= max) return item.at.plus(window)
        }
        return null
    }
}

/** Where a fee stands in the ledger: quoted with the transfer, collected once it is confirmed, void if it never went. */
enum class FeeState { QUOTED, COLLECTED, VOID }

/** One row of the fee ledger per transfer (§7b "Fee address and accounting"), written with the transfer. */
data class FeeEntry(
    val transferId: TransferId,
    val holder: UserId,
    val amount: Money,
    val fee: Fee,
    val state: FeeState,
    val createdAt: Instant,
) {
    override fun toString(): String = "FeeEntry($transferId, $state)"
}

/** Why a quoted payment can't go as it is (W17 reports it rather than refusing). */
enum class QuoteProblem {
    /** The wallet doesn't hold the amount and its fee (GW20). */
    INSUFFICIENT_FUNDS,

    /** One payment's amount is over its cap. */
    PER_PAYMENT_LIMIT,

    /** The total doesn't fit what is left of the rolling window (GW14b). */
    DAILY_LIMIT,
}

/**
 * W17's answer: the one fee for this exact payment and what it means. [quoteId] is set only for the person who signs
 * for the wallet (W4 then charges exactly this fee until [expiresAt]); everyone else sees the fee as it stands.
 * [maxAmountMinor] is the most that can be sent now with its fee ("Send all", GW20's offer).
 */
data class TransferQuote(
    val blockchain: String,
    val amount: Money,
    val fee: Fee,
    val balanceMinor: Long,
    val maxAmountMinor: Long,
    val allowance: Allowance,
    val problem: QuoteProblem?,
    val quoteId: UUID?,
    val expiresAt: Instant,
) {
    val totalMinor: Long get() = amount.amountMinor + fee.amountMinor

    /** When this payment fits the window, if it doesn't now (GW14b's "from 14:30"); null if it fits or never can. */
    val fitsAt: Instant? get() = allowance.fitsAt(totalMinor)

    override fun toString(): String = "TransferQuote($blockchain, $problem)"
}
