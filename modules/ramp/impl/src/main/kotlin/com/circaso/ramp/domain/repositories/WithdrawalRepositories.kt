package com.circaso.ramp.domain.repositories

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalDestination
import com.circaso.shared.Network
import java.time.Instant
import java.util.UUID

/** `ramp.withdrawal_destinations`: one live row per merchant, network and address (`withdrawal_destinations_live_key`). */
interface WithdrawalDestinationRepository {
    /** False when the merchant already has that address live on the network. */
    suspend fun insert(destination: WithdrawalDestination): Boolean

    suspend fun find(destinationId: UUID): WithdrawalDestination?

    /** The merchant's live addresses on [network], newest first. */
    suspend fun live(merchantId: MerchantId, network: Network): List<WithdrawalDestination>

    /** Every address the merchant ever saved on [network], removed ones too, newest first (staff). */
    suspend fun all(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
    ): List<WithdrawalDestination>

    /** Marks a live address removed; true for the caller that removed it. */
    suspend fun remove(destinationId: UUID, at: Instant): Boolean
}

/**
 * `ramp.withdrawals`. Moves are `WHERE state IN (…)`, so the job and a second process can never both send, complete or
 * fail the same withdrawal; `withdrawals_idempotency_key` makes a repeated request the same row.
 */
interface WithdrawalRepository {
    /** False when the merchant already made a withdrawal with the same idempotency key. */
    suspend fun insert(withdrawal: Withdrawal): Boolean

    suspend fun find(withdrawalId: UUID): Withdrawal?

    suspend fun byKey(merchantId: MerchantId, idempotencyKey: String): Withdrawal?

    /** The merchant's withdrawals on [network], newest first, those before [before] (an id) when given. */
    suspend fun page(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        before: UUID?,
    ): List<Withdrawal>

    /** `REQUESTED` and `SENT` withdrawals on [network], oldest first: what the ops job sends and follows. */
    suspend fun open(network: Network, limit: Int): List<Withdrawal>

    /** What the merchant asked to withdraw on [network] since [since], failed ones left out. */
    suspend fun totalSince(
        merchantId: MerchantId,
        network: Network,
        since: Instant,
    ): Long

    /** `REQUESTED → SENT` with the custodian's transaction; true when it moved. */
    suspend fun sent(
        withdrawalId: UUID,
        custodyTxId: String,
        at: Instant,
    ): Boolean

    /** Records the chain's hash on a `SENT` withdrawal as soon as the custodian has it. */
    suspend fun noteHash(
        withdrawalId: UUID,
        txHash: String,
        at: Instant,
    )

    /** `SENT → COMPLETED`; true when it moved. */
    suspend fun complete(
        withdrawalId: UUID,
        txHash: String?,
        at: Instant,
    ): Boolean

    /** `REQUESTED | SENT → FAILED`; true when it moved. */
    suspend fun fail(
        withdrawalId: UUID,
        reason: String,
        at: Instant,
    ): Boolean
}
