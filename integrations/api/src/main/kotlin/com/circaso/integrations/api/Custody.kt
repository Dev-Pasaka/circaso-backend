package com.circaso.integrations.api

import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/**
 * User-controlled stablecoin wallets (Circle today). The backend only creates challenges: every action that signs is
 * run by the user in the vendor's SDK on the phone, with their PIN. Account types, token ids and the vendor's user
 * tokens are this contract's business; the wallet passes our `circleUserId` and stores what comes back.
 *
 * A user's wallet can live on several chains (decision 2026-10-28): one vendor wallet per chain, each with its own
 * address. Which chains are supported, and which assets are offered on each, is server configuration ([supported]).
 */
interface StablecoinCustody {
    /** The chains a wallet may be on and the assets offered on each; a new wallet starts on the default one. */
    val supported: SupportedChains

    /** Registers [circleUserId] with the vendor; a user that already exists is success. */
    suspend fun createUser(circleUserId: String): Resource<Unit>

    /**
     * The challenge that sets the user's PIN and creates their wallet on the default chain, and on every other
     * receiving chain that can share that challenge ([creationGroup]; decision 2026-10-30). A user who has already
     * done it gets success with a [Challenge] that has nothing to sign ([Challenge.needsSignature] false): read
     * [wallets] instead.
     */
    suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge>

    /**
     * The challenge that adds a wallet on one more supported [blockchain] for a user who already set their PIN; the
     * user signs it with the PIN, the vendor creates the wallet, and it then shows in [wallets]. A chain that isn't
     * supported is `Rejected`.
     */
    suspend fun addWallet(
        circleUserId: String,
        blockchain: String,
        idempotencyKey: UUID,
    ): Resource<Challenge>

    /**
     * Chains in one group can be created in one challenge (one PIN entry). Circle can't mix account types in one
     * request, so its groups are the account types (SCA for the EVM chains, EOA for Solana).
     */
    fun creationGroup(blockchain: String): String = blockchain

    /** One challenge that adds wallets on all [blockchains], which must share a [creationGroup]. */
    suspend fun addWallets(
        circleUserId: String,
        blockchains: List<String>,
        idempotencyKey: UUID,
    ): Resource<Challenge> =
        if (blockchains.size == 1) {
            addWallet(circleUserId, blockchains.single(), idempotencyKey)
        } else {
            Resource.error(IntegrationError.Rejected("These chains can't be added in one step."))
        }

    /** What the phone's SDK needs to run a challenge; reused until 5 minutes before it expires. */
    suspend fun sdkSession(circleUserId: String): Resource<SdkSession>

    /** The user's wallets, one per chain they are on. */
    suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>>

    /** The [asset] balance of one wallet, zero if it holds none. Only USDC is offered. */
    suspend fun balance(
        circleUserId: String,
        walletId: String,
        asset: Asset,
    ): Resource<Money>

    /**
     * Every asset offered on [blockchain], as held by the wallet [walletId] on that chain: one line per offered asset,
     * zero for one it doesn't hold, and nothing for tokens that aren't offered (anyone can send a wallet any token).
     */
    suspend fun balances(
        circleUserId: String,
        walletId: String,
        blockchain: String,
    ): Resource<List<AssetBalance>>

    /** The challenge the user signs to send [TransferRequest.amount]; nothing moves until they do. */
    suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge>

    /**
     * Whether one challenge on [blockchain] can pay several addresses at once, all or nothing (spaces-plan §7b, fee
     * spike: a batch on the sender's own smart-contract wallet on EVM chains; Solana can't). Only then can a payment
     * carry Circaso's fee in the same signature.
     */
    fun canSplit(blockchain: String): Boolean = false

    /**
     * What the vendor expects the network to charge for [request] right now (the gas Circaso sponsors), in the chain's
     * native coin. Nothing is created. `Rejected` where [canSplit] is false.
     */
    suspend fun estimateSplit(circleUserId: String, request: SplitTransferRequest): Resource<NetworkCost> =
        Resource.error(IntegrationError.Rejected("${request.blockchain} can't pay several addresses at once."))

    /**
     * The one challenge the user signs to pay every leg of [request] together: all legs move, or none. `Rejected`
     * where [canSplit] is false.
     */
    suspend fun prepareSplitTransfer(circleUserId: String, request: SplitTransferRequest): Resource<Challenge> =
        Resource.error(IntegrationError.Rejected("${request.blockchain} can't pay several addresses at once."))

    suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction>

    /**
     * The user's transactions on every wallet they hold, in and out, created at or after [since], newest first and at
     * most [limit] of them. Only offered assets are listed, as [transaction] gives them. For the wallet's
     * reconciliation jobs, which catch what a lost webhook never told us.
     */
    suspend fun transactions(
        circleUserId: String,
        since: Instant,
        limit: Int,
    ): Resource<List<CustodyTransaction>>

    /** Whether [address] is a valid address on [blockchain]. */
    suspend fun validateAddress(blockchain: String, address: String): Resource<Boolean>

    suspend fun pinChallenge(
        circleUserId: String,
        kind: PinChallenge,
        idempotencyKey: UUID,
    ): Resource<Challenge>
}

/**
 * The supported chains, from configuration, and the one a new wallet starts on. Chain names are the vendor's
 * (`BASE-SEPOLIA`, `SOL-DEVNET`): they are what the wallet stores and shows.
 */
