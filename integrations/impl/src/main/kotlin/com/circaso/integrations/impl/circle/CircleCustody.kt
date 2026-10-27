package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.Challenge
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyWallet
import com.circaso.integrations.api.CustodyWalletState
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PinChallenge
import com.circaso.integrations.api.SdkSession
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.TransferRequest
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import java.time.Clock
import java.util.UUID

/**
 * Circle user-controlled wallets (developers.circle.com/api-reference/wallets/user-controlled-wallets). [CircleClient]
 * runs on the shared `HttpClients.named("circle")` client (base URL `https://api.circle.com`), so timeouts, retries,
 * the circuit breaker and the vendor metrics come with it. Calls made as the user need a Circle user token, which this
 * class gets and keeps itself ([CircleUserTokens]); if Circle refuses a kept token, it is dropped and the call made
 * once more with a new one. Nothing here logs a token, a key, an address or an amount.
 */
class CircleCustody internal constructor(
    private val client: CircleClient,
    private val settings: CircleSettings,
    clock: Clock,
) : StablecoinCustody {
    constructor(
        http: HttpClient,
        apiKey: String,
        settings: CircleSettings,
        clock: Clock = Clock.systemUTC(),
    ) : this(CircleClient(http, apiKey), settings, clock)

    private val tokens = CircleUserTokens(client, clock)
    private val transactions = CircleTransactions(CircleTokens(client), settings)

    override suspend fun createUser(circleUserId: String): Resource<Unit> {
        val reply =
            client.write<CreateUserBody, CreatedUserData>(
                HttpMethod.Post,
                "/v1/w3s/users",
                "create_user",
                CreateUserBody(circleUserId, freshKey()),
            )
        return when {
            reply is CircleReply.Ok -> Resource.success(Unit)
            reply is CircleReply.Failed && reply.code == CircleErrors.USER_ALREADY_EXISTS -> Resource.success(Unit)
            else -> CircleErrors.failure(reply, "create_user")
        }
    }

    override suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge> {
        val body = InitializeBody(idempotencyKey.toString(), settings.accountType, listOf(settings.blockchain))
        return asUser(circleUserId) { token ->
            client.write<InitializeBody, ChallengeData>(
                HttpMethod.Post,
                "/v1/w3s/user/initialize",
                "initialize_user",
                body,
                token,
            )
        }.flatMapReply { token, reply ->
            if (reply is CircleReply.Failed && reply.code == CircleErrors.USER_ALREADY_INITIALIZED) {
                Resource.success(Challenge("", sessionOf(token)))
            } else {
                challengeOf(token, reply, "initialize_user")
            }
        }
    }

    override suspend fun sdkSession(circleUserId: String): Resource<SdkSession> =
        when (val token = tokens.get(circleUserId)) {
            is Resource.Success -> Resource.success(sessionOf(token.data))
            is Resource.Error -> token
        }

    override suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>> =
        asUser(circleUserId) { token ->
            client.get<WalletsData>("/v1/w3s/wallets", "list_wallets", token)
        }.flatMapReply { _, reply ->
            if (reply !is CircleReply.Ok) return@flatMapReply CircleErrors.failure(reply, "list_wallets")
            Resource.success(reply.data.wallets.map(::walletOf))
        }

    override suspend fun balance(
        circleUserId: String,
        walletId: String,
        asset: Asset,
    ): Resource<Money> {
        if (asset != Asset.USDC) return notOffered(asset)
        return asUser(circleUserId) { token ->
            client.get<BalancesData>("/v1/w3s/wallets/$walletId/balances", "get_balance", token)
        }.flatMapReply { _, reply ->
            if (reply !is CircleReply.Ok) return@flatMapReply CircleErrors.failure(reply, "get_balance")
            usdcBalanceOf(reply.data)
        }
    }

    override suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge> {
        if (request.amount.asset != Asset.USDC) return notOffered(request.amount.asset)
        val body =
            TransferBody(
                idempotencyKey = request.idempotencyKey.toString(),
                walletId = request.walletId,
                destinationAddress = request.destinationAddress,
                amounts = listOf(CircleTransactions.decimalOf(request.amount)),
                tokenAddress = settings.usdcTokenAddress,
                blockchain = settings.blockchain,
                feeLevel = FEE_LEVEL,
                refId = request.refId,
            )
        return asUser(circleUserId) { token ->
            client.write<TransferBody, ChallengeData>(
                HttpMethod.Post,
                "/v1/w3s/user/transactions/transfer",
                "create_transfer",
                body,
                token,
            )
        }.flatMapReply { token, reply -> challengeOf(token, reply, "create_transfer") }
    }

    override suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction> =
        asUser(circleUserId) { token ->
            client.get<TransactionData>("/v1/w3s/transactions/$circleTxId", "get_transaction", token)
        }.flatMapReply { _, reply ->
            if (reply !is CircleReply.Ok) return@flatMapReply CircleErrors.failure(reply, "get_transaction")
            val transaction =
                reply.data.transaction
                    ?: return@flatMapReply CircleErrors.failure(CircleReply.Failed(200, null), "get_transaction")
            offeredOnly(transactions.translate(transaction))
        }

    override suspend fun validateAddress(address: String): Resource<Boolean> {
        val reply =
            client.write<ValidateAddressBody, ValidateAddressData>(
                HttpMethod.Post,
                "/v1/w3s/transactions/validateAddress",
                "validate_address",
                ValidateAddressBody(freshKey(), settings.blockchain, address),
            )
        if (reply !is CircleReply.Ok) return CircleErrors.failure(reply, "validate_address")
        return Resource.success(reply.data.isValid)
    }

    override suspend fun pinChallenge(
        circleUserId: String,
        kind: PinChallenge,
        idempotencyKey: UUID,
    ): Resource<Challenge> {
        val (method, path, operation) =
            when (kind) {
                PinChallenge.CHANGE -> Triple(HttpMethod.Put, "/v1/w3s/user/pin", "change_pin")
                PinChallenge.RESTORE -> Triple(HttpMethod.Post, "/v1/w3s/user/pin/restore", "restore_pin")
            }
        return asUser(circleUserId) { token ->
            client.write<PinBody, ChallengeData>(method, path, operation, PinBody(idempotencyKey.toString()), token)
        }.flatMapReply { token, reply -> challengeOf(token, reply, operation) }
    }

    /**
     * Runs [call] with the user's token. A token Circle no longer accepts is dropped and the call made once more with
     * a fresh one: tokens are kept per node and may have been revoked or expired early.
     */
    private suspend fun <R> asUser(
        circleUserId: String,
        call: suspend (userToken: String) -> CircleReply<R>,
    ): Resource<UserCall<R>> {
        val first =
            when (val token = tokens.get(circleUserId)) {
                is Resource.Success -> UserCall(token.data, call(token.data.userToken))
                is Resource.Error -> return token
            }
        if (!first.isTokenRefused()) return Resource.success(first)

        tokens.forget(circleUserId)
        return when (val token = tokens.get(circleUserId)) {
            is Resource.Success -> Resource.success(UserCall(token.data, call(token.data.userToken)))
            is Resource.Error -> token
        }
    }

    private class UserCall<R>(val token: CircleUserTokens.UserToken, val reply: CircleReply<R>) {
        fun isTokenRefused(): Boolean = reply is CircleReply.Failed && reply.code in CircleErrors.USER_TOKEN_REFUSED
    }

    private inline fun <R, T> Resource<UserCall<R>>.flatMapReply(
        handle: (CircleUserTokens.UserToken, CircleReply<R>) -> Resource<T>,
    ): Resource<T> =
        when (this) {
            is Resource.Success -> handle(data.token, data.reply)
            is Resource.Error -> this
        }

    private fun challengeOf(
        token: CircleUserTokens.UserToken,
        reply: CircleReply<ChallengeData>,
        operation: String,
    ): Resource<Challenge> {
        if (reply !is CircleReply.Ok) return CircleErrors.failure(reply, operation)
        val challengeId = reply.data.challengeId
        if (challengeId.isEmpty()) return CircleErrors.failure(CircleReply.Failed(200, null), operation)
        return Resource.success(Challenge(challengeId, sessionOf(token)))
    }

    private fun sessionOf(token: CircleUserTokens.UserToken) =
        SdkSession(
            appId = settings.appId,
            userToken = token.userToken,
            encryptionKey = token.encryptionKey,
            expiresAt = token.expiresAt,
        )

    private fun walletOf(wallet: WalletData) =
        CustodyWallet(
            walletId = wallet.id,
            address = wallet.address.lowercase(),
            blockchain = wallet.blockchain,
            accountType = wallet.accountType,
            state = if (wallet.state == "LIVE") CustodyWalletState.LIVE else CustodyWalletState.FROZEN,
        )

    /** The USDC line of `tokenBalances`; a wallet that never held USDC has none, which is zero. */
    private fun usdcBalanceOf(data: BalancesData): Resource<Money> {
        val usdc =
            data.tokenBalances.firstOrNull { settings.isUsdc(it.token.tokenAddress, it.token.blockchain) }
                ?: return Resource.success(Money(0, Asset.USDC))
        val minor =
            CircleTransactions.minorUnits(usdc.amount, Asset.USDC)
                ?: return CircleErrors.failure(CircleReply.Failed(200, null), "get_balance")
        return Resource.success(Money(minor, Asset.USDC))
    }

    private fun offeredOnly(translated: Resource<CustodyTransaction?>): Resource<CustodyTransaction> =
        when (translated) {
            is Resource.Error -> {
                translated
            }

            is Resource.Success -> {
                translated.data?.let { Resource.success(it) }
                    ?: Resource.error(IntegrationError.Rejected("The transaction isn't in an offered asset."))
            }
        }

    private fun <T> notOffered(asset: Asset): Resource<T> =
        Resource.error(IntegrationError.Rejected("$asset is not offered."))

    /** Calls that are harmless to repeat still carry a key: Circle accepts one, and the shared client may retry. */
    private fun freshKey(): String = UUID.randomUUID().toString()

    private companion object {
        /** Gas is sponsored through Gas Station on SCA wallets; the level only sets how fast Circle bids. */
        const val FEE_LEVEL = "MEDIUM"
    }
}
