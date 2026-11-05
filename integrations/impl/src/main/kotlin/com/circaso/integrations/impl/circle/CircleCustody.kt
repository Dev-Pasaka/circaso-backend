package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.AssetBalance
import com.circaso.integrations.api.Challenge
import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyWallet
import com.circaso.integrations.api.CustodyWalletState
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.NetworkCost
import com.circaso.integrations.api.PinChallenge
import com.circaso.integrations.api.SdkSession
import com.circaso.integrations.api.SplitTransferRequest
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.SupportedChains
import com.circaso.integrations.api.TransferRequest
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant
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

    override val supported: SupportedChains get() = settings.supported

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
        // Every receiving chain that shares the default's account type is created in the same PIN setup.
        val chain = settings.supported.default
        val group = settings.accountType(chain)
        val chains =
            listOf(chain) +
                settings.supported.chains
                    .filter {
                        it.receiveEnabled && it.blockchain != chain &&
                            settings.accountType(it.blockchain) == group
                    }.map { it.blockchain }
        val body = InitializeBody(idempotencyKey.toString(), group, chains)
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

    override suspend fun addWallet(
        circleUserId: String,
        blockchain: String,
        idempotencyKey: UUID,
    ): Resource<Challenge> {
        if (settings.supported.find(blockchain) == null) return notSupported(blockchain)
        val body = AddWalletsBody(idempotencyKey.toString(), settings.accountType(blockchain), listOf(blockchain))
        return asUser(circleUserId) { token ->
            client.write<AddWalletsBody, ChallengeData>(
                HttpMethod.Post,
                "/v1/w3s/user/wallets",
                "create_wallet",
                body,
                token,
            )
        }.flatMapReply { token, reply -> challengeOf(token, reply, "create_wallet") }
    }

    override fun creationGroup(blockchain: String): String = settings.accountType(blockchain)

    override suspend fun addWallets(
        circleUserId: String,
        blockchains: List<String>,
        idempotencyKey: UUID,
    ): Resource<Challenge> {
        blockchains.firstOrNull { settings.supported.find(it) == null }?.let { return notSupported(it) }
        val groups = blockchains.map { settings.accountType(it) }.distinct()
        if (groups.size !=
            1
        ) {
            return Resource.error(IntegrationError.Rejected("These chains can't be added in one step."))
        }
        val body = AddWalletsBody(idempotencyKey.toString(), groups.single(), blockchains)
        return asUser(circleUserId) { token ->
            client.write<AddWalletsBody, ChallengeData>(
                HttpMethod.Post,
                "/v1/w3s/user/wallets",
                "create_wallet",
                body,
                token,
            )
        }.flatMapReply { token, reply -> challengeOf(token, reply, "create_wallet") }
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

    override suspend fun balances(
        circleUserId: String,
        walletId: String,
        blockchain: String,
    ): Resource<List<AssetBalance>> {
        val chain = settings.supported.find(blockchain) ?: return notSupported(blockchain)
        return asUser(circleUserId) { token ->
            client.get<BalancesData>("/v1/w3s/wallets/$walletId/balances", "get_balances", token)
        }.flatMapReply { _, reply ->
            if (reply !is CircleReply.Ok) return@flatMapReply CircleErrors.failure(reply, "get_balances")
            balancesOf(reply.data, blockchain, chain.assets)
        }
    }

    override suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge> {
        if (request.amount.asset != Asset.USDC) return notOffered(request.amount.asset)
        val usdc = settings.usdcOn(request.blockchain) ?: return notSupported(request.blockchain)
        val body =
            TransferBody(
                idempotencyKey = request.idempotencyKey.toString(),
                walletId = request.walletId,
                destinationAddress = request.destinationAddress,
                amounts = listOf(CircleTransactions.decimalOf(request.amount)),
                tokenAddress = usdc.tokenAddress,
                blockchain = request.blockchain,
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

    /** Smart-contract wallets batch calls (`executeBatch`); Solana's EOAs can't (fee spike, 2026-11-03). */
    override fun canSplit(blockchain: String): Boolean =
        settings.supported.find(blockchain) != null && settings.accountType(blockchain) == SCA

    override suspend fun estimateSplit(circleUserId: String, request: SplitTransferRequest): Resource<NetworkCost> {
        val batch =
            when (val built = batchOf(request)) {
                is Resource.Success -> built.data
                is Resource.Error -> return built
            }
        val body = EstimateContractBody(request.walletId, request.walletAddress, EXECUTE_BATCH, batch)
        val text = circleJson.encodeToString(EstimateContractBody.serializer(), body)
        return asUser(circleUserId) { token ->
            // Nothing is created, so Circle takes no key in the body; the header still lets the shared client retry.
            client.exchange(
                HttpMethod.Post,
                "/v1/w3s/transactions/contractExecution/estimateFee",
                "estimate_contract_execution",
                token,
                CircleClient.WrittenBody(freshKey(), text),
                FeeEstimateData.serializer(),
            )
        }.flatMapReply { _, reply ->
            if (reply !is CircleReply.Ok) return@flatMapReply CircleErrors.failure(reply, "estimate_contract_execution")
            val level = reply.data.medium ?: reply.data.high
            val fee = level?.networkFee?.toBigDecimalOrNull()
            if (fee == null || fee.signum() < 0) {
                return@flatMapReply CircleErrors.failure(CircleReply.Failed(200, null), "estimate_contract_execution")
            }
            Resource.success(NetworkCost(fee, nativeSymbol(request.blockchain)))
        }
    }

    override suspend fun prepareSplitTransfer(
        circleUserId: String,
        request: SplitTransferRequest,
    ): Resource<Challenge> {
        val batch =
            when (val built = batchOf(request)) {
                is Resource.Success -> built.data
                is Resource.Error -> return built
            }
        val body =
            ContractExecutionBody(
                idempotencyKey = request.idempotencyKey.toString(),
                walletId = request.walletId,
                contractAddress = request.walletAddress,
                abiFunctionSignature = EXECUTE_BATCH,
                abiParameters = batch,
                feeLevel = FEE_LEVEL,
                refId = request.refId,
            )
        return asUser(circleUserId) { token ->
            client.write<ContractExecutionBody, ChallengeData>(
                HttpMethod.Post,
                "/v1/w3s/user/transactions/contractExecution",
                "create_contract_execution",
                body,
                token,
            )
        }.flatMapReply { token, reply -> challengeOf(token, reply, "create_contract_execution") }
    }

    /**
     * `executeBatch`'s one argument: per leg `(USDC, 0, transfer(to, amount))`. Every leg is USDC on a chain whose
     * wallets are smart-contract accounts, to an EVM address.
     */
    private fun batchOf(request: SplitTransferRequest): Resource<List<List<List<String>>>> {
        if (!canSplit(request.blockchain)) {
            return Resource.error(
                IntegrationError.Rejected("${request.blockchain} can't pay several addresses at once."),
            )
        }
        val usdc = settings.usdcOn(request.blockchain) ?: return notSupported(request.blockchain)
        if (request.legs.isEmpty()) return Resource.error(IntegrationError.Rejected("A payment needs a leg."))
        val calls =
            request.legs.map { leg ->
                if (leg.amount.asset != Asset.USDC) return notOffered(leg.amount.asset)
                val data =
                    Erc20.transfer(leg.destinationAddress, leg.amount.amountMinor)
                        ?: return Resource.error(IntegrationError.AddressInvalid())
                listOf(usdc.tokenAddress, "0", data)
            }
        return Resource.success(listOf(calls))
    }

    private fun nativeSymbol(blockchain: String): String =
        when {
            blockchain.startsWith("MATIC") -> "POL"
            blockchain.startsWith("SOL") -> "SOL"
            blockchain.startsWith("AVAX") -> "AVAX"
            else -> "ETH"
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

    override suspend fun transactions(
        circleUserId: String,
        since: Instant,
        limit: Int,
    ): Resource<List<CustodyTransaction>> {
        val listed = mutableListOf<CustodyTransaction>()
        var after: String? = null
        while (listed.size < limit) {
            val size = minOf(PAGE_SIZE, limit - listed.size)
            val path =
                "/v1/w3s/transactions?from=${encode(since.toString())}&pageSize=$size" +
                    (after?.let { "&pageAfter=${encode(it)}" } ?: "")
            val page =
                asUser(circleUserId) { token ->
                    client.get<TransactionsData>(path, "list_transactions", token)
                }.flatMapReply { _, reply ->
                    if (reply !is CircleReply.Ok) return@flatMapReply CircleErrors.failure(reply, "list_transactions")
                    Resource.success(reply.data.transactions)
                }
            val payloads =
                when (page) {
                    is Resource.Success -> page.data
                    is Resource.Error -> return page
                }
            for (payload in payloads) {
                when (val translated = transactions.translate(payload)) {
                    is Resource.Success -> translated.data?.let { listed += it.withUser(circleUserId) }

                    // One transaction we can't read (logged by the translation) doesn't hide the others.
                    is Resource.Error -> if (translated.error is IntegrationError.Unavailable) return translated
                }
            }
            if (payloads.size < size) break
            after = payloads.last().id
        }
        return Resource.success(listed.take(limit))
    }

    /** Circle's list may leave out the user on each transaction; it is the user we asked about. */
    private fun CustodyTransaction.withUser(circleUserId: String) =
        if (this.circleUserId.isEmpty()) copy(circleUserId = circleUserId) else this

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    override suspend fun validateAddress(blockchain: String, address: String): Resource<Boolean> {
        if (settings.supported.find(blockchain) == null) return notSupported(blockchain)
        val reply =
            client.write<ValidateAddressBody, ValidateAddressData>(
                HttpMethod.Post,
                "/v1/w3s/transactions/validateAddress",
                "validate_address",
                ValidateAddressBody(freshKey(), blockchain, address),
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
            address = wallet.address,
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

    /**
     * One line per asset offered on the chain, in the configured order, then any other token the wallet holds on it
     * (decision 2026-10-30: whatever is in the wallet on that network is shown). Circle lists only the tokens a wallet
     * has held, so an offered asset missing from its answer is zero. A held token we don't offer is shown only while
     * its balance is above zero, with Circle's symbol and decimals, never priced and never sendable (sending checks the
     * offered assets).
     */
    private fun balancesOf(
        data: BalancesData,
        blockchain: String,
        offered: List<CustodyAsset>,
    ): Resource<List<AssetBalance>> {
        val held = data.tokenBalances.filter { it.token.blockchain == blockchain }
        val lines =
            offered.map { asset ->
                val line =
                    held.firstOrNull { settings.offered(it.token.tokenAddress, blockchain) == asset }
                        ?: return@map AssetBalance(asset, BigInteger.ZERO)
                val minor =
                    minorUnits(line.amount, asset.decimals)
                        ?: return CircleErrors.failure(CircleReply.Failed(200, null), "get_balances")
                AssetBalance(asset, minor)
            }
        val others =
            held
                .filter { line -> settings.offered(line.token.tokenAddress, blockchain) == null }
                .mapNotNull { line -> heldOnly(line) }
        return Resource.success(lines + others)
    }

    /** A held token outside the offered list, or null when Circle didn't describe it or it holds nothing. */
    private fun heldOnly(line: TokenBalanceData): AssetBalance? {
        val decimals = line.token.decimals ?: return null
        val symbol =
            line.token.symbol
                .substringBefore('-')
                .trim()
                .uppercase()
        if (symbol.isEmpty() || decimals !in 0..36) return null
        val minor = minorUnits(line.amount, decimals) ?: return null
        if (minor.signum() <= 0) return null
        return AssetBalance(CustodyAsset(symbol, line.token.tokenAddress, decimals, usdPegged = false), minor)
    }

    /** "20.5" of a 6-decimal token as 20 500 000; null if it isn't a number or has more decimals than the asset. */
    private fun minorUnits(decimal: String, decimals: Int): BigInteger? =
        runCatching {
            BigDecimal(decimal)
                .movePointRight(decimals)
                .setScale(0, RoundingMode.UNNECESSARY)
                .toBigIntegerExact()
        }.getOrNull()

    private fun <T> notSupported(blockchain: String): Resource<T> =
        Resource.error(IntegrationError.Rejected("$blockchain is not a supported chain."))

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

        const val SCA = "SCA"

        /** Every Circle smart-contract wallet has it; all calls succeed together or the whole batch reverts. */
        const val EXECUTE_BATCH = "executeBatch((address,uint256,bytes)[])"

        /** The most Circle lists in one page. */
        const val PAGE_SIZE = 50
    }
}
