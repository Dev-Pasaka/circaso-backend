package com.circaso.ramp.domain.models

import com.circaso.ramp.api.MerchantId
import com.circaso.shared.PhoneNumber
import java.time.Instant
import java.util.UUID

/**
 * Which of the merchant's three wallets at their provider a top-up fills (2026-11-24; RM19's `wallet`). Each is its
 * own wallet with its own balance and its own top-up.
 */
enum class TopUpTarget(
    /** What the portal and the admin call it. */
    val label: String,
    /** `PAYHERO` or `FINOQ`: whose wallet it is. */
    val provider: String,
    /** The side of the desk it serves. */
    val direction: Direction,
    /** What it is for, in one line. */
    val purpose: String,
) {
    /** PayHero's service wallet: the fee for every M-Pesa prompt (buying, the on-ramp) is taken from it. */
    PAYHERO_SERVICE(
        "PayHero service wallet",
        "PAYHERO",
        Direction.ON_RAMP,
        "PayHero's fee for each M-Pesa prompt when people buy comes from here",
    ),

    /** FinoQ's service wallet: the fee for every payout (selling, the off-ramp) is taken from it. */
    FINOQ_SERVICE(
        "FinoQ service wallet",
        "FINOQ",
        Direction.OFF_RAMP,
        "FinoQ's fee for each M-Pesa payout when people sell comes from here",
    ),

    /** FinoQ's payment wallet: what payouts to sellers spend. */
    FINOQ_PAYMENT(
        "FinoQ payment wallet",
        "FINOQ",
        Direction.OFF_RAMP,
        "Sellers' M-Pesa payouts are paid from here",
    ),
    ;

    /** FinoQ's wallets belong to the account the merchant names by its id; PayHero's needs none. */
    val needsAccountId: Boolean get() = this != PAYHERO_SERVICE

    companion object {
        /** RM19's names, and the two from before 2026-11-24: `SERVICE` (PayHero's) and `PAYMENT` (FinoQ's payment). */
        fun parse(text: String): TopUpTarget? =
            when (val name = text.trim().uppercase()) {
                "SERVICE" -> PAYHERO_SERVICE
                "PAYMENT" -> FINOQ_PAYMENT
                else -> entries.firstOrNull { it.name == name }
            }
    }
}

/**
 * Where a top-up stands. `REQUESTED` is the only open state: the prompt went out (or may have) and nobody yet knows
 * whether it was paid. `UNKNOWN` closes a top-up nothing could confirm or deny in its window; a late answer from the
 * provider that it was paid can still confirm it.
 */
enum class TopUpState {
    REQUESTED,
    CONFIRMED,
    FAILED,
    UNKNOWN,
    ;

    val isOpen: Boolean get() = this == REQUESTED
}

/** Why a top-up failed, in words the portal can say. */
enum class TopUpFailure {
    /** The merchant cancelled the prompt on their phone (the provider said so). */
    CANCELLED,

    /** The provider says the payment failed: a wrong PIN, not enough in M-Pesa, the prompt timing out… */
    FAILED,

    /** No prompt was sent: the provider can't take its fee for it. Top up the service wallet first. */
    SERVICE_BALANCE_LOW,

    /** No prompt was sent: the provider only prompts the number the account was opened with. */
    PAYER_NOT_ALLOWED,

    /** No prompt was sent: the provider refused the account's username and password. */
    CREDENTIALS_REFUSED,

    /** No prompt was sent: the provider couldn't be reached. Safe to try again. */
    PROVIDER_UNAVAILABLE,

    /** No prompt was sent: the provider refused for a reason we don't tell apart. */
    REFUSED,
}

/** What confirmed a top-up: the provider's own status, or the wallet's balance rising by the amount. */
enum class TopUpConfirmation { STATUS, BALANCE }

/**
 * A merchant topping up one of their own wallets at the provider with an M-Pesa prompt to **their own contact phone**
 * (2026-11-23). Written down before the prompt is asked for, sent once, and confirmed only by the provider's status or
 * the wallet's balance. The balances are that wallet's, read before and after.
 */
data class TopUp(
    val topUpId: UUID,
    val merchantId: MerchantId,
    val target: TopUpTarget,
    val provider: String,
    val amountMinor: Long,
    val payer: PhoneNumber,
    val state: TopUpState,
    val failure: TopUpFailure?,
    /** What the provider last said, in our words, for staff and the log (`accepted`, `unknown`, `looked_up:Pending`). */
    val outcome: String?,
    val providerReference: String?,
    val checkoutId: String?,
    val receipt: String?,
    val confirmedBy: TopUpConfirmation?,
    val balanceBeforeMinor: Long?,
    val balanceAfterMinor: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val closedAt: Instant?,
) {
    override fun toString(): String = "TopUp($topUpId, $target, $state)"
}

/** RM19's request, checked for shape: which wallet, how much (KES cents) and, while offline, the PIN. */
class TopUpCommand(val target: TopUpTarget, val amountMinor: Long, val pin: String?) {
    override fun toString(): String = "TopUpCommand($target, ***)"
}
