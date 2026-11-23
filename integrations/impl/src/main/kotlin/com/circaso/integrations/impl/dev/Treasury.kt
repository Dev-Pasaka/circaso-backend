package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.integrations.api.DepositQuery
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MerchantFloat
import com.circaso.integrations.api.TreasuryAccess
import com.circaso.integrations.api.TreasuryBalance
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryRefusal
import com.circaso.integrations.api.TreasuryTransaction
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.integrations.api.TreasuryWallet
import com.circaso.integrations.api.TreasuryWalletRequest
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Network
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests: Circaso's own wallets in memory, with no money anywhere. Wallets are made up from their `refId` and chain (so
 * asking again answers the same wallet), hold only USDC, and a send stays in flight until the test settles it
 * ([settle]); the same idempotency key never sends twice. [usdcContract] is the contract a wallet's USDC is reported
 * under, which callers match against the network's settings. While [failure] is set, every call fails with it.
 *
 * It is also the float reader ([MerchantFloat]) over the same balances, so one instance serves a test's app side and
 * its ops side.
 */
class FakeTreasury(
    private val usdcContract: (Network, String) -> String = { _, _ -> USDC_CONTRACT },
    /** `log` only: every wallet always holds [ENDLESS_FLOAT], and a send is confirmed at once. */
    private val endless: Boolean = false,
) : TreasuryCustody,
    MerchantFloat {
    val wallets = ConcurrentHashMap<String, TreasuryWallet>()
    val balances = ConcurrentHashMap<String, BigInteger>()
    val sends: MutableList<TreasuryTransfer> = CopyOnWriteArrayList()
    val transactions = ConcurrentHashMap<String, TreasuryTransaction>()
    val walletRequests: MutableList<TreasuryWalletRequest> = CopyOnWriteArrayList()
    private val byKey = ConcurrentHashMap<UUID, String>()
    private val accesses = ConcurrentHashMap<Network, TreasuryAccess>()

    /** What the provider recorded arriving in each chain transaction, by its hash; set by a test. */
    val deposits = ConcurrentHashMap<String, List<CustodyTransaction>>()

    @Volatile
    var failure: IntegrationError? = null

    /** What this stand-in may do on [network]; everything unless a test says otherwise. */
    fun grant(network: Network, access: TreasuryAccess) {
        accesses[network] = access
    }

    /** Puts [usdcMinor] of USDC into [walletId]. */
    fun fund(walletId: String, usdcMinor: Long) {
        balances[walletId] = BigInteger.valueOf(usdcMinor)
    }

    /** The chain confirmed (or failed) the transaction [transactionId]. */
    fun settle(
        transactionId: String,
        state: TreasuryTransactionState = TreasuryTransactionState.CONFIRMED,
    ): TreasuryTransaction {
        val hash = if (state == TreasuryTransactionState.CONFIRMED) hashOf(transactionId) else ""
        val reason = if (state == TreasuryTransactionState.FAILED) "FAILED_ON_CHAIN" else ""
        val settled = transactions.getValue(transactionId).copy(state = state, txHash = hash, failureReason = reason)
        transactions[transactionId] = settled
        return settled
    }

    override fun access(network: Network): TreasuryAccess = accesses[network] ?: TreasuryAccess.FULL

    override suspend fun createWalletSet(
        network: Network,
        name: String,
        idempotencyKey: UUID,
    ): Resource<String> = failure?.let { Resource.error(it) } ?: Resource.success("dev-set-${network.name.lowercase()}")

    override suspend fun createWallets(
        network: Network,
        request: TreasuryWalletRequest,
    ): Resource<List<TreasuryWallet>> {
        failure?.let { return Resource.error(it) }
        walletRequests += request
        return Resource.success(request.blockchains.map { walletOf(request, it) })
    }

    override suspend fun deriveWallet(
        network: Network,
        walletId: String,
        blockchain: String,
        name: String,
        refId: String,
    ): Resource<TreasuryWallet> = Resource.error(IntegrationError.NotImplemented("TreasuryCustody.deriveWallet", "dev"))

    override suspend fun findWallets(
        network: Network,
        address: String?,
        blockchain: String?,
        refId: String?,
    ): Resource<List<TreasuryWallet>> {
        failure?.let { return Resource.error(it) }
        return Resource.success(
            wallets.values.filter {
                (address == null || it.address == address) &&
                    (blockchain == null || it.blockchain == blockchain) &&
                    (refId == null || it.refId == refId)
            },
        )
    }

    override suspend fun balances(network: Network, walletId: String): Resource<List<TreasuryBalance>> {
        failure?.let { return Resource.error(it) }
        val chain = wallets[walletId]?.blockchain ?: chainOf(walletId)
        return Resource.success(
            listOf(
                TreasuryBalance(
                    tokenId = "dev-usdc",
                    symbol = Asset.USDC.symbol,
                    tokenAddress = usdcContract(network, chain),
                    decimals = Asset.USDC.decimals,
                    native = false,
                    amountMinor = held(walletId),
                ),
            ),
        )
    }

    override suspend fun balance(network: Network, walletId: String): Resource<Money> {
        failure?.let { return Resource.error(it) }
        return Resource.success(Money(held(walletId), Asset.USDC))
    }

    override suspend fun deposits(network: Network, query: DepositQuery): Resource<List<CustodyTransaction>> {
        failure?.let { return Resource.error(it) }
        if (endless) return Resource.success(listOf(expected(query)))
        return Resource.success(deposits[query.txHash].orEmpty().filter { it.walletId == query.walletId })
    }

    override suspend fun estimateTransfer(network: Network, transfer: TreasuryTransfer): Resource<BigDecimal> =
        Resource.success(BigDecimal.ZERO)

    override suspend fun send(network: Network, transfer: TreasuryTransfer): Resource<TreasuryTransaction> {
        failure?.let { return Resource.error(it) }
        byKey[transfer.idempotencyKey]?.let { return Resource.success(transactions.getValue(it)) }
        if (!endless) {
            if (held(transfer.walletId) <
                transfer.amountMinor
            ) {
                return Resource.error(TreasuryRefusal(BAD_REQUEST, null))
            }
            balances.merge(transfer.walletId, transfer.amountMinor.negate(), BigInteger::add)
            wallets.values.firstOrNull { it.address == transfer.destinationAddress }?.let { to ->
                balances.merge(to.walletId, transfer.amountMinor, BigInteger::add)
            }
        }
        sends += transfer
        val id = "dev-tx-${transfer.idempotencyKey}"
        val transaction =
            if (endless) {
                TreasuryTransaction(id, TreasuryTransactionState.CONFIRMED, hashOf(id), failureReason = "")
            } else {
                TreasuryTransaction(id, TreasuryTransactionState.IN_FLIGHT, txHash = "", failureReason = "")
            }
        transactions[id] = transaction
        byKey[transfer.idempotencyKey] = id
        return Resource.success(transaction)
    }

    override suspend fun transaction(network: Network, transactionId: String): Resource<TreasuryTransaction> {
        failure?.let { return Resource.error(it) }
        transactions[transactionId]?.let { return Resource.success(it) }
        // `log` after a restart: the transaction this process made up is gone, and it was confirmed when it was made.
        if (endless) {
            val confirmed = TreasuryTransactionState.CONFIRMED
            return Resource.success(TreasuryTransaction(transactionId, confirmed, hashOf(transactionId), ""))
        }
        return Resource.error(TreasuryRefusal(NOT_FOUND, null))
    }

    private fun held(walletId: String): BigInteger =
        if (endless) {
            ENDLESS_FLOAT
        } else {
            balances[walletId]
                ?: BigInteger.ZERO
        }

    private fun walletOf(request: TreasuryWalletRequest, blockchain: String): TreasuryWallet {
        val id = "dev-${request.refId}-$blockchain"
        return wallets.computeIfAbsent(id) {
            TreasuryWallet(
                walletId = id,
                walletSetId = request.walletSetId,
                blockchain = blockchain,
                address = addressOf(id),
                accountType = request.accountType,
                refId = request.refId,
                developerOwned = true,
            )
        }
    }

    companion object {
        /** A made-up contract for tests that don't care which. */
        const val USDC_CONTRACT = "0x00000000000000000000000000000000000000dc"

        /** 1 000 USDC: what every wallet holds, always, under `log`. */
        val ENDLESS_FLOAT: BigInteger = BigInteger.valueOf(1_000_000_000)
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404

        /** The chain a made-up wallet id names (`dev-<refId>-<chain>`), for a wallet this process didn't make. */
        private fun chainOf(walletId: String): String = walletId.substringAfter("dev-").drop(UUID_LENGTH + 1)

        private const val UUID_LENGTH = 36

        private fun hex(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString().replace("-", "")

        /** A made-up EVM address: stable for the same wallet id. */
        fun addressOf(walletId: String): String = "0x" + (hex(walletId) + hex("$walletId:b")).take(ADDRESS_HEX)

        fun hashOf(transactionId: String): String = "0x" + hex(transactionId) + hex("$transactionId:b")

        private const val ADDRESS_HEX = 40
    }
}

/**
 * Local runs (`log`): no wallet exists at any provider and no coin moves. Wallets are made up, every one always
 * holds 1 000 USDC, and a send is confirmed at once, so an on-ramp completes on a developer's machine. It prints that
 * it was asked: never an amount or an address. Its answers don't depend on what this process remembers, so the app
 * and the ops process, which share nothing but the database, agree.
 */
class LogTreasury(
    usdcContract: (Network, String) -> String = { _, _ -> FakeTreasury.USDC_CONTRACT },
    private val delegate: FakeTreasury = FakeTreasury(usdcContract, endless = true),
) : TreasuryCustody by delegate {
    private val log = LoggerFactory.getLogger(LogTreasury::class.java)

    override suspend fun createWallets(
        network: Network,
        request: TreasuryWalletRequest,
    ): Resource<List<TreasuryWallet>> =
        delegate.createWallets(network, request).also {
            log.info(
                "treasury (log): made up {} wallet(s) on {} for refId={}",
                request.blockchains.size,
                network,
                request.refId,
            )
        }

    override suspend fun send(network: Network, transfer: TreasuryTransfer): Resource<TreasuryTransaction> =
        delegate.send(network, transfer).also {
            log.info("treasury (log): transfer on {} for refId={}, reported as confirmed", network, transfer.refId)
        }
}

/**
 * Local runs (`log`): every merchant float wallet always holds 1 000 USDC, and every deposit a caller looks for is
 * reported as arrived and confirmed, exactly as expected (no coin moves on a developer's machine, so there is no
 * record to read). Nothing is read from anywhere.
 */
class LogMerchantFloat : MerchantFloat {
    private val log = LoggerFactory.getLogger(LogMerchantFloat::class.java)

    override suspend fun balance(network: Network, walletId: String): Resource<Money> =
        Resource.success(Money(FakeTreasury.ENDLESS_FLOAT, Asset.USDC))

    override suspend fun deposits(network: Network, query: DepositQuery): Resource<List<CustodyTransaction>> {
        log.info("merchant float (log): deposit on {} reported as arrived, as expected", query.blockchain)
        return Resource.success(listOf(expected(query)))
    }
}

/** The deposit [query] looks for, as a confirmed incoming USDC transfer: what the local stand-ins answer. */
internal fun expected(query: DepositQuery): CustodyTransaction =
    CustodyTransaction(
        circleTxId = "dev-deposit-${query.txHash}",
        circleUserId = "",
        walletId = query.walletId,
        blockchain = query.blockchain,
        direction = CustodyDirection.IN,
        state = CustodyTransactionState.CONFIRMED,
        amount = Money(query.expectedAmountMinor, Asset.USDC),
        sourceAddress = query.expectedFrom,
        destinationAddress = "",
        refId = "",
        txHash = query.txHash,
        failureReason = "",
        updatedAt = Instant.EPOCH,
        developerWallet = true,
        tokenAddress = query.expectedTokenAddress,
    )
