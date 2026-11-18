package com.circaso.wallet

import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.CustodyChain
import com.circaso.integrations.api.CustodySeeds
import com.circaso.integrations.api.CustodySetup
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.SupportedChains
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
import com.circaso.wallet.domain.models.ChainFee
import com.circaso.wallet.domain.models.FeeDestination
import com.circaso.wallet.domain.models.FeeWallet
import com.circaso.wallet.domain.models.FeeWalletNetwork
import com.circaso.wallet.domain.models.FeeWithdrawal
import com.circaso.wallet.domain.models.WithdrawalChange
import com.circaso.wallet.domain.models.WithdrawalState
import com.circaso.wallet.domain.repositories.FeeDestinationRepository
import com.circaso.wallet.domain.repositories.FeeWalletRepository
import com.circaso.wallet.domain.repositories.FeeWithdrawalRepository
import com.circaso.wallet.domain.support.FeeDestinations
import com.circaso.wallet.domain.support.FeeWalletBalances
import com.circaso.wallet.domain.support.FeeWalletSetup
import com.circaso.wallet.domain.support.FeeWithdrawals
import com.circaso.wallet.domain.support.Networks
import kotlinx.coroutines.yield
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

const val USDC_BASE_MAINNET = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"

private fun usdc(address: String) = CustodyAsset("USDC", address, 6, usdPegged = true)

