package com.circaso.integrations.impl.dev

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
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests (and the sandbox until Circle keys are set): an in-memory custodian that records every call. A wallet appears
 * only when the test says the user ran the challenge ([completeSetup]), unless [autoCompleteSetup] is on; while
 * [failure] is set, every call fails with it.
 */
class FakeCustody(private val clock: Clock = Clock.systemUTC(), private val autoCompleteSetup: Boolean = false) :
    StablecoinCustody {
    data class PinRequest(val circleUserId: String, val kind: PinChallenge, val idempotencyKey: UUID)

    val users: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val wallets = ConcurrentHashMap<String, List<CustodyWallet>>()
    val balances = ConcurrentHashMap<String, Money>()
    val transactions = ConcurrentHashMap<String, CustodyTransaction>()
    val transfers: MutableList<Pair<String, TransferRequest>> = CopyOnWriteArrayList()
    val pinRequests: MutableList<PinRequest> = CopyOnWriteArrayList()
    val invalidAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var failure: IntegrationError? = null

    private val challenges = AtomicInteger()

    /** What Circle does once the user has set the PIN: the wallet exists, `LIVE`, on Base Sepolia. */
    fun completeSetup(circleUserId: String, address: String = randomAddress()): CustodyWallet {
        val wallet =
            CustodyWallet(
                walletId = UUID.randomUUID().toString(),
                address = address.lowercase(),
                blockchain = "BASE-SEPOLIA",
                accountType = "SCA",
                state = CustodyWalletState.LIVE,
            )
        wallets[circleUserId] = listOf(wallet)
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

    override suspend fun sdkSession(circleUserId: String): Resource<SdkSession> =
        guarded { Resource.success(session(circleUserId)) }

    override suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>> =
        guarded { Resource.success(wallets[circleUserId].orEmpty()) }

    override suspend fun balance(
        circleUserId: String,
        walletId: String,
        asset: Asset,
    ): Resource<Money> = guarded { Resource.success(balances[walletId] ?: Money(0, asset)) }

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

    override suspend fun validateAddress(address: String): Resource<Boolean> =
        guarded { Resource.success(ADDRESS.matches(address) && address.lowercase() !in invalidAddresses) }

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

        fun randomAddress(): String =
            "0x" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .padEnd(40, '0')
    }
}

/**
 * Local runs: [FakeCustody] with wallets that appear as soon as they are asked for (there is no phone SDK to run the
 * challenge), printing each call. Addresses and amounts are never printed.
 */
class LogCustody(private val fake: FakeCustody = FakeCustody(autoCompleteSetup = true)) : StablecoinCustody {
    private val log = LoggerFactory.getLogger(LogCustody::class.java)

    override suspend fun createUser(circleUserId: String): Resource<Unit> =
        fake.createUser(circleUserId).also { log.info("custody create user {}", circleUserId) }

    override suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge> =
        fake.createWallet(circleUserId, idempotencyKey).also { log.info("custody create wallet for {}", circleUserId) }

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

    override suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge> =
        fake.prepareTransfer(circleUserId, request).also {
            log.info("custody transfer from {} (ref {})", request.walletId, request.refId)
        }

    override suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction> =
        fake.transaction(circleUserId, circleTxId).also { log.info("custody transaction {}", circleTxId) }

    override suspend fun validateAddress(address: String): Resource<Boolean> =
        fake.validateAddress(address).also { log.info("custody validate an address") }

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
