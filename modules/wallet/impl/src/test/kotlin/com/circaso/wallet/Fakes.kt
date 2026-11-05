package com.circaso.wallet

import com.circaso.integrations.api.AssetBalance
import com.circaso.integrations.api.Challenge
import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.CustodyChain
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyWallet
import com.circaso.integrations.api.CustodyWalletState
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.NetworkCost
import com.circaso.integrations.api.PinChallenge
import com.circaso.integrations.api.SdkSession
import com.circaso.integrations.api.SplitTransferRequest
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.SupportedChains
import com.circaso.integrations.api.TransferRequest
import com.circaso.integrations.api.UsdRate
import com.circaso.shared.Asset
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventSubscription
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.ports.BalanceCache
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.WalletOutbox
import com.circaso.wallet.domain.repositories.WalletRepository
import kotlinx.serialization.KSerializer
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

const val USDC_BASE = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
const val USDC_SOLANA = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
const val USDC_AMOY = "0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582"
const val USDC_SEPOLIA = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"

/**
 * Base Sepolia (USDC and ETH, the native coin), Solana Devnet (USDC) and Polygon Amoy (USDC), which is retired from
 * receiving (decision 2026-10-28); a wallet starts on Base.
 */
val TEST_CHAINS =
    SupportedChains(
        default = "BASE-SEPOLIA",
        chains =
            listOf(
                CustodyChain(
                    "BASE-SEPOLIA",
                    listOf(
                        CustodyAsset("USDC", USDC_BASE, 6, usdPegged = true),
                        CustodyAsset("ETH", "", 18, usdPegged = false),
                    ),
                ),
                CustodyChain("SOL-DEVNET", listOf(CustodyAsset("USDC", USDC_SOLANA, 6, usdPegged = true))),
                CustodyChain(
                    "MATIC-AMOY",
                    listOf(CustodyAsset("USDC", USDC_AMOY, 6, usdPegged = true)),
                    receiveEnabled = false,
                ),
            ),
    )

/**
 * Circle in memory. A challenge only becomes a wallet when the test says the user signed it ([sign]); while [down]
 * is set every call fails as Circle being unavailable. Each call is counted by name in [calls].
 */
class FakeCustody(override val supported: SupportedChains = TEST_CHAINS) : StablecoinCustody {
    val users: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Users who have set the PIN: Circle has nothing more for them to sign in `createWallet`. */
    val initialized: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val wallets = ConcurrentHashMap<String, List<CustodyWallet>>()

    /** Held amounts by Circle wallet id and asset symbol, in minor units. */
    val held = ConcurrentHashMap<String, Map<String, BigInteger>>()
    val calls: MutableList<String> = CopyOnWriteArrayList()
    val keys: MutableList<UUID> = CopyOnWriteArrayList()

    /** Every transfer asked for, by Circle user; one challenge per idempotency key, as Circle answers a repeat. */
    val transfers: MutableList<Pair<String, TransferRequest>> = CopyOnWriteArrayList()
    private val transferChallenges = ConcurrentHashMap<UUID, String>()

    /** Addresses Circle says aren't valid, on top of the shape check. */
    val invalidAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Circle's transactions by id, as the jobs read them ([record]). */
    val ledger = ConcurrentHashMap<String, CustodyTransaction>()

    /** When set, Circle refuses the next transfers with it. */
    @Volatile
    var refuseTransfers: IntegrationError? = null

    @Volatile
    var down = false

    private val challenges = AtomicInteger()

    /** The user ran the challenge in the SDK: Circle now has their wallet on [blockchain]. */
    fun sign(
        circleUserId: String,
        blockchain: String = supported.default,
        address: String? = null,
    ): CustodyWallet {
        val wallet =
            CustodyWallet(
                walletId = "w-${UUID.randomUUID()}",
                address = address ?: defaultAddress(blockchain),
                blockchain = blockchain,
                accountType = if (blockchain.startsWith("SOL")) "EOA" else "SCA",
                state = CustodyWalletState.LIVE,
            )
        wallets.merge(circleUserId, listOf(wallet)) { old, new -> old + new }
        initialized += circleUserId
        return wallet
    }

    fun count(call: String): Int = calls.count { it == call }

    override suspend fun createUser(circleUserId: String): Resource<Unit> =
        call("createUser") {
            users += circleUserId
            Resource.success(Unit)
        }

    override suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge> =
        call("createWallet") {
            keys += idempotencyKey
            if (circleUserId in initialized) {
                Resource.success(Challenge("", session(circleUserId)))
            } else {
                Resource.success(challenge(circleUserId))
            }
        }

    override suspend fun addWallet(
        circleUserId: String,
        blockchain: String,
        idempotencyKey: UUID,
    ): Resource<Challenge> =
        call("addWallet") {
            keys += idempotencyKey
            Resource.success(challenge(circleUserId))
        }

