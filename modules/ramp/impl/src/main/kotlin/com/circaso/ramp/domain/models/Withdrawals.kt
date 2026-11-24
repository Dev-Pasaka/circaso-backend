package com.circaso.ramp.domain.models

import com.circaso.ramp.api.MerchantId
import com.circaso.shared.Network
import java.time.Instant
import java.util.UUID

/**
 * A merchant's saved withdrawal address on one network (2026-11-24). It can be withdrawn to only from [usableAt]:
 * [createdAt] plus the network's cooling-off delay when it was added, so a stolen portal session can't empty the float
 * to an address of its own before the merchant notices. Removing keeps the row ([removedAt]): withdrawals name it.
 */
data class WithdrawalDestination(
    val destinationId: UUID,
    val merchantId: MerchantId,
    val network: Network,
    val blockchain: String,
    /** EIP-55 checksummed. */
    val address: String,
    val label: String,
    val createdAt: Instant,
    val usableAt: Instant,
    val removedAt: Instant? = null,
    /** Set when a lock found it still cooling off (2026-11-24): never usable; remove it and add it again. */
    val frozenAt: Instant? = null,
) {
    fun isUsableAt(at: Instant): Boolean = removedAt == null && frozenAt == null && !usableAt.isAfter(at)
}

/**
 * `REQUESTED → SENT → COMPLETED | FAILED`; `REQUESTED → FAILED` when the custodian refuses; `REQUESTED → CANCELLED`
 * (2026-11-24) when a lock or the address's removal comes before the ops process claimed it for sending.
 */
enum class WithdrawalState {
    REQUESTED,
    SENT,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    val isOpen: Boolean get() = this == REQUESTED || this == SENT
}

/**
 * One withdrawal of float. Its amount is reserved in the float from the moment it is written (a reservation of kind
 * `WITHDRAWAL` keyed by [withdrawalId]): consumed when the chain confirms it, released when it fails. The destination is
 * copied in as it was when the merchant asked.
 */
data class Withdrawal(
    val withdrawalId: UUID,
    val merchantId: MerchantId,
    val network: Network,
    val blockchain: String,
    val destinationId: UUID,
    val destinationAddress: String,
    val destinationLabel: String,
    val amountMinor: Long,
    val state: WithdrawalState,
    val idempotencyKey: String?,
    val custodyTxId: String? = null,
    val txHash: String? = null,
    val failureReason: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sentAt: Instant? = null,
    val closedAt: Instant? = null,
    /** When the ops process claimed it for sending; from then on it can't be cancelled. */
    val sendClaimedAt: Instant? = null,
)

/** RM21: what the merchant asked for; the PIN (and a passkey, once they have one) confirms it. */
data class WithdrawCommand(
    val amountMinor: Long,
    val destinationId: UUID,
    val pin: String,
    val idempotencyKey: String?,
    val passkey: PasskeyAssertion? = null,
) {
    override fun toString(): String = "WithdrawCommand($amountMinor, $destinationId, ***)"
}

/** RM20: a new address and its label; the PIN (and a passkey, once they have one) confirms it. */
data class DestinationCommand(
    val address: String,
    val label: String,
    val pin: String,
    val passkey: PasskeyAssertion? = null,
) {
    override fun toString(): String = "DestinationCommand($address, $label, ***)"
}

/** RM20: an address just saved, and whether it is usable already (a delay of 0). */
data class SavedDestination(val destination: WithdrawalDestination, val usable: Boolean)

/** RM20's list: the live addresses, the delay a new one waits, the chain they are on (null: no float wallet yet). */
data class DestinationBook(
    val destinations: List<WithdrawalDestination>,
    val delayHours: Int,
    val blockchain: String?,
    val max: Int,
    val now: Instant,
)

/** RM21's list: a page of withdrawals and the limits a new one must fit. */
data class WithdrawalPage(
    val withdrawals: List<Withdrawal>,
    val minMinor: Long,
    val dailyMaxMinor: Long?,
    val usedTodayMinor: Long,
    val next: Withdrawal?,
)

/** Staff's read of one merchant's withdrawals and addresses on a network. */
data class StaffWithdrawals(val withdrawals: List<Withdrawal>, val destinations: List<WithdrawalDestination>)