data class SupportedChains(val default: String, val chains: List<CustodyChain>) {
    init {
        val start = chains.firstOrNull { it.blockchain == default }
        require(start != null) { "The default chain $default must be one of the chains" }
        require(start.receiveEnabled) { "The default chain $default can't have receiving disabled" }
    }

    fun find(blockchain: String): CustodyChain? = chains.firstOrNull { it.blockchain == blockchain }
}

/**
 * A supported chain and the assets offered on it. A chain is never taken out of the configuration while any wallet
 * holds funds on it (decision 2026-10-28); it is retired by turning [receiveEnabled] off instead: it stays readable
 * (balances, the deletion guard), but no wallet is added on it and nobody is paid on it, so it only empties.
 */
data class CustodyChain(val blockchain: String, val assets: List<CustodyAsset>, val receiveEnabled: Boolean = true)

/**
 * An asset offered on a chain. [tokenAddress] is its contract (an EVM address, a Solana mint), empty for the chain's
 * native coin. [usdPegged] marks a stablecoin worth one US dollar (USDC), the only kind given a fiat value for now.
 */
data class CustodyAsset(
    val symbol: String,
    val tokenAddress: String,
    val decimals: Int,
    val usdPegged: Boolean,
)

/**
 * [amountMinor] in the asset's smallest unit. A big integer: an 18-decimal token passes `Long` at about 9 whole
 * coins. The string form hides the amount, which never reaches a log line.
 */
data class AssetBalance(val asset: CustodyAsset, val amountMinor: BigInteger) {
    override fun toString(): String = "AssetBalance(${asset.symbol}, ***)"
}

/**
 * A challenge for the user to run in the SDK, with the session values it runs under. [StablecoinCustody.createWallet]
 * for a user who has already set their PIN and has a wallet returns one with an empty [challengeId]: there is nothing
 * to sign, and the caller reads [StablecoinCustody.wallets] instead. Every other call returns a real challenge.
 */
data class Challenge(val challengeId: String, val sdk: SdkSession) {
    /** False when the vendor had nothing for the user to sign (the wallet already exists). */
    val needsSignature: Boolean get() = challengeId.isNotEmpty()
}

/** The phone SDK's values. [userToken] and [encryptionKey] go to the app only, never to a log or the agent token. */
data class SdkSession(
    val appId: String,
    val userToken: String,
    val encryptionKey: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "SdkSession(appId=$appId, userToken=***, encryptionKey=***, expiresAt=$expiresAt)"
}

data class CustodyWallet(
    val walletId: String,
    val address: String,
    val blockchain: String,
    val accountType: String,
    val state: CustodyWalletState,
)

enum class CustodyWalletState { LIVE, FROZEN }

data class TransferRequest(
    /** The chain of the sending wallet; the token is named by its contract there. */
    val blockchain: String,
    val walletId: String,
    val destinationAddress: String,
    val amount: Money,
    val idempotencyKey: UUID,
    /** Ours (the wallet's `transferId`), echoed back on every update so the update finds its transfer. */
    val refId: String,
)

/** One leg of a split payment: [amount] to [destinationAddress]. */
data class PaymentLeg(val destinationAddress: String, val amount: Money) {
    override fun toString(): String = "PaymentLeg(***)"
}

/**
 * Several payments from one wallet in one challenge (the payee and Circaso's fee wallet): [walletId] is the sending
 * wallet and [walletAddress] its address on [blockchain]. [refId] is ours, echoed back as for [TransferRequest].
 */
data class SplitTransferRequest(
    val blockchain: String,
    val walletId: String,
    val walletAddress: String,
    val legs: List<PaymentLeg>,
    val idempotencyKey: UUID,
    val refId: String,
) {
    override fun toString(): String = "SplitTransferRequest($blockchain, ${legs.size} legs)"
}

/** What a network charges, in its native coin ([symbol]: ETH, POL, SOL), as a decimal of whole coins. */
data class NetworkCost(val amount: BigDecimal, val symbol: String)

enum class PinChallenge {
    /** The user changes the PIN, typing the current one. */
    CHANGE,

    /** The user answers the security questions and sets a new PIN. */
    RESTORE,
}

/** One transaction as the vendor reports it, in our words. Empty text stands for a value the vendor hasn't given. */
data class CustodyTransaction(
    val circleTxId: String,
    val circleUserId: String,
    val walletId: String,
    val blockchain: String,
    val direction: CustodyDirection,
    val state: CustodyTransactionState,
    val amount: Money,
    val sourceAddress: String,
    val destinationAddress: String,
    val refId: String,
    val txHash: String,
    val failureReason: String,
    /** When the vendor last changed it; updates can arrive out of order, so only a newer one applies. */
    val updatedAt: Instant,
    /** What the network charged, in the chain's native coin as a decimal ("0.00000133"); empty until it is known. */
    val networkFee: String = "",
)

enum class CustodyDirection { IN, OUT }

enum class CustodyTransactionState {
    /** Signed and on its way (vendor: initiated, cleared, queued, sent). */
    SUBMITTED,

    /** The vendor reports it stuck; shown as submitted and handled by the stuck-transfer job. */
    STUCK,

    /** On chain (vendor: confirmed, complete). */
    CONFIRMED,

    /** Failed, denied or cancelled. */
    FAILED,
}

/**
 * Told about every transaction update from the vendor's webhook. Implemented by the wallet and wired in `app`;
 * integrations never depend on the wallet. An error makes the webhook answer non-200, so the vendor retries.
 */
interface CustodyNotifications {
    suspend fun onTransaction(update: CustodyTransaction): Resource<Unit>
}