    override suspend fun sdkSession(circleUserId: String): Resource<SdkSession> =
        call("sdkSession") { Resource.success(session(circleUserId)) }

    override suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>> =
        call("wallets") { Resource.success(wallets[circleUserId].orEmpty()) }

    override suspend fun balance(
        circleUserId: String,
        walletId: String,
        asset: Asset,
    ): Resource<Money> = error("not used in WS2")

    override suspend fun balances(
        circleUserId: String,
        walletId: String,
        blockchain: String,
    ): Resource<List<AssetBalance>> =
        call("balances") {
            val chain = supported.find(blockchain) ?: return@call Resource.error(IntegrationError.Rejected("chain"))
            val amounts = held[walletId].orEmpty()
            Resource.success(chain.assets.map { AssetBalance(it, amounts[it.symbol] ?: BigInteger.ZERO) })
        }

    override suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge> =
        call("prepareTransfer") {
            refuseTransfers?.let { return@call Resource.error(it) }
            transfers += circleUserId to request
            val id = transferChallenges.getOrPut(request.idempotencyKey) { "challenge-${challenges.incrementAndGet()}" }
            Resource.success(Challenge(id, session(circleUserId)))
        }

    /** Every split payment asked for (payee and fee in one batch), by Circle user. */
    val splits: MutableList<Pair<String, SplitTransferRequest>> = CopyOnWriteArrayList()

    /** What the estimate answers, in the native coin; [estimateFails] makes it fail as Circle down would. */
    @Volatile
    var networkCost: BigDecimal = BigDecimal("0.000004")

    @Volatile
    var estimateFails = false

    /** EVM chains batch (smart-contract wallets); Solana doesn't. */
    override fun canSplit(blockchain: String): Boolean =
        supported.find(blockchain) != null && !blockchain.startsWith("SOL")

    override suspend fun estimateSplit(circleUserId: String, request: SplitTransferRequest): Resource<NetworkCost> =
        call("estimateSplit") {
            if (estimateFails) return@call Resource.error(IntegrationError.Unavailable("Circle"))
            Resource.success(NetworkCost(networkCost, "ETH"))
        }

    override suspend fun prepareSplitTransfer(
        circleUserId: String,
        request: SplitTransferRequest,
    ): Resource<Challenge> =
        call("prepareSplitTransfer") {
            refuseTransfers?.let { return@call Resource.error(it) }
            splits += circleUserId to request
            val id = transferChallenges.getOrPut(request.idempotencyKey) { "challenge-${challenges.incrementAndGet()}" }
            Resource.success(Challenge(id, session(circleUserId)))
        }

    override suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction> =
        call("transaction") {
            ledger[circleTxId]?.let { Resource.success(it) }
                ?: Resource.error(IntegrationError.Rejected("Unknown transaction."))
        }

    override suspend fun transactions(
        circleUserId: String,
        since: Instant,
        limit: Int,
    ): Resource<List<CustodyTransaction>> =
        call("transactions") {
            Resource.success(
                ledger.values
                    .filter { it.circleUserId == circleUserId && !it.updatedAt.isBefore(since) }
                    .sortedByDescending { it.updatedAt }
                    .take(limit),
            )
        }

    /** What Circle now says about a transaction: what [transaction] and [transactions] answer. */
    fun record(transaction: CustodyTransaction) {
        ledger[transaction.circleTxId] = transaction
    }

    override suspend fun validateAddress(blockchain: String, address: String): Resource<Boolean> =
        call("validateAddress") {
            val shape = if (blockchain.startsWith("SOL")) SOLANA_ADDRESS else EVM_ADDRESS
            Resource.success(shape.matches(address) && address !in invalidAddresses)
        }

    override suspend fun pinChallenge(
        circleUserId: String,
        kind: PinChallenge,
        idempotencyKey: UUID,
    ): Resource<Challenge> = error("not used in WS2")

    private inline fun <T> call(name: String, block: () -> Resource<T>): Resource<T> {
        calls += name
        if (down) return Resource.error(IntegrationError.Unavailable("Circle"))
        return block()
    }

    private fun challenge(circleUserId: String) =
        Challenge("challenge-${challenges.incrementAndGet()}", session(circleUserId))

    private fun session(circleUserId: String) =
        SdkSession("app-1", "token-$circleUserId", "key-$circleUserId", Instant.parse("2026-10-28T10:00:00Z"))

    private companion object {
        val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
        val SOLANA_ADDRESS = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

        fun defaultAddress(blockchain: String): String {
            val random = UUID.randomUUID().toString().replace("-", "")
            if (blockchain.startsWith("SOL")) return "So1" + random.take(29).replace('0', '1').replace('l', 'L')
            return "0xAB" + random.take(32) + "cdef01"
        }
    }
}

