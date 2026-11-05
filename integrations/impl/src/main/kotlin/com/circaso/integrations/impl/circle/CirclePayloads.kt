package com.circaso.integrations.impl.circle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// Circle's request and response bodies, as Circle names them (developers.circle.com/api-reference/wallets). They never
// leave this package: CircleCustody and CircleWebhook turn them into the contract's types. Answers are read leniently
// (unknown fields ignored, a null where we expect text read as empty), since Circle adds fields without notice.

/** How every Circle body is read and written. */
internal val circleJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

/** A POST or PUT body: Circle takes the idempotency key in the body, and the shared client reads it for the header. */
internal interface CircleWrite {
    val idempotencyKey: String
}

/** Every answer is wrapped in `data`. */
@Serializable
internal data class CircleEnvelope<T>(val data: T? = null)

/** A refusal: Circle's own code (`155106`) and a message we never log. */
@Serializable
internal data class CircleErrorBody(val code: Int? = null)

// Users and tokens

@Serializable
internal data class CreateUserBody(val userId: String, override val idempotencyKey: String) : CircleWrite

@Serializable
internal data class CreatedUserData(val id: String = "")

@Serializable
internal data class UserTokenBody(val userId: String, override val idempotencyKey: String) : CircleWrite

@Serializable
internal data class UserTokenData(val userToken: String = "", val encryptionKey: String = "")

/** The only claim of Circle's user token we read: when it expires, in epoch seconds. */
@Serializable
internal data class UserTokenClaims(val exp: Long? = null)

// Challenges

@Serializable
internal data class InitializeBody(
    override val idempotencyKey: String,
    val accountType: String,
    val blockchains: List<String>,
) : CircleWrite

/** `POST /v1/w3s/user/wallets`: one more wallet, on [blockchains], for a user who has already set the PIN. */
@Serializable
internal data class AddWalletsBody(
    override val idempotencyKey: String,
    val accountType: String,
    val blockchains: List<String>,
) : CircleWrite

@Serializable
internal data class TransferBody(
    override val idempotencyKey: String,
    val walletId: String,
    val destinationAddress: String,
    val amounts: List<String>,
    val tokenAddress: String,
    val blockchain: String,
    val feeLevel: String,
    val refId: String,
) : CircleWrite

/**
 * `POST /v1/w3s/user/transactions/contractExecution`: a call the user signs. For a split payment the call is
 * `executeBatch((address,uint256,bytes)[])` on the sending smart-contract wallet itself ([contractAddress] is its own
 * address), each entry a USDC `transfer` (fee spike, 2026-11-03).
 */
@Serializable
internal data class ContractExecutionBody(
    override val idempotencyKey: String,
    val walletId: String,
    val contractAddress: String,
    val abiFunctionSignature: String,
    val abiParameters: List<List<List<String>>>,
    val feeLevel: String,
    val refId: String,
) : CircleWrite

/** `POST /v1/w3s/transactions/contractExecution/estimateFee`: the same call, estimated as the user; nothing is made. */
@Serializable
internal data class EstimateContractBody(
    val walletId: String,
    val contractAddress: String,
    val abiFunctionSignature: String,
    val abiParameters: List<List<List<String>>>,
)

/** The estimate per fee level; `networkFee` is in the chain's native coin and is the buffered upper bound. */
@Serializable
internal data class FeeEstimateData(
    val low: FeeLevelData? = null,
    val medium: FeeLevelData? = null,
    val high: FeeLevelData? = null,
)

@Serializable
internal data class FeeLevelData(val networkFee: String = "", val gasLimit: String = "")

@Serializable
internal data class PinBody(override val idempotencyKey: String) : CircleWrite

@Serializable
internal data class ChallengeData(val challengeId: String = "")

/** `GET /v1/w3s/user/challenges/{id}`: `{"challenge": {"id", "type", "status", ...}}`. */
@Serializable
internal data class ChallengeStatusData(val challenge: ChallengeStatus? = null)

@Serializable
internal data class ChallengeStatus(val id: String = "", val type: String = "", val status: String = "")

