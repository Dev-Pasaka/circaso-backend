package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.AssetBalance
import com.circaso.integrations.api.Challenge
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyWallet
import com.circaso.integrations.api.CustodyWalletState
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PinChallenge
import com.circaso.integrations.api.SdkSession
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.SupportedChains
import com.circaso.integrations.api.TransferRequest
import com.circaso.integrations.impl.CustodyChainsConfig
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests: an in-memory custodian that records every call. A wallet appears only when the test says the user ran the
 * challenge ([completeSetup]), unless [autoCompleteSetup] is on; while [failure] is set, every call fails with it.
 */
class FakeCustody(
    private val clock: Clock = Clock.systemUTC(),
    private val autoCompleteSetup: Boolean = false,
    override val supported: SupportedChains = CustodyChainsConfig.TESTNETS,
) : StablecoinCustody {
    data class PinRequest(val circleUserId: String, val kind: PinChallenge, val idempotencyKey: UUID)

    val users: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val wallets = ConcurrentHashMap<String, List<CustodyWallet>>()
    val balances = ConcurrentHashMap<String, Money>()

    /** Held amounts by wallet id and asset symbol, in minor units; anything not set is zero. */
    val assetBalances = ConcurrentHashMap<String, Map<String, BigInteger>>()
    val transactions = ConcurrentHashMap<String, CustodyTransaction>()
    val transfers: MutableList<Pair<String, TransferRequest>> = CopyOnWriteArrayList()
    val pinRequests: MutableList<PinRequest> = CopyOnWriteArrayList()
    val invalidAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var failure: IntegrationError? = null

    private val challenges = AtomicInteger()

    /** What Circle does once the user has signed: the wallet on [blockchain] exists, `LIVE`. */
    fun completeSetup(
        circleUserId: String,
        address: String? = null,
        blockchain: String = supported.default,
    ): CustodyWallet {
        val wallet =
            CustodyWallet(
                walletId = UUID.randomUUID().toString(),
                address = address ?: randomAddress(blockchain),
                blockchain = blockchain,
                accountType = CustodyChainsConfig.defaultAccountType(blockchain),
                state = CustodyWalletState.LIVE,
            )
        wallets.merge(circleUserId, listOf(wallet)) { old, new -> old.filter { it.blockchain != blockchain } + new }
        return wallet
    }

    override suspend fun createUser(circleUserId: String): Resource<Unit> =
        guarded {
            users += circleUserId
            Resource.success(Unit)
        }

    override suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge> =
        guarded {
            if (circleUserId !in users) return@guarded Resource.error(IntegrationError.Rejected("Unknown user."))
            if (wallets[circleUserId].orEmpty().isNotEmpty()) {
                return@guarded Resource.success(Challenge("", session(circleUserId)))
            }
            if (autoCompleteSetup) completeSetup(circleUserId)
            Resource.success(challenge(circleUserId))
        }

    override suspend fun addWallet(
        circleUserId: String,
        blockchain: String,
        idempotencyKey: UUID,
    ): Resource<Challenge> =
        guarded {
            if (supported.find(blockchain) == null) {
                return@guarded Resource.error(IntegrationError.Rejected("$blockchain is not a supported chain."))
            }
            if (wallets[circleUserId].orEmpty().isEmpty()) {
                return@guarded Resource.error(IntegrationError.Rejected("The user has no wallet yet."))
            }
            if (autoCompleteSetup) completeSetup(circleUserId, blockchain = blockchain)
            Resource.success(challenge(circleUserId))
        }

    /** Every chain at once, as Circle does for one account type. */
    override suspend fun addWallets(
        circleUserId: String,
        blockchains: List<String>,
        idempotencyKey: UUID,
    ): Resource<Challenge> {
        var last: Resource<Challenge> = Resource.error(IntegrationError.Rejected("No chains to add."))
        for (blockchain in blockchains) {
            last = addWallet(circleUserId, blockchain, idempotencyKey)
            if (last is Resource.Error) return last
        }
        return last
    }

    override suspend fun sdkSession(circleUserId: String): Resource<SdkSession> =
        guarded { Resource.success(session(circleUserId)) }

    override suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>> =
        guarded { Resource.success(wallets[circleUserId].orEmpty()) }

    override suspend fun balance(
        circleUserId: String,
        walletId: String,
        asset: Asset,
    ): Resource<Money> = guarded { Resource.success(balances[walletId] ?: Money(0, asset)) }

    override suspend fun balances(
        circleUserId: String,
        walletId: String,
        blockchain: String,
    ): Resource<List<AssetBalance>> =
        guarded {
            val chain =
                supported.find(blockchain)
                    ?: return@guarded Resource.error(IntegrationError.Rejected("$blockchain is not a supported chain."))
            val held = assetBalances[walletId].orEmpty()
            Resource.success(chain.assets.map { AssetBalance(it, held[it.symbol] ?: BigInteger.ZERO) })
        }

    override suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge> =
        guarded {
            transfers += circleUserId to request
            Resource.success(challenge(circleUserId))
        }

    override suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction> =
        guarded {
            transactions[circleTxId]?.let { Resource.success(it) }
                ?: Resource.error(IntegrationError.Rejected("Unknown transaction."))
        }

    override suspend fun validateAddress(blockchain: String, address: String): Resource<Boolean> =
        guarded {
            val format = if (blockchain.startsWith("SOL")) SOLANA_ADDRESS else ADDRESS
            Resource.success(format.matches(address) && address !in invalidAddresses)
        }

    override suspend fun pinChallenge(
        circleUserId: String,
        kind: PinChallenge,
        idempotencyKey: UUID,
    ): Resource<Challenge> =
        guarded {
            pinRequests += PinRequest(circleUserId, kind, idempotencyKey)
            Resource.success(challenge(circleUserId))
        }

    private inline fun <T> guarded(block: () -> Resource<T>): Resource<T> =
        failure?.let { Resource.error(it) } ?: block()

    private fun challenge(circleUserId: String) =
        Challenge("fake-challenge-${challenges.incrementAndGet()}", session(circleUserId))

    private fun session(circleUserId: String) =
        SdkSession(
            appId = "fake-app",
            userToken = "fake-user-token-$circleUserId",
            encryptionKey = "fake-encryption-key",
            expiresAt = clock.instant().plus(Duration.ofMinutes(60)),
        )

    private companion object {
        val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
        val SOLANA_ADDRESS = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
        const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /** A made-up address in the chain's own form: a Solana address is base58, an EVM one hex. */
        fun randomAddress(blockchain: String): String {
            val random = UUID.randomUUID().toString().replace("-", "")
            if (blockchain.startsWith("SOL")) {
                return (random + random).map { BASE58[it.digitToInt(16) * 3 % BASE58.length] }.joinToString("").take(44)
            }
            return "0x" + random.padEnd(40, '0')
        }
    }
}

