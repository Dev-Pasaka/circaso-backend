package com.circaso.wallet.api

import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

// The wallet's staff contract for Circaso's fee wallets (decision 2026-11-18), for the ops module behind the ops
// gateway only: the wallets and what they hold, where collected fees may be withdrawn to, and the withdrawals. The
// wallets are Circle developer-controlled wallets the ops server creates itself at start-up; there is nothing to
// create here. Amounts are minor units.

/** The staff member acting, with their email as it is now (kept on what they add, for the lists). */
data class OpsFeeStaff(val staffId: UUID, val email: String)

data class OpsFeeBalance(val symbol: String, val amountMinor: BigInteger, val decimals: Int)

/**
 * One chain's fee wallet. [balancesKnown] false: Circle couldn't be asked, so [balances] is empty and [nativeBalance]
 * null (not a zero). [withdrawable] false comes with [unavailableReason].
 */
data class OpsFeeWallet(
    val blockchain: String,
    val address: String,
    val balances: List<OpsFeeBalance>,
    /** The chain's own coin, which pays a withdrawal's network fee. */
    val nativeBalance: OpsFeeBalance?,
    val balancesKnown: Boolean,
    val withdrawable: Boolean,
    val unavailableReason: String?,
)

/** A network's fee wallets. [status] is `READY`, `NOT_CREATED` or `FAILED`; [reason] never holds a secret. */
data class OpsFeeWallets(
    val network: Network,
    val status: String,
    val reason: String?,
    val wallets: List<OpsFeeWallet>,
    /** How long a new destination waits before it can be withdrawn to (a database setting). */
    val destinationDelayHours: Int,
)

data class OpsFeeDestination(
    val id: UUID,
    val network: Network,
    val blockchain: String,
    val address: String,
    val label: String,
    /** The email of the staff member who added it. */
    val createdBy: String,
    val createdById: UUID,
    val createdAt: Instant,
    /** From when it may be withdrawn to. */
    val usableAt: Instant,
)

/** [state] is `PENDING`, `SENT`, `CONFIRMED` or `FAILED`. */
data class OpsFeeWithdrawal(
    val id: UUID,
    val network: Network,
    val blockchain: String,
    val asset: String,
    val amountMinor: BigInteger,
    val decimals: Int,
    val destinationId: UUID,
    val destinationAddress: String,
    val destinationLabel: String,
    val state: String,
    val txHash: String?,
    val reason: String,
    val failureReason: String?,
    /** The email of the staff member who asked for it. */
    val by: String,
    val byId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class OpsFeeWithdrawalPage(val items: List<OpsFeeWithdrawal>, val nextCursor: String?)

data class OpsNewFeeDestination(
    val network: Network,
    val blockchain: String,
    val address: String,
    val label: String,
)

data class OpsNewFeeWithdrawal(
    val network: Network,
    val blockchain: String,
    val asset: String,
    val amountMinor: BigInteger,
    val destinationId: UUID,
    val reason: String,
    /** The request's `Idempotency-Key`: the same key from the same staff member is the same withdrawal. */
    val idempotencyKey: String,
)

/**
 * Staff's view of the fee wallets. Errors: `fees.invalid` (400, the field named), `fees.destination_not_found` (404),
 * `fees.destination_exists`, `fees.destination_not_ready`, `fees.insufficient_balance`, `fees.insufficient_gas`,
 * `fees.withdrawals_unavailable`, `fees.withdrawal_refused` (409), `fees.destination_is_fee_wallet`,
 * `fees.asset_unsupported` (400), `fees.custody_unavailable` (502).
 */
interface FeeWalletAdmin {
    suspend fun wallets(network: Network): OpsFeeWallets

    /** The destinations in use, newest first; [network] null for both. */
    suspend fun destinations(network: Network?): List<OpsFeeDestination>

    /** One destination, removed or not; null when there never was one. */
    suspend fun destination(id: UUID): OpsFeeDestination?

    suspend fun addDestination(destination: OpsNewFeeDestination, by: OpsFeeStaff): Resource<OpsFeeDestination>

    /** Removes a destination; the destination as it was. Withdrawals already made to it keep naming it. */
    suspend fun removeDestination(id: UUID, by: OpsFeeStaff): Resource<OpsFeeDestination>

    /** Newest first; [cursor] is the last page's `nextCursor`. A cursor that isn't one answers `request.malformed`. */
    suspend fun withdrawals(
        network: Network?,
        cursor: String?,
        limit: Int,
    ): Resource<OpsFeeWithdrawalPage>

    /**
     * Withdraws collected fees to a destination. The answer is the withdrawal as it stands: `SENT` when Circle took
     * it, `PENDING` when Circle couldn't be reached (it is sent as soon as it can be, once). A refusal is an error and
     * leaves a `FAILED` withdrawal in the list.
     */
    suspend fun withdraw(withdrawal: OpsNewFeeWithdrawal, by: OpsFeeStaff): Resource<OpsFeeWithdrawal>
}
