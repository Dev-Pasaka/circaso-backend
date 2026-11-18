package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryAccess
import com.circaso.integrations.api.TreasuryBalance
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryRefusal
import com.circaso.integrations.api.TreasuryTransaction
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.integrations.api.TreasuryWallet
import com.circaso.integrations.api.TreasuryWalletRequest
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.BigInteger
import java.security.PublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One Circle account as the treasury uses it: its client, and its entity secret when this server has one. */
internal class TreasuryAccount(val client: CircleClient, val secret: EntitySecret?)

/**
 * Circle's developer-controlled wallets (developers.circle.com/api-reference/wallets/developer-controlled-wallets),
 * for Circaso's own fee wallets (decision 2026-11-18). Built in the ops process only. Checked against Circle's
 * reference on 2026-11-19:
 *
 * - `GET /v1/w3s/config/entity/publicKey` → `data.publicKey` (PEM); kept for [KEY_KEPT_FOR] per account.
 * - `POST /v1/w3s/developer/walletSets` `{idempotencyKey, entitySecretCiphertext, name}` → `data.walletSet.id`.
 * - `POST /v1/w3s/developer/wallets` `{idempotencyKey, entitySecretCiphertext, walletSetId, blockchains, count,
 *   accountType, metadata[{name, refId}]}` → `data.wallets[]`.
 * - `PUT /v1/w3s/developer/wallets/{id}/blockchains/{blockchain}` `{metadata}` → `data.wallet` (EVM only; neither a
 *   key nor a ciphertext; 200 when it already exists).
 * - `GET /v1/w3s/wallets?address=&blockchain=&refId=&pageSize=` → `data.wallets[]`.
 * - `GET /v1/w3s/wallets/{id}/balances` → `data.tokenBalances[]` (decimal amounts).
 * - `POST /v1/w3s/transactions/transfer/estimateFee` → `data.{low,medium,high}.networkFee` (native coin, decimal).
 * - `POST /v1/w3s/developer/transactions/transfer` `{idempotencyKey, entitySecretCiphertext, walletId, tokenId,
 *   amounts[decimal], destinationAddress, feeLevel, refId}` → `data.{id, state}`.
 * - `GET /v1/w3s/transactions/{id}` → `data.transaction`.
 *
 * Every body with a ciphertext is sent once per ciphertext (never repeated by the shared client); what makes a repeat
 * safe is Circle's idempotency key in the body. Nothing here logs a body, a key, the secret or a ciphertext.
 */