/**
 * Local runs and the sandbox: [FakeCustody] with wallets that appear as soon as they are asked for (there is no phone
 * SDK to run the challenge), printing each call. Addresses and amounts are never printed.
 */
class LogCustody(private val fake: FakeCustody = FakeCustody(autoCompleteSetup = true)) : StablecoinCustody {
    private val log = LoggerFactory.getLogger(LogCustody::class.java)

    override val supported: SupportedChains get() = fake.supported

    override suspend fun createUser(circleUserId: String): Resource<Unit> =
        fake.createUser(circleUserId).also { log.info("custody create user {}", circleUserId) }

    override suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge> =
        fake.createWallet(circleUserId, idempotencyKey).also { log.info("custody create wallet for {}", circleUserId) }

    override suspend fun addWallet(
        circleUserId: String,
        blockchain: String,
        idempotencyKey: UUID,
    ): Resource<Challenge> =
        fake.addWallet(circleUserId, blockchain, idempotencyKey).also {
            log.info("custody add a {} wallet for {}", blockchain, circleUserId)
        }

    override suspend fun addWallets(
        circleUserId: String,
        blockchains: List<String>,
        idempotencyKey: UUID,
    ): Resource<Challenge> =
        fake.addWallets(circleUserId, blockchains, idempotencyKey).also {
            log.info("custody add {} wallets for {}", blockchains, circleUserId)
        }

    override suspend fun sdkSession(circleUserId: String): Resource<SdkSession> =
        fake.sdkSession(circleUserId).also { log.info("custody sdk session for {}", circleUserId) }

    override suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>> =
        fake.wallets(circleUserId).also { log.info("custody wallets of {}", circleUserId) }

    override suspend fun balance(
        circleUserId: String,
        walletId: String,
        asset: Asset,
    ): Resource<Money> =
        fake.balance(circleUserId, walletId, asset).also { log.info("custody balance of {}", walletId) }

    override suspend fun balances(
        circleUserId: String,
        walletId: String,
        blockchain: String,
    ): Resource<List<AssetBalance>> =
        fake.balances(circleUserId, walletId, blockchain).also { log.info("custody balances of {}", walletId) }

    override suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge> =
        fake.prepareTransfer(circleUserId, request).also {
            log.info("custody transfer from {} (ref {})", request.walletId, request.refId)
        }

    override suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction> =
        fake.transaction(circleUserId, circleTxId).also { log.info("custody transaction {}", circleTxId) }

    override suspend fun validateAddress(blockchain: String, address: String): Resource<Boolean> =
        fake.validateAddress(blockchain, address).also { log.info("custody validate an address on {}", blockchain) }

    override suspend fun pinChallenge(
        circleUserId: String,
        kind: PinChallenge,
        idempotencyKey: UUID,
    ): Resource<Challenge> =
        fake
            .pinChallenge(
                circleUserId,
                kind,
                idempotencyKey,
            ).also { log.info("custody pin {} for {}", kind, circleUserId) }
}
