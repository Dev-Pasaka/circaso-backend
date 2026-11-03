package com.circaso.wallet.domain.models

import com.circaso.integrations.api.SdkSession
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Where a transfer (and the transaction rows that record it) stands. A state only moves by the transition guard
 * ([from]): an update that would move it from anywhere else changes nothing, so webhooks are ordered by what they
 * mean, not by when they arrive (decision 2026-10-27, PG0 review). Circle's `STUCK` is shown as [SUBMITTED].
 */
enum class TransferState {
    /** Prepared; the user hasn't signed the challenge with the PIN yet. */
    AWAITING_SIGNATURE,

    /** Signed and on its way (Circle `INITIATED`, `CLEARED`, `QUEUED`, `SENT`, `STUCK`). */
    SUBMITTED,

    /** On chain (Circle `CONFIRMED`, `COMPLETE`). */
    CONFIRMED,

    /** Circle `FAILED`, `DENIED`, `CANCELLED`, or refused when it was prepared. */
    FAILED,

    /** Never signed within 15 minutes (`ExpireUnsignedTransfers`, WS4). */
    EXPIRED,
    ;

    /**
     * The states a transfer may move here from. An `EXPIRED` transfer still follows Circle: if the chain says it was
     * signed after all, the chain is right.
     */
    val from: Set<TransferState>
        get() =
            when (this) {
                AWAITING_SIGNATURE -> emptySet()
                SUBMITTED -> setOf(AWAITING_SIGNATURE, EXPIRED)
                CONFIRMED, FAILED -> setOf(AWAITING_SIGNATURE, SUBMITTED, EXPIRED)
                EXPIRED -> setOf(AWAITING_SIGNATURE)
            }
}

/** Who started a transfer: the user in the app, or Asha's proposal the user confirmed (Phase B). */
enum class TransferOrigin { APP, ASHA }

enum class Direction { IN, OUT }

/** Who W4 pays: a Circaso user, or an address outside Circaso on the transfer's chain. */
sealed interface Payee {
    data class Person(val userId: UserId) : Payee

    data class Address(val address: String) : Payee {
        override fun toString(): String = "Address(***)"
    }

    /** A group's wallet (decision 2026-11-03): a member pays into it, a contribution when the group has a target. */
    data class Group(val chatId: ChatId) : Payee
}

/** A transfer prepared by W4, from one of the sender's chain wallets. Amounts and addresses are never logged. */
data class Transfer(
    val transferId: TransferId,
    val senderId: UserId,
    val senderWalletId: String,
    val blockchain: String,
    /** Derived from the W4 request's `Idempotency-Key`; also Circle's idempotency key. */
    val idempotencyKey: UUID,
    /** Null for an outside address. */
    val recipientId: UserId?,
    val destinationAddress: String,
    val amount: Money,
    val state: TransferState,
    val circleTxId: String?,
    val txHash: String?,
    val chatId: ChatId?,
    val origin: TransferOrigin,
    val proposalId: UUID?,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Who signed it; null on transfers written before group wallets, where it was always [senderId]. */
    val signerId: UserId? = null,
    /** The approved payout request it pays, when it leaves a group wallet. */
    val payoutRequestId: UUID? = null,
    /** The group whose wallet it leaves ([senderId] is then that wallet's holder id). */
    val fromGroupId: ChatId? = null,
    /** The group whose wallet it pays ([recipientId] is then that wallet's holder id). */
    val toGroupId: ChatId? = null,
) {
    /** The person who signed it. */
    val signer: UserId get() = signerId ?: senderId

    /** Until when the user can sign it; then `ExpireUnsignedTransfers` (WS4) marks it `EXPIRED`. */
    val expiresAt: Instant get() = createdAt.plus(SIGNING_WINDOW)

    override fun toString(): String = "Transfer($transferId, $blockchain, $state)"

    companion object {
        val SIGNING_WINDOW: Duration = Duration.ofMinutes(15)
    }
}

/**
 * W5's answer: the transfer and the side it is seen from ([side]: the wallet holder whose payment it is to the caller,
 * the sender's for `OUT`, the recipient's for `IN`).
 */
data class SeenTransfer(val transfer: Transfer, val side: UserId) {
    val isOutgoing: Boolean get() = side == transfer.senderId
}

/** What an update brings besides the state; null leaves the stored value as it is. */
data class TransferChange(
    val circleTxId: String? = null,
    val txHash: String? = null,
    val failureReason: String? = null,
    val at: Instant,
)

/**
 * One line of a user's history: the sender's `OUT` row is written with the transfer, the recipient's `IN` row from
 * Circle's inbound notification. Never logged.
 */
data class WalletTransaction(
    val id: UUID,
    val userId: UserId,
    val direction: Direction,
    val state: TransferState,
    val amount: Money,
    val blockchain: String,
    val counterpartyId: UserId?,
    val counterpartyAddress: String?,
    val transferId: TransferId?,
    val circleTxId: String?,
    val txHash: String?,
    val chatId: ChatId?,
    val origin: TransferOrigin,
    val occurredAt: Instant,
) {
    override fun toString(): String = "WalletTransaction($id, $direction, $state)"
}

/**
 * W4's request in the domain's words. [senderId] is the caller, who signs; [walletId] is the wallet it leaves (null:
 * their own), and a group wallet needs the admins' approved [payoutRequestId]. [idempotencyKey] is derived from the
 * request's `Idempotency-Key`.
 */
data class TransferCommand(
    val senderId: UserId,
    val blockchain: String,
    val payee: Payee,
    val amount: Money,
    val chatId: ChatId?,
    val proposalId: UUID?,
    val idempotencyKey: UUID,
    val walletId: WalletId? = null,
    val payoutRequestId: UUID? = null,
)

/**
 * W4's answer: the transfer, and the challenge to sign while it awaits a signature. [challengeId] is null once the
 * transfer is past that (a repeated request for a transfer already signed, or failed).
 */
data class PreparedTransfer(val transfer: Transfer, val challengeId: String?, val sdk: SdkSession?)

/** Whether W9's person can be paid, on [blockchains] (the chains they can receive on now). */
enum class RecipientStatus { CAN_RECEIVE, NO_WALLET, NO_WALLET_ON_CHAIN }

data class RecipientQuery(
    val senderId: UserId,
    val recipientId: UserId,
    val blockchain: String?,
    val chatId: ChatId?,
)

data class RecipientCheck(val userId: UserId, val status: RecipientStatus, val blockchains: List<String>)

/** W10's answer. */
enum class ReminderOutcome {
    /** The contact was told, by frame and push. */
    SENT,

    /** Already told by this sender in the last 24 hours. */
    ALREADY_SENT,

    /** The contact has a wallet now: pay them instead. */
    HAS_WALLET,
}

/** W11's answer: whether [address] can be paid on [blockchain]. */
data class AddressCheck(val blockchain: String, val address: String, val valid: Boolean) {
    override fun toString(): String = "AddressCheck($blockchain, ***, $valid)"
}

/**
 * The transfer limits, in USDC minor units, from configuration until they are decided.
 * TODO(decision: limits, KYC and licensing) per-payment and daily limits for unverified and verified accounts.
 */
data class TransferLimits(val perPaymentMinor: Long, val dailyMinor: Long)
