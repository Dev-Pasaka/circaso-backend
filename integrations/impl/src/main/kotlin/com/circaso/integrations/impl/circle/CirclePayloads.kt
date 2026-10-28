package com.circaso.integrations.impl.circle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

@Serializable
internal data class PinBody(override val idempotencyKey: String) : CircleWrite

@Serializable
internal data class ChallengeData(val challengeId: String = "")

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

/** A token; a native coin (ETH) has no contract address. */
@Serializable
internal data class TokenData(val id: String = "", val blockchain: String = "", val tokenAddress: String = "")

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

// Transactions, from the API and from webhooks (same shape)

@Serializable
internal data class TransactionData(val transaction: TransactionPayload? = null)

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
)

// Notifications

/** What every notification starts with; read first, so the body is decoded by its type. */
@Serializable
internal data class NotificationHeader(val notificationId: String = "", val notificationType: String = "")

@Serializable
internal data class TransactionNotification(val notification: TransactionPayload? = null)

@Serializable
internal data class NotificationKeyData(val publicKey: String = "")
