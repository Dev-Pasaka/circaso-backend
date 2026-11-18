package com.circaso.integrations.api

import com.circaso.shared.ErrorKind
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

/**
 * Wallets Circaso holds itself (decision 2026-11-18, fee wallets): the provider signs for them with a secret only
 * Circaso's server has, no person and no PIN. Bound in the ops process only; the app's processes never hold the
 * signing secret and never see this contract. Every call names its network: each has its own vendor account.
 *
 * Reading (finding wallets, balances, a transaction, an estimate) needs the account's API key alone; creating a
 * wallet set, creating wallets and sending need the signing secret as well ([canSign]).
 */
interface TreasuryCustody {
    /** What this server can do on [network]. */
    fun access(network: Network): TreasuryAccess

    fun canRead(network: Network): Boolean = access(network) != TreasuryAccess.NO_ACCOUNT

    fun canSign(network: Network): Boolean = access(network) == TreasuryAccess.FULL

    /** Creates a wallet set named [name]; its id. The same [idempotencyKey] answers the same set. */
    suspend fun createWalletSet(
        network: Network,
        name: String,
        idempotencyKey: UUID,
    ): Resource<String>

    /**
     * Creates one externally owned wallet per chain of [blockchains] in [walletSetId], all under [refId] (on EVM
     * chains wallets that share a set and a `refId` share one address). The same [idempotencyKey] answers the same
     * wallets.
     */
    suspend fun createWallets(
        network: Network,
        request: TreasuryWalletRequest,
    ): Resource<List<TreasuryWallet>>

    /**
     * The wallet at [walletId]'s address on another EVM chain, created if it isn't there yet; asking again answers the
     * same wallet.
     */
    suspend fun deriveWallet(
        network: Network,
        walletId: String,
        blockchain: String,
        name: String,
        refId: String,
    ): Resource<TreasuryWallet>

    /** The account's own wallets, narrowed by any of [address], [blockchain] and [refId]; at most one page (50). */
    suspend fun findWallets(
        network: Network,
        address: String? = null,
        blockchain: String? = null,
        refId: String? = null,
    ): Resource<List<TreasuryWallet>>

    /** Every coin the wallet holds, the chain's own coin included. */
    suspend fun balances(network: Network, walletId: String): Resource<List<TreasuryBalance>>

    /** What the network would charge for [transfer] now, in the chain's own coin (the buffered upper bound). */
    suspend fun estimateTransfer(network: Network, transfer: TreasuryTransfer): Resource<BigDecimal>

    /**
     * Sends [transfer]. The same [TreasuryTransfer.idempotencyKey] never sends twice: asked again it answers the first
     * transaction. An `Unavailable` error means the outcome is unknown (ask again with the same key); any other error
     * means nothing was sent.
     */
    suspend fun send(network: Network, transfer: TreasuryTransfer): Resource<TreasuryTransaction>

    suspend fun transaction(network: Network, transactionId: String): Resource<TreasuryTransaction>
}

enum class TreasuryAccess {
    /** No vendor account for the network on this server (its API key isn't set). */
    NO_ACCOUNT,

    /** The API key but no signing secret: wallets and balances can be read, nothing can be created or sent. */
    READ_ONLY,

    FULL,
}

data class TreasuryWalletRequest(
    val walletSetId: String,
    val blockchains: List<String>,
    val name: String,
    val refId: String,
    val idempotencyKey: UUID,
)

/** One of Circaso's own wallets at the provider. [developerOwned] false is a user's wallet in the same account. */
data class TreasuryWallet(
    val walletId: String,
    val walletSetId: String,
    val blockchain: String,
    val address: String,
    val accountType: String,
    val refId: String,
    val developerOwned: Boolean,
)

/**
 * A coin a treasury wallet holds. [tokenId] is the provider's id for the coin, which a transfer names it by;
 * [tokenAddress] is empty for the chain's own coin ([native]).
 */
data class TreasuryBalance(
    val tokenId: String,
    val symbol: String,
    val tokenAddress: String,
    val decimals: Int,
    val native: Boolean,
    val amountMinor: BigInteger,
) {
    override fun toString(): String = "TreasuryBalance($symbol, ***)"
}

data class TreasuryTransfer(
    val walletId: String,
    val blockchain: String,
    val tokenId: String,
    val decimals: Int,
    val amountMinor: BigInteger,
    val destinationAddress: String,
    val refId: String,
    val idempotencyKey: UUID,
) {
    override fun toString(): String = "TreasuryTransfer($blockchain, ***)"
}

data class TreasuryTransaction(
    val transactionId: String,
    val state: TreasuryTransactionState,
    val txHash: String,
    /** The provider's reason when [state] is `FAILED`; never a secret. */
    val failureReason: String,
)

enum class TreasuryTransactionState {
    /** Accepted by the provider; on its way to, or on, the chain. */
    IN_FLIGHT,
    CONFIRMED,
    FAILED,
}

/**
 * The provider answered and refused, with what it said in a form safe to show staff and keep (HTTP status and the
 * provider's own error code; never a body, a key or a secret).
 */
class TreasuryRefusal(val status: Int, val providerCode: Int?) :
    IntegrationError(
        "integrations.rejected",
        "The custody provider refused the request (HTTP $status${providerCode?.let { ", code $it" }.orEmpty()}).",
        ErrorKind.INVALID,
    )