/** A challenge the user can still sign (Circle: `PENDING`, `IN_PROGRESS`; done: `COMPLETE`, `FAILED`, `EXPIRED`). */
internal val OPEN_CHALLENGE = setOf("PENDING", "IN_PROGRESS")

// Wallets and balances

@Serializable
internal data class WalletsData(val wallets: List<WalletData> = emptyList())

@Serializable
internal data class WalletData(
    val id: String = "",
    val address: String = "",
    val blockchain: String = "",
    val accountType: String = "",
    val state: String = "",
)

@Serializable
internal data class BalancesData(val tokenBalances: List<TokenBalanceData> = emptyList())

@Serializable
internal data class TokenBalanceData(val token: TokenData = TokenData(), val amount: String = "")

/**
 * A token; a native coin (ETH) has no contract address. [symbol] and [decimals] describe a token we don't offer, so
 * the wallet can still show it (Circle names a testnet's native coin `ETH-SEPOLIA`).
 */
@Serializable
internal data class TokenData(
    val id: String = "",
    val blockchain: String = "",
    val tokenAddress: String = "",
    val symbol: String = "",
    val decimals: Int? = null,
)

@Serializable
internal data class TokenLookupData(val token: TokenData? = null)

// Addresses

@Serializable
internal data class ValidateAddressBody(
    override val idempotencyKey: String,
    val blockchain: String,
    val address: String,
) : CircleWrite

@Serializable
internal data class ValidateAddressData(val isValid: Boolean = false)

// Transactions, from the API and from webhooks. Mostly the same shape, but not quite: the webhook's body may leave out
// `operation` (and a contract execution never has `tokenId` or `refId`), so nothing here may depend on those.

@Serializable
internal data class TransactionData(val transaction: TransactionPayload? = null)

@Serializable
internal data class TransactionsData(val transactions: List<TransactionPayload> = emptyList())

@Serializable
internal data class TransactionPayload(
    val id: String = "",
    val userId: String = "",
    val walletId: String = "",
    val blockchain: String = "",
    val tokenId: String = "",
    val transactionType: String = "",
    val state: String = "",
    val amounts: List<String> = emptyList(),
    val sourceAddress: String = "",
    val destinationAddress: String = "",
    val refId: String = "",
    val txHash: String = "",
    val errorReason: String = "",
    val createDate: String = "",
    val updateDate: String = "",
    /** `TRANSFER` or `CONTRACT_EXECUTION` (a split payment's batch arrives as one, with no token or amount). */
    val operation: String = "",
    /** What the network charged, in the chain's native coin. */
    val networkFee: String = "",
    /** `ENDUSER` (a user-controlled wallet) or `DEVELOPER` (one of Circaso's own, such as a fee wallet). */
    val custodyType: String = "",
    /** The contract a `CONTRACT_EXECUTION` called (for a split payment, the sending wallet itself). */
    val contractAddress: String = "",
)

// Notifications

/** What every notification starts with; read first, so the body is decoded by its type. */
@Serializable
internal data class NotificationHeader(val notificationId: String = "", val notificationType: String = "")

@Serializable
internal data class TransactionNotification(val notification: TransactionPayload? = null)

/**
 * A `challenges.*` notification's `notification` (developers.circle.com, user-controlled wallets: challenge
 * notification): the challenge id, Circle's user id, the challenge type (`INITIALIZE`, `CREATE_WALLET`, ...) and its
 * outcome, `COMPLETE` or `FAILED`. Circle's pages show the outcome both as `status` and as `state`, so both are read.
 */
@Serializable
internal data class ChallengeNotification(val notification: ChallengePayload? = null)

@Serializable
internal data class ChallengePayload(
    val id: String = "",
    val userId: String = "",
    val type: String = "",
    val status: String = "",
    val state: String = "",
) {
    val outcome: String get() = status.ifEmpty { state }
}

/** Only the field names of a notification we couldn't read, for its log line (names and presence, never values). */
@Serializable
internal data class NotificationFields(val notification: Map<String, JsonElement>? = null)

@Serializable
internal data class NotificationKeyData(val publicKey: String = "")