internal class CircleTreasury(
    private val accounts: Map<Network, TreasuryAccount>,
    private val clock: Clock = Clock.systemUTC(),
) : TreasuryCustody {
    private class KeptKey(val key: PublicKey, val at: Instant)

    private val log = LoggerFactory.getLogger(CircleTreasury::class.java)
    private val publicKeys = ConcurrentHashMap<Network, KeptKey>()

    override fun access(network: Network): TreasuryAccess {
        val account = accounts[network] ?: return TreasuryAccess.NO_ACCOUNT
        return if (account.secret == null) TreasuryAccess.READ_ONLY else TreasuryAccess.FULL
    }

    override suspend fun createWalletSet(
        network: Network,
        name: String,
        idempotencyKey: UUID,
    ): Resource<String> =
        signed<CreateWalletSetBody, WalletSetData>(network, "/v1/w3s/developer/walletSets", "create_wallet_set") {
            CreateWalletSetBody(idempotencyKey.toString(), it, name)
        }.flatMap { data ->
            data.walletSet
                ?.id
                ?.takeIf { it.isNotEmpty() }
                ?.let { Resource.success(it) } ?: unreadable()
        }

    override suspend fun createWallets(
        network: Network,
        request: TreasuryWalletRequest,
    ): Resource<List<TreasuryWallet>> =
        signed<CreateDeveloperWalletsBody, DeveloperWalletsData>(
            network,
            "/v1/w3s/developer/wallets",
            "create_developer_wallets",
        ) { ciphertext ->
            CreateDeveloperWalletsBody(
                idempotencyKey = request.idempotencyKey.toString(),
                entitySecretCiphertext = ciphertext,
                walletSetId = request.walletSetId,
                blockchains = request.blockchains,
                count = 1,
                accountType = EOA,
                metadata = listOf(WalletMetadata(request.name, request.refId)),
            )
        }.flatMap { data ->
            val wallets = data.wallets.mapNotNull { it.toWallet() }
            if (wallets.isEmpty()) unreadable() else Resource.success(wallets)
        }

    override suspend fun deriveWallet(
        network: Network,
        walletId: String,
        blockchain: String,
        name: String,
        refId: String,
    ): Resource<TreasuryWallet> {
        val account = accounts[network] ?: return noAccount()
        val body =
            circleJson.encodeToString(DeriveWalletBody.serializer(), DeriveWalletBody(WalletMetadata(name, refId)))
        val path =
            "/v1/w3s/developer/wallets/${walletId.encodeURLParameter()}" +
                "/blockchains/${blockchain.encodeURLParameter()}"
        val reply =
            account.client.exchange(
                HttpMethod.Put,
                path,
                "derive_wallet",
                userToken = null,
                body = CircleClient.WrittenBody("", body, repeatable = false),
                answer = serializer<DeveloperWalletData>(),
            )
        return answerOf(reply, "derive_wallet").flatMap { data ->
            data.wallet?.toWallet()?.let { Resource.success(it) } ?: unreadable()
        }
    }

    override suspend fun findWallets(
        network: Network,
        address: String?,
        blockchain: String?,
        refId: String?,
    ): Resource<List<TreasuryWallet>> {
        val account = accounts[network] ?: return noAccount()
        val query =
            listOfNotNull(
                address?.let { "address" to it },
                blockchain?.let { "blockchain" to it },
                refId?.let { "refId" to it },
                "pageSize" to PAGE_SIZE.toString(),
            ).joinToString("&") { (name, value) -> "$name=${value.encodeURLParameter()}" }
        val reply = account.client.get<DeveloperWalletsData>("/v1/w3s/wallets?$query", "list_wallets")
        return answerOf(reply, "list_wallets").map { data -> data.wallets.mapNotNull { it.toWallet() } }
    }

    override suspend fun balances(network: Network, walletId: String): Resource<List<TreasuryBalance>> {
        val account = accounts[network] ?: return noAccount()
        val path = "/v1/w3s/wallets/${walletId.encodeURLParameter()}/balances?pageSize=$PAGE_SIZE"
        val reply = account.client.get<BalancesData>(path, "get_treasury_balances")
        return answerOf(reply, "get_treasury_balances").map { data -> data.tokenBalances.mapNotNull { it.toBalance() } }
    }

    override suspend fun estimateTransfer(network: Network, transfer: TreasuryTransfer): Resource<BigDecimal> {
        val account = accounts[network] ?: return noAccount()
        val body =
            EstimateTransferBody(
                walletId = transfer.walletId,
                tokenId = transfer.tokenId,
                amounts = listOf(decimalOf(transfer.amountMinor, transfer.decimals)),
                destinationAddress = transfer.destinationAddress,
            )
        val reply =
            account.client.exchange(
                HttpMethod.Post,
                "/v1/w3s/transactions/transfer/estimateFee",
                "estimate_treasury_transfer",
                userToken = null,
                body =
                    CircleClient.WrittenBody(
                        "",
                        circleJson.encodeToString(EstimateTransferBody.serializer(), body),
                        repeatable = false,
                    ),
                answer = serializer<FeeEstimateData>(),
            )
        return answerOf(reply, "estimate_treasury_transfer").flatMap { data ->
            // MEDIUM is the level the transfer is sent at; its `networkFee` is Circle's buffered upper bound.
            val fee = (data.medium ?: data.high ?: data.low)?.networkFee?.toBigDecimalOrNull()
            if (fee == null || fee.signum() < 0) unreadable() else Resource.success(fee)
        }
    }

    override suspend fun send(network: Network, transfer: TreasuryTransfer): Resource<TreasuryTransaction> =
        signed<DeveloperTransferBody, DeveloperTransactionData>(
            network,
            "/v1/w3s/developer/transactions/transfer",
            "create_treasury_transfer",
        ) { ciphertext ->
            DeveloperTransferBody(
                idempotencyKey = transfer.idempotencyKey.toString(),
                entitySecretCiphertext = ciphertext,
                walletId = transfer.walletId,
                tokenId = transfer.tokenId,
                amounts = listOf(decimalOf(transfer.amountMinor, transfer.decimals)),
                destinationAddress = transfer.destinationAddress,
                feeLevel = FEE_LEVEL,
                refId = transfer.refId,
            )
        }.flatMap { data ->
            if (data.id.isEmpty()) {
                // Circle said yes but we can't read which transaction: unknown, so the same key is asked again.
                Resource.error(IntegrationError.Unavailable(CircleErrors.VENDOR))
            } else {
                Resource.success(TreasuryTransaction(data.id, stateOf(data.state), txHash = "", failureReason = ""))
            }
        }

    override suspend fun transaction(network: Network, transactionId: String): Resource<TreasuryTransaction> {
        val account = accounts[network] ?: return noAccount()
        val path = "/v1/w3s/transactions/${transactionId.encodeURLParameter()}"
        val reply = account.client.get<TransactionData>(path, "get_treasury_transaction")
        return answerOf(reply, "get_treasury_transaction").flatMap { data ->
            val found = data.transaction ?: return@flatMap unreadable()
            Resource.success(
                TreasuryTransaction(
                    transactionId = found.id.ifEmpty { transactionId },
                    state = stateOf(found.state),
                    txHash = found.txHash,
                    failureReason = found.errorReason.take(REASON_LENGTH),
                ),
            )
        }
    }

    /** A POST whose body carries a fresh ciphertext of the account's entity secret. */
    private suspend inline fun <reified B : CircleWrite, reified R> signed(
        network: Network,
        path: String,
        operation: String,
        body: (ciphertext: String) -> B,
    ): Resource<R> {
        val account = accounts[network] ?: return noAccount()
        val secret = account.secret ?: return Resource.error(IntegrationError.Unavailable(CircleErrors.VENDOR, false))
        val key =
            when (val fetched = publicKey(network, account)) {
                is Resource.Success -> fetched.data
                is Resource.Error -> return fetched
            }
        val written = body(secret.ciphertext(key))
        val reply =
            account.client.exchange(
                HttpMethod.Post,
                path,
                operation,
                userToken = null,
                body =
                    CircleClient.WrittenBody(
                        written.idempotencyKey,
                        circleJson.encodeToString(serializer<B>(), written),
                        repeatable = false,
                    ),
                answer = serializer<R>(),
            )
        // A refusal may be a rotated entity key: the next call fetches it again.
        if (reply is CircleReply.Failed) publicKeys.remove(network)
        return answerOf(reply, operation)
    }

    private suspend fun publicKey(network: Network, account: TreasuryAccount): Resource<PublicKey> {
        val kept = publicKeys[network]
        if (kept != null && clock.instant().isBefore(kept.at.plus(KEY_KEPT_FOR))) return Resource.success(kept.key)
        val reply = account.client.get<EntityPublicKeyData>("/v1/w3s/config/entity/publicKey", "get_entity_public_key")
        return answerOf(reply, "get_entity_public_key").flatMap { data ->
            val key = EntitySecret.publicKey(data.publicKey) ?: return@flatMap unreadable()
            publicKeys[network] = KeptKey(key, clock.instant())
            Resource.success(key)
        }
    }

    /**
     * Circle's answer in the contract's words. Down, overloaded, or a success we can't read: `Unavailable`, the
     * outcome unknown. Anything else Circle said: a [TreasuryRefusal] with the status and Circle's code, nothing more.
     */
    private fun <T> answerOf(reply: CircleReply<T>, operation: String): Resource<T> =
        when (reply) {
            is CircleReply.Ok -> {
                Resource.success(reply.data)
            }

            is CircleReply.Unreachable -> {
                Resource.error(IntegrationError.Unavailable(CircleErrors.VENDOR), reply.cause)
            }

            is CircleReply.Failed -> {
                log.warn("circle treasury: operation={} status={} code={}", operation, reply.status, reply.code)
                if (reply.status == TOO_MANY || reply.status >= SERVER_ERROR || reply.status in 200..299) {
                    Resource.error(IntegrationError.Unavailable(CircleErrors.VENDOR))
                } else {
                    Resource.error(TreasuryRefusal(reply.status, reply.code))
                }
            }
        }

    private fun <T> noAccount(): Resource<T> = Resource.error(IntegrationError.Unavailable(CircleErrors.VENDOR, false))

    private fun <T> unreadable(): Resource<T> = Resource.error(IntegrationError.Unavailable(CircleErrors.VENDOR))

    private fun DeveloperWalletPayload.toWallet(): TreasuryWallet? {
        if (id.isEmpty() || address.isEmpty() || blockchain.isEmpty()) return null
        return TreasuryWallet(
            walletId = id,
            walletSetId = walletSetId,
            blockchain = blockchain,
            address = address,
            accountType = accountType,
            refId = refId,
            developerOwned = custodyType == DEVELOPER,
        )
    }

    private fun TokenBalanceData.toBalance(): TreasuryBalance? {
        val decimals = token.decimals?.takeIf { it in 0..MAX_DECIMALS } ?: return null
        val symbol = CircleTransactions.symbolOf(token.symbol) ?: return null
        val minor = CircleTransactions.minorUnits(amount, decimals) ?: return null
        return TreasuryBalance(
            tokenId = token.id,
            symbol = symbol,
            tokenAddress = token.tokenAddress.trim(),
            decimals = decimals,
            native = token.isNative || token.tokenAddress.isBlank(),
            amountMinor = minor,
        )
    }

    private fun stateOf(state: String): TreasuryTransactionState =
        when (state) {
            "CONFIRMED", "COMPLETE" -> TreasuryTransactionState.CONFIRMED

            "FAILED", "DENIED", "CANCELLED" -> TreasuryTransactionState.FAILED

            // INITIATED, CLEARED, QUEUED, SENT, STUCK, and any state Circle adds later: still on its way.
            else -> TreasuryTransactionState.IN_FLIGHT
        }

    internal companion object {
        const val EOA = "EOA"
        const val FEE_LEVEL = "MEDIUM"
        const val DEVELOPER = "DEVELOPER"
        const val PAGE_SIZE = 50
        const val MAX_DECIMALS = 36
        const val REASON_LENGTH = 200
        const val TOO_MANY = 429
        const val SERVER_ERROR = 500
        val KEY_KEPT_FOR: Duration = Duration.ofHours(1)

        /** Minor units as the decimal string Circle expects: exact, never through a floating-point number. */
        fun decimalOf(amountMinor: BigInteger, decimals: Int): String =
            BigDecimal(amountMinor, decimals).stripTrailingZeros().toPlainString()
    }
}
