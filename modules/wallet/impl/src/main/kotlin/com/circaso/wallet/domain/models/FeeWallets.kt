package com.circaso.wallet.domain.models

import com.circaso.shared.Network
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Where a network's fee wallets stand (decision 2026-11-18): `READY` when every chain that can carry a fee has an
 * address fees are collected at; `NOT_CREATED` when some have none and nothing went wrong (no key, no entity secret);
 * `FAILED` when creating one was tried and Circle refused or couldn't be reached.
 */
enum class FeeWalletStatus { READY, NOT_CREATED, FAILED }

/** How a fee wallet row came to be. */
enum class FeeWalletOrigin {
    /** This server created it at Circle. */
    CREATED,

    /** It existed (made by hand or by the old script) and was found at Circle by its address or `refId`. */
    IMPORTED,

    /** Only the address is known: no Circle wallet behind it that this server can reach. */
    ADDRESS_ONLY,
}

data class FeeWalletNetwork(
    val network: Network,
    val walletSetId: String?,
    val status: FeeWalletStatus,
    /** Why it isn't `READY`, or what a `READY` one can't do (no withdrawals); never a secret. */
    val reason: String?,
    val updatedAt: Instant,
)

data class FeeWallet(
    val blockchain: String,
    val address: String,
    val circleWalletId: String?,
    val walletSetId: String?,
    val origin: FeeWalletOrigin,
    val createdAt: Instant,
) {
    val network: Network get() = Network.ofChain(blockchain)
}

/** An address collected fees may be withdrawn to. [removedAt] set: no longer usable, kept for the withdrawals' sake. */
data class FeeDestination(
    val id: UUID,
    val blockchain: String,
    val address: String,
    val label: String,
    val createdBy: UUID,
    val createdByEmail: String,
    val createdAt: Instant,
    val removedAt: Instant? = null,
) {
    val network: Network get() = Network.ofChain(blockchain)

    /** From when it may be withdrawn to: [delay] after it was added. */
    fun usableAt(delay: Duration): Instant = createdAt.plus(delay)
}

/**
 * `PENDING`: recorded; Circle isn't known to have it yet. `SENT`: Circle accepted it. `CONFIRMED`: on the chain.
 * `FAILED`: Circle refused it or the chain did; nothing left the wallet.
 */
enum class WithdrawalState {
    PENDING,
    SENT,
    CONFIRMED,
    FAILED,
    ;

    /** The only moves there are: a withdrawal never goes back, and a finished one never changes. */
    fun mayBecome(next: WithdrawalState): Boolean =
        when (this) {
            PENDING -> next == SENT || next == FAILED
            SENT -> next == CONFIRMED || next == FAILED
            CONFIRMED, FAILED -> false
        }

    val isOpen: Boolean get() = this == PENDING || this == SENT
}

data class FeeWithdrawal(
    val id: UUID,
    val blockchain: String,
    val asset: String,
    /** Empty for the chain's own coin. */
    val tokenAddress: String,
    val decimals: Int,
    val amountMinor: BigInteger,
    val destinationId: UUID,
    val destinationAddress: String,
    val destinationLabel: String,
    val circleWalletId: String,
    val tokenId: String,
    /** The idempotency key every attempt to send this withdrawal carries at Circle. */
    val circleKey: UUID,
    val state: WithdrawalState,
    val circleTxId: String?,
    val txHash: String?,
    val failureReason: String?,
    val reason: String,
    val requestedBy: UUID,
    val requestedByEmail: String,
    val idempotencyKey: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val network: Network get() = Network.ofChain(blockchain)

    override fun toString(): String = "FeeWithdrawal($id, $blockchain, $state)"
}

/** What staff ask for: [amountMinor] of [asset] from [blockchain]'s fee wallet to a destination, and why. */
data class WithdrawalOrder(
    val network: Network,
    val blockchain: String,
    val asset: String,
    val amountMinor: BigInteger,
    val destinationId: UUID,
    val reason: String,
    /** The staff request's `Idempotency-Key`. */
    val idempotencyKey: String,
)

/** What moving a withdrawal to another state records with it. */
data class WithdrawalChange(
    val circleTxId: String? = null,
    val txHash: String? = null,
    val failureReason: String? = null,
)

/** Who acts on the fee wallets: the staff member's id and their email at the time. */
data class FeeStaff(val staffId: UUID, val email: String)
