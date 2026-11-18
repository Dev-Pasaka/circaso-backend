package com.circaso.integrations.impl

import com.circaso.integrations.api.AssetBalance
import com.circaso.integrations.api.Challenge
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyWallet
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
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.currentNetwork
import java.time.Instant
import java.util.UUID

/**
 * One custody per network, side by side (decision 2026-11-18): each has its own vendor account (its own API key, app
 * id, user tokens and chains), and every call goes to the one for the network the coroutine works for
 * (`currentNetwork()`), so a testnet request can't reach the mainnet account or the other way round. A network with
 * no custody (mainnet without `CIRCLE_MAINNET_API_KEY`) answers `Unavailable` and is left out of [networks], which is
 * what keeps people off it.
 */
class NetworkCustody(private val byNetwork: Map<Network, StablecoinCustody>) : StablecoinCustody {
    init {
        require(Network.TESTNET in byNetwork) { "Custody needs the testnet at least" }
    }

    /** The custody for [network], for a caller that names it itself (tests, and wiring that must reach one side). */
    fun on(network: Network): StablecoinCustody? = byNetwork[network]

    override val supported: SupportedChains get() = byNetwork.getValue(Network.TESTNET).supported

    override val networks: Set<Network> get() = byNetwork.keys

    override suspend fun chains(): SupportedChains =
        (byNetwork[currentNetwork()] ?: byNetwork.getValue(Network.TESTNET)).supported

    override suspend fun createUser(circleUserId: String): Resource<Unit> = here { it.createUser(circleUserId) }

    override suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge> =
        here { it.createWallet(circleUserId, idempotencyKey) }

    override suspend fun addWallet(
        circleUserId: String,
        blockchain: String,
        idempotencyKey: UUID,
    ): Resource<Challenge> = here { it.addWallet(circleUserId, blockchain, idempotencyKey) }

    override fun creationGroup(blockchain: String): String =
        byNetwork[Network.ofChain(blockchain)]?.creationGroup(blockchain) ?: blockchain

    override suspend fun addWallets(
        circleUserId: String,
        blockchains: List<String>,
        idempotencyKey: UUID,
    ): Resource<Challenge> = here { it.addWallets(circleUserId, blockchains, idempotencyKey) }

    override suspend fun sdkSession(circleUserId: String): Resource<SdkSession> = here { it.sdkSession(circleUserId) }

    override suspend fun challengeOpen(circleUserId: String, challengeId: String): Resource<Boolean> =
        here { it.challengeOpen(circleUserId, challengeId) }

    override suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>> =
        here { it.wallets(circleUserId) }

    override suspend fun balance(
        circleUserId: String,
        walletId: String,
        asset: Asset,
    ): Resource<Money> = here { it.balance(circleUserId, walletId, asset) }

    override suspend fun balances(
        circleUserId: String,
        walletId: String,
        blockchain: String,
    ): Resource<List<AssetBalance>> = here { it.balances(circleUserId, walletId, blockchain) }

    override suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge> =
        here { it.prepareTransfer(circleUserId, request) }

    override fun canSplit(blockchain: String): Boolean =
        byNetwork[Network.ofChain(blockchain)]?.canSplit(blockchain) == true

    override suspend fun estimateSplit(circleUserId: String, request: SplitTransferRequest): Resource<NetworkCost> =
        here { it.estimateSplit(circleUserId, request) }

    override suspend fun prepareSplitTransfer(
        circleUserId: String,
        request: SplitTransferRequest,
    ): Resource<Challenge> = here { it.prepareSplitTransfer(circleUserId, request) }

    override suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction> =
        here { it.transaction(circleUserId, circleTxId) }

    override suspend fun transactions(
        circleUserId: String,
        since: Instant,
        limit: Int,
    ): Resource<List<CustodyTransaction>> = here { it.transactions(circleUserId, since, limit) }

    override suspend fun chainTransaction(blockchain: String, txHash: String): Resource<List<CustodyTransaction>> =
        here { it.chainTransaction(blockchain, txHash) }

    override suspend fun validateAddress(blockchain: String, address: String): Resource<Boolean> =
        here { it.validateAddress(blockchain, address) }

    override suspend fun pinChallenge(
        circleUserId: String,
        kind: PinChallenge,
        idempotencyKey: UUID,
    ): Resource<Challenge> = here { it.pinChallenge(circleUserId, kind, idempotencyKey) }

    private suspend fun <T> here(call: suspend (StablecoinCustody) -> Resource<T>): Resource<T> {
        val custody = byNetwork[currentNetwork()] ?: return Resource.error(IntegrationError.Unavailable("Custody"))
        return call(custody)
    }
}
