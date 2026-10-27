package com.circaso.integrations.api

import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import java.time.Instant
import java.util.UUID

/**
 * User-controlled stablecoin wallets (Circle today). The backend only creates challenges: every action that signs is
 * run by the user in the vendor's SDK on the phone, with their PIN. The chain, account type, token and the vendor's
 * user tokens are this contract's business; the wallet passes our `circleUserId` and stores what comes back.
 */
interface StablecoinCustody {
    /** Registers [circleUserId] with the vendor; a user that already exists is success. */
    suspend fun createUser(circleUserId: String): Resource<Unit>

    /**
     * The challenge that sets the user's PIN and creates their wallet on the configured chain. A user who has already
     * done it gets success with a [Challenge] that has nothing to sign ([Challenge.needsSignature] false): read
     * [wallets] instead.
     */
    suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge>

    /** What the phone's SDK needs to run a challenge; reused until 5 minutes before it expires. */
    suspend fun sdkSession(circleUserId: String): Resource<SdkSession>

    suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>>

    /** The [asset] balance of one wallet, zero if it holds none. Only USDC is offered. */
    suspend fun balance(
        circleUserId: String,
        walletId: String,
        asset: Asset,
    ): Resource<Money>

    /** The challenge the user signs to send [TransferRequest.amount]; nothing moves until they do. */
    suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge>

    suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction>

    /** Whether [address] is a valid address on the configured chain. */
    suspend fun validateAddress(address: String): Resource<Boolean>

    suspend fun pinChallenge(
        circleUserId: String,
        kind: PinChallenge,
        idempotencyKey: UUID,
    ): Resource<Challenge>
}

/** A challenge for the user to run in the SDK, with the session values it runs under. */
data class Challenge(val challengeId: String, val sdk: SdkSession) {
    // TODO(decision: wallet.md says "user already initialized" (155106) is success "with the existing wallet", but
    //  createWallet returns a Challenge; until the contract says otherwise it is a Challenge with an empty id.)

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
    val walletId: String,
    val destinationAddress: String,
    val amount: Money,
    val idempotencyKey: UUID,
    /** Ours (the wallet's `transferId`), echoed back on every update so the update finds its transfer. */
    val refId: String,
)

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