/** Rates set by the test, one US dollar in each currency. */
class FakeRates : ExchangeRates {
    val rates = ConcurrentHashMap<String, BigDecimal>()
    val asOf: Instant = Instant.parse("2026-10-28T00:00:00Z")

    override suspend fun usdRate(currency: String): UsdRate? = rates[currency]?.let { UsdRate(currency, it, asOf) }
}

/** `circle_users` and `wallets` in memory, with the same claims as the unique keys. */
class MemoryWalletRepository : WalletRepository {
    val circleUsers = ConcurrentHashMap<UserId, CircleUser>()
    val rows: MutableList<ChainWallet> = CopyOnWriteArrayList()

    override suspend fun circleUser(userId: UserId): CircleUser? = circleUsers[userId]

    override suspend fun insertCircleUser(user: CircleUser): Boolean =
        circleUsers.putIfAbsent(user.userId, user) == null

    override suspend fun circleUserByCircleId(circleUserId: UUID): CircleUser? =
        circleUsers.values.firstOrNull { it.circleUserId == circleUserId }

    override suspend fun wallets(userId: UserId): List<ChainWallet> = rows.filter { it.userId == userId }

    override suspend fun wallet(circleWalletId: String): ChainWallet? =
        rows.firstOrNull {
            it.circleWalletId ==
                circleWalletId
        }

    override suspend fun walletAt(address: String, blockchain: String): ChainWallet? =
        rows.firstOrNull { it.address == address && it.blockchain == blockchain }

    override suspend fun insertWallet(wallet: ChainWallet): Boolean =
        synchronized(rows) {
            val taken =
                rows.any {
                    it.circleWalletId == wallet.circleWalletId ||
                        (it.userId == wallet.userId && it.blockchain == wallet.blockchain) ||
                        (it.address == wallet.address && it.blockchain == wallet.blockchain)
                }
            if (!taken) rows += wallet
            !taken
        }
}

object DirectTransactor : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = block()

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> = block()
}

class RecordingOutbox : WalletOutbox {
    val written: MutableList<EventEnvelope> = CopyOnWriteArrayList()

    override suspend fun write(events: List<EventEnvelope>) {
        written += events
    }
}

class MemoryBus : EventBus {
    val published: MutableList<EventEnvelope> = CopyOnWriteArrayList()

    override fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>) =
        EventEnvelope.of(event, eventSerializer)

    override suspend fun publish(envelope: EventEnvelope) {
        published += envelope
    }

    override fun subscribe(subscription: EventSubscription) = Unit
}

class MemoryBalanceCache : BalanceCache {
    val values = ConcurrentHashMap<String, List<Holding>>()

    override suspend fun get(circleWalletId: String): List<Holding>? = values[circleWalletId]

    override suspend fun put(circleWalletId: String, holdings: List<Holding>) {
        values[circleWalletId] = holdings
    }

    override suspend fun clear(circleWalletId: String) {
        values.remove(circleWalletId)
    }
}

/** The warnings the wallet raised, as (chain, Circle wallet id). */
class RecordingWarnings : WalletWarnings {
    val chainsNotConfigured: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    /** (transfer id, share in basis points) of every payment whose gas went over its share of the fee. */
    val gasOverShare: MutableList<Pair<String, Int>> = CopyOnWriteArrayList()

    val feesNotTaken: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    override fun gasAboveShare(
        transferId: String,
        blockchain: String,
        shareBps: Int,
    ) {
        gasOverShare += transferId to shareBps
    }

    override fun feeNotTaken(blockchain: String, reason: String) {
        feesNotTaken += blockchain to reason
    }

    val unknownWallets: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    override fun chainNotConfigured(blockchain: String, circleWalletId: String) {
        chainsNotConfigured += blockchain to circleWalletId
    }

    val refIdMismatches: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    override fun refIdOfAnotherWallet(transferId: String, circleWalletId: String) {
        refIdMismatches += transferId to circleWalletId
    }

    override fun unknownWallet(blockchain: String, circleWalletId: String) {
        unknownWallets += blockchain to circleWalletId
    }

    val stuck: MutableList<String> = CopyOnWriteArrayList()

    override fun transferStuck(transferId: String, blockchain: String) {
        stuck += transferId
    }

    val backoffs: MutableList<Pair<String, Int>> = CopyOnWriteArrayList()

    override fun jobBackingOff(job: String, skipRuns: Int) {
        backoffs += job to skipRuns
    }
}

class MemoryAddingChains : AddingChains {
    val values = ConcurrentHashMap<UserId, MutableSet<String>>()

    override suspend fun add(userId: UserId, blockchain: String) {
        values.getOrPut(userId) { ConcurrentHashMap.newKeySet() } += blockchain
    }

    override suspend fun list(userId: UserId): Set<String> = values[userId].orEmpty().toSet()

    override suspend fun remove(userId: UserId, blockchain: String) {
        values[userId]?.remove(blockchain)
    }
}