/** The mainnet as decided (BASE first, MATIC, ETH, SOL) and the test chains (Base Sepolia, Solana Devnet, Amoy). */
object FeeSeeds : CustodySeeds {
    val MAINNET =
        SupportedChains(
            default = "BASE",
            chains =
                listOf(
                    CustodyChain("BASE", listOf(usdc(USDC_BASE_MAINNET))),
                    CustodyChain("MATIC", listOf(usdc("0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359"))),
                    CustodyChain("ETH", listOf(usdc("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"))),
                    CustodyChain("SOL", listOf(usdc("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))),
                ),
        )

    override fun seed(network: Network): CustodySetup =
        if (network == Network.TESTNET) CustodySetup("test-app", TEST_CHAINS) else CustodySetup("", MAINNET)
}

/**
 * Circle's developer-controlled wallets in memory, with the two properties the fee wallets lean on: a repeated
 * idempotency key answers the first result, and deriving a wallet on another chain keeps its address.
 */
class FakeTreasury(private val access: MutableMap<Network, TreasuryAccess> = ConcurrentHashMap()) : TreasuryCustody {
    val calls: MutableList<String> = CopyOnWriteArrayList()
    val wallets: MutableList<TreasuryWallet> = CopyOnWriteArrayList()
    val held = ConcurrentHashMap<String, List<TreasuryBalance>>()
    val sends: MutableList<TreasuryTransfer> = CopyOnWriteArrayList()
    val transactions = ConcurrentHashMap<String, TreasuryTransaction>()

    /** Refusals and outages by operation name (`createWalletSet`, `createWallets`, `deriveWallet`, `send`, ...). */
    val failing = ConcurrentHashMap<String, IntegrationError>()

    @Volatile
    var estimate: Resource<BigDecimal> = Resource.success(BigDecimal("0.00002"))

    private val setsByKey = ConcurrentHashMap<UUID, String>()
    private val walletsByKey = ConcurrentHashMap<UUID, List<TreasuryWallet>>()
    private val sentByKey = ConcurrentHashMap<UUID, TreasuryTransaction>()

    fun grant(network: Network, level: TreasuryAccess) {
        access[network] = level
    }

    fun count(operation: String): Int = calls.count { it == operation }

    /** A wallet that exists at Circle already (made by hand, or by the old script). */
    fun existing(
        blockchain: String,
        address: String,
        refId: String = "circaso-fees",
        developerOwned: Boolean = true,
        walletSetId: String = "set-old",
    ): TreasuryWallet =
        TreasuryWallet("w-${wallets.size + 1}", walletSetId, blockchain, address, "EOA", refId, developerOwned)
            .also { wallets += it }

    override fun access(network: Network): TreasuryAccess = access[network] ?: TreasuryAccess.NO_ACCOUNT

    /** False while Circle's wallet list doesn't show what was just made. */
    @Volatile
    var listable = true

    private suspend fun <T> call(operation: String, block: () -> Resource<T>): Resource<T> {
        // Every call to Circle is a place another server's run can get in between.
        yield()
        calls += operation
        failing[operation]?.let { return Resource.error(it) }
        return block()
    }

    override suspend fun createWalletSet(
        network: Network,
        name: String,
        idempotencyKey: UUID,
    ): Resource<String> =
        call("createWalletSet") {
            check(canSign(network)) { "signed without the entity secret" }
            Resource.success(setsByKey.computeIfAbsent(idempotencyKey) { "set-${setsByKey.size + 1}" })
        }

    override suspend fun createWallets(
        network: Network,
        request: TreasuryWalletRequest,
    ): Resource<List<TreasuryWallet>> =
        call("createWallets") {
            check(canSign(network)) { "signed without the entity secret" }
            Resource.success(
                walletsByKey.computeIfAbsent(request.idempotencyKey) {
                    val address = "0x" + "%040x".format(request.walletSetId.hashCode().toLong() and 0xffffffffL)
                    request.blockchains.map { chain ->
                        TreasuryWallet(
                            "w-${wallets.size + 1}",
                            request.walletSetId,
                            chain,
                            address,
                            "EOA",
                            request.refId,
                            developerOwned = true,
                        ).also { wallets += it }
                    }
                },
            )
        }

    override suspend fun deriveWallet(
        network: Network,
        walletId: String,
        blockchain: String,
        name: String,
        refId: String,
    ): Resource<TreasuryWallet> =
        call("deriveWallet") {
            val source = wallets.first { it.walletId == walletId }
            val there =
                wallets.firstOrNull { it.blockchain == blockchain && it.address == source.address }
                    ?: source.copy(walletId = "w-${wallets.size + 1}", blockchain = blockchain).also { wallets += it }
            Resource.success(there)
        }

    override suspend fun findWallets(
        network: Network,
        address: String?,
        blockchain: String?,
        refId: String?,
    ): Resource<List<TreasuryWallet>> =
        call("findWallets") {
            Resource.success(
                wallets.filter {
                    listable &&
                        Network.ofChain(it.blockchain) == network &&
                        (address == null || it.address == address) &&
                        (blockchain == null || it.blockchain == blockchain) &&
                        (refId == null || it.refId == refId)
                },
            )
        }

    override suspend fun balances(network: Network, walletId: String): Resource<List<TreasuryBalance>> =
        call("balances") { Resource.success(held[walletId].orEmpty()) }

    override suspend fun estimateTransfer(network: Network, transfer: TreasuryTransfer): Resource<BigDecimal> =
        call("estimateTransfer") { estimate }

    override suspend fun send(network: Network, transfer: TreasuryTransfer): Resource<TreasuryTransaction> =
        call("send") {
            check(canSign(network)) { "signed without the entity secret" }
            sends += transfer
            Resource.success(
                sentByKey.computeIfAbsent(transfer.idempotencyKey) {
                    TreasuryTransaction("tx-${sentByKey.size + 1}", TreasuryTransactionState.IN_FLIGHT, "", "")
                        .also { transactions[it.transactionId] = it }
                },
            )
        }

    override suspend fun transaction(network: Network, transactionId: String): Resource<TreasuryTransaction> =
        call("transaction") {
            transactions[transactionId]?.let { Resource.success(it) } ?: Resource.error(TreasuryRefusal(404, null))
        }

    /** Circle's word on a transaction changes. */
    fun settle(
        transactionId: String,
        state: TreasuryTransactionState,
        txHash: String = "0xhash-$transactionId",
        failureReason: String = "",
    ) {
        transactions[transactionId] = TreasuryTransaction(transactionId, state, txHash, failureReason)
    }

    companion object {
        val DOWN = IntegrationError.Unavailable("Circle")
        val REFUSED = TreasuryRefusal(400, 156004)
    }
}

/** The fee wallets' rows in memory; the fee addresses are the settings fake's `chain_fees`, as in the database. */
class MemoryFeeWallets(private val fees: MutableMap<String, ChainFee>) : FeeWalletRepository {
    val networks = ConcurrentHashMap<Network, FeeWalletNetwork>()
    val rows = ConcurrentHashMap<String, FeeWallet>()

    @Volatile
    var delayHours = 24

    override suspend fun network(network: Network): FeeWalletNetwork? = networks[network]

    override suspend fun saveNetwork(row: FeeWalletNetwork) {
        networks.compute(row.network) { _, old -> row.copy(walletSetId = row.walletSetId ?: old?.walletSetId) }
    }

    override suspend fun wallets(network: Network): List<FeeWallet> =
        rows.values.filter { it.network == network }.sortedBy { it.blockchain }

    override suspend fun record(wallet: FeeWallet): FeeWallet {
        val old = rows[wallet.blockchain]
        if (old == null) {
            rows[wallet.blockchain] = wallet
            if (fees[wallet.blockchain]?.feeAddress.isNullOrBlank()) {
                fees[wallet.blockchain] =
                    ChainFee(wallet.blockchain, wallet.address, fees[wallet.blockchain]?.nativeUsd)
            }
        } else if (old.circleWalletId == null && wallet.circleWalletId != null && old.address == wallet.address) {
            rows[wallet.blockchain] =
                old.copy(
                    circleWalletId = wallet.circleWalletId,
                    walletSetId = wallet.walletSetId,
                    origin = wallet.origin,
                )
        }
        return rows.getValue(wallet.blockchain)
    }

    override suspend fun feeAddresses(): Map<String, String> =
        fees.values
            .filter { !it.feeAddress.isNullOrBlank() }
            .associate { it.blockchain to it.feeAddress!!.trim() }

    override suspend fun destinationDelayHours(): Int = delayHours
}

class MemoryFeeDestinations : FeeDestinationRepository {
    val rows: MutableList<FeeDestination> = CopyOnWriteArrayList()

    override suspend fun live(network: Network?): List<FeeDestination> =
        rows
            .filter { it.removedAt == null && (network == null || it.network == network) }
            .sortedByDescending { it.createdAt }

    override suspend fun find(id: UUID): FeeDestination? = rows.firstOrNull { it.id == id }

    override suspend fun add(destination: FeeDestination): Boolean {
        val taken =
            rows.any {
                it.removedAt == null && it.blockchain == destination.blockchain && it.address == destination.address
            }
        if (taken) return false
        rows += destination
        return true
    }

    override suspend fun remove(
        id: UUID,
        by: UUID,
        at: Instant,
    ): Boolean {
        val index = rows.indexOfFirst { it.id == id && it.removedAt == null }
        if (index < 0) return false
        rows[index] = rows[index].copy(removedAt = at)
        return true
    }
}

/** `fee_withdrawals` in memory, with the table's own rules: one row per key, a move only from the state named. */
class MemoryFeeWithdrawals : FeeWithdrawalRepository {
    val rows = ConcurrentHashMap<UUID, FeeWithdrawal>()

    /** Every state each withdrawal has been in, in order. */
    val history = ConcurrentHashMap<UUID, List<WithdrawalState>>()

    override suspend fun insert(withdrawal: FeeWithdrawal): Boolean {
        val taken =
            rows.values.any {
                it.requestedBy == withdrawal.requestedBy && it.idempotencyKey == withdrawal.idempotencyKey
            }
        if (taken) return false
        rows[withdrawal.id] = withdrawal
        history[withdrawal.id] = listOf(withdrawal.state)
        return true
    }

    override suspend fun byIdempotencyKey(requestedBy: UUID, idempotencyKey: String): FeeWithdrawal? =
        rows.values.firstOrNull { it.requestedBy == requestedBy && it.idempotencyKey == idempotencyKey }

    override suspend fun find(id: UUID): FeeWithdrawal? = rows[id]

    override suspend fun page(
        network: Network?,
        before: UUID?,
        limit: Int,
    ): List<FeeWithdrawal> =
        rows.values
            .filter { network == null || it.network == network }
            .sortedByDescending { it.createdAt }
            .filter { before == null || it.createdAt < rows.getValue(before).createdAt }
            .take(limit)

    override suspend fun open(limit: Int): List<FeeWithdrawal> =
        rows.values
            .filter { it.state.isOpen }
            .sortedBy { it.createdAt }
            .take(limit)

    override suspend fun openOn(blockchain: String): List<FeeWithdrawal> =
        rows.values.filter { it.blockchain == blockchain && it.state.isOpen }

    override suspend fun move(
        id: UUID,
        from: WithdrawalState,
        to: WithdrawalState,
        change: WithdrawalChange,
        at: Instant,
    ): Boolean {
        val row = rows[id]?.takeIf { it.state == from } ?: return false
        val circleTxId = change.circleTxId ?: row.circleTxId
        check(to !in setOf(WithdrawalState.SENT, WithdrawalState.CONFIRMED) || circleTxId != null) {
            "a withdrawal can't look sent without Circle's transaction id"
        }
        rows[id] =
            row.copy(
                state = to,
                circleTxId = circleTxId,
                txHash = change.txHash ?: row.txHash,
                failureReason = change.failureReason ?: row.failureReason,
                updatedAt = at,
            )
        history[id] = history.getValue(id) + to
        return true
    }

    override suspend fun noteHash(
        id: UUID,
        txHash: String,
        at: Instant,
    ) {
        rows.computeIfPresent(id) { _, row ->
            if (row.state ==
                WithdrawalState.SENT
            ) {
                row.copy(txHash = txHash)
            } else {
                row
            }
        }
    }
}

/** The fee wallets over fakes: Circle, the rows and the settings in memory, both networks reachable by custody. */
class FeeWalletWorld(seedFees: Map<String, String> = emptyMap()) {
    val clock = MutableClock(Instant.parse("2026-11-19T09:00:00Z"))
    val settings = MemoryNetworkSettings()
    val treasury = FakeTreasury()
    val fakeCustody = FakeCustody()
    val custody: StablecoinCustody =
        object : StablecoinCustody by fakeCustody {
            override val networks: Set<Network> = Network.entries.toSet()
        }
    private val seeded =
        FakeFeeConfig().apply {
            addresses.clear()
            addresses.putAll(seedFees)
        }
    val networks = Networks(settings, FeeSeeds, seeded, custody, RecordingWarnings(), clock)
    val wallets = MemoryFeeWallets(settings.fees)
    val destinationRows = MemoryFeeDestinations()
    val withdrawalRows = MemoryFeeWithdrawals()
    val setup = FeeWalletSetup(treasury, networks, wallets, clock)
    val balances = FeeWalletBalances(treasury, networks, wallets)
    val destinations = FeeDestinations(destinationRows, wallets, networks, custody, clock)
    val withdrawals =
        FeeWithdrawals(treasury, withdrawalRows, destinationRows, wallets, balances, destinations, clock)

    /** Another server on the same database and the same Circle account. */
    fun secondSetup() = FeeWalletSetup(treasury, networks, wallets, clock)

    fun feeAddress(blockchain: String): String? = settings.fees[blockchain]?.feeAddress
}
