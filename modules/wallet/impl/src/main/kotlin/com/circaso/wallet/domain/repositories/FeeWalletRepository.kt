package com.circaso.wallet.domain.repositories

import com.circaso.shared.Network
import com.circaso.wallet.domain.models.FeeDestination
import com.circaso.wallet.domain.models.FeeWallet
import com.circaso.wallet.domain.models.FeeWalletNetwork
import com.circaso.wallet.domain.models.FeeWithdrawal
import com.circaso.wallet.domain.models.WithdrawalChange
import com.circaso.wallet.domain.models.WithdrawalState
import java.time.Instant
import java.util.UUID

/** `fee_wallet_networks`, `fee_wallets`, `fee_wallet_settings` and the fee addresses in `chain_fees` (V13). */
interface FeeWalletRepository {
    suspend fun network(network: Network): FeeWalletNetwork?

    /** Writes [row]; a wallet set id already stored is kept when [row] has none. */
    suspend fun saveNetwork(row: FeeWalletNetwork)

    suspend fun wallets(network: Network): List<FeeWallet>

    /**
     * Records [wallet] for its chain, in one transaction with the chain's fee address:
     * - no row yet: the row is inserted, and `chain_fees.fee_address` is set to its address **only when the chain has
     *   none** (an address already there is never replaced);
     * - a row with the same address and no Circle wallet id: it takes [wallet]'s id, set and origin;
     * - anything else (another address, or a wallet id already there): nothing changes.
     *
     * The row as it is afterwards.
     */
    suspend fun record(wallet: FeeWallet): FeeWallet

    /** Every chain's fee address as the table has it now (not the few-seconds-old snapshot), blank ones left out. */
    suspend fun feeAddresses(): Map<String, String>

    /** `fee_wallet_settings.destination_delay_hours`; 24 when the row is missing. */
    suspend fun destinationDelayHours(): Int
}

/** `fee_destinations` (V13). */
interface FeeDestinationRepository {
    /** The destinations in use (not removed), newest first; [network] null for both. */
    suspend fun live(network: Network?): List<FeeDestination>

    suspend fun find(id: UUID): FeeDestination?

    /** False when the chain already has a live destination at that address. */
    suspend fun add(destination: FeeDestination): Boolean

    /** Marks it removed; false when it was already, or never existed. */
    suspend fun remove(
        id: UUID,
        by: UUID,
        at: Instant,
    ): Boolean
}

/** `fee_withdrawals` (V13). */
interface FeeWithdrawalRepository {
    /** Inserts [withdrawal]; false when this staff member already used its idempotency key. */
    suspend fun insert(withdrawal: FeeWithdrawal): Boolean

    suspend fun byIdempotencyKey(requestedBy: UUID, idempotencyKey: String): FeeWithdrawal?

    suspend fun find(id: UUID): FeeWithdrawal?

    /** Newest first, those before [before] when given; [network] null for both. */
    suspend fun page(
        network: Network?,
        before: UUID?,
        limit: Int,
    ): List<FeeWithdrawal>

    /** `PENDING` and `SENT`, oldest first. */
    suspend fun open(limit: Int): List<FeeWithdrawal>

    /** The open withdrawals from [blockchain]'s fee wallet: what is promised and hasn't left the balance yet. */
    suspend fun openOn(blockchain: String): List<FeeWithdrawal>

    /**
     * Moves [id] from [from] to [to] with [change]; false when it wasn't in [from] any more (someone else moved it).
     * Callers check [WithdrawalState.mayBecome] first; the database refuses a `SENT` row without a Circle id.
     */
    suspend fun move(
        id: UUID,
        from: WithdrawalState,
        to: WithdrawalState,
        change: WithdrawalChange,
        at: Instant,
    ): Boolean

    /** Records the chain hash of a `SENT` withdrawal once Circle knows it. */
    suspend fun noteHash(
        id: UUID,
        txHash: String,
        at: Instant,
    )
}
