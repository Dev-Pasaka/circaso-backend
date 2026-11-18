package com.circaso.wallet.domain.support

import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryWallet
import com.circaso.integrations.api.TreasuryWalletRequest
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.FeeWallet
import com.circaso.wallet.domain.models.FeeWalletNetwork
import com.circaso.wallet.domain.models.FeeWalletOrigin
import com.circaso.wallet.domain.models.FeeWalletStatus
import com.circaso.wallet.domain.models.NetworkSettings
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.FeeWalletRepository
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Makes sure a network has its fee wallets (decision 2026-11-18), run by the ops server at start-up and every few
 * minutes after: one externally owned wallet at Circle, at the same address on every enabled chain that can carry a
 * fee, in the wallet set "Circaso fees" under the `refId` `circaso-fees`.
 *
 * Whatever is there already is kept:
 * 1. A chain whose fee address is already in `chain_fees` (the testnet's, seeded from the old variables) is
 *    **imported**: its wallet is looked up at Circle by that address and its id recorded. The address is never
 *    replaced. If Circle doesn't have it, the address stays, fees keep being collected at it, and it is recorded as
 *    address-only: no balance, no withdrawals.
 * 2. A chain with no address gets a wallet: derived from a fee wallet this network already has (so the address is the
 *    same), else adopted from wallets made earlier under our `refId`, else created (the wallet set, then one wallet on
 *    the default chain, then derived on the others). Its address goes into `chain_fees` in the same transaction.
 *
 * Safe to run again and on two servers at once: nothing is created that a row or Circle already has, every creation
 * carries the same idempotency key each time ([FeeChains.creationKey]), deriving is idempotent at Circle, and the rows
 * settle on their primary keys. It never throws: a failure is the network's status, with a reason that holds Circle's
 * HTTP status and error code at most, never a key or a secret.
 */
class FeeWalletSetup(
    private val treasury: TreasuryCustody?,
    private val networks: Networks,
    private val repository: FeeWalletRepository,
    private val clock: Clock,
    private val warnings: WalletWarnings? = null,
) {
    private class Outcome(val status: FeeWalletStatus, val reason: String?, val walletSetId: String? = null)

    /** What one run knows and has done so far. */
    private class Run(
        val network: Network,
        val chains: List<String>,
        val defaultChain: String,
        val rows: MutableMap<String, FeeWallet>,
        val addresses: Map<String, String>,
        var walletSetId: String?,
    ) {
        val notes = mutableListOf<String>()
        val failures = mutableListOf<String>()
    }

    suspend fun ensure(network: Network): FeeWalletNetwork {
        val outcome =
            try {
                run(network)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome(FeeWalletStatus.FAILED, "The fee wallet check stopped on an error (${e::class.simpleName}).")
            }
        val row = FeeWalletNetwork(network, outcome.walletSetId, outcome.status, outcome.reason, clock.instant())
        val before = repository.network(network)
        repository.saveNetwork(row)
        // Said once per change, not on every ten-minute check.
        if (before?.status != row.status || before.reason != row.reason) {
            warnings?.feeWalletsChecked(network.name, row.status.name, row.reason)
        }
        // A new fee address is in force in this process at once, and within seconds in the app's.
        networks.invalidate()
        return row
    }

    private suspend fun run(network: Network): Outcome {
        val settings = networks.settings(network)
        val chains = feeChains(settings)
        if (chains.isEmpty()) {
            return Outcome(
                FeeWalletStatus.NOT_CREATED,
                "No enabled chain on $network can carry a fee.",
            )
        }
        val run =
            Run(
                network = network,
                chains = chains,
                defaultChain = settings.defaultChain,
                rows = repository.wallets(network).associateBy { it.blockchain }.toMutableMap(),
                addresses = repository.feeAddresses(),
                walletSetId = repository.network(network)?.walletSetId,
            )
        val custody = treasury
        if (custody == null || !custody.canRead(network)) {
            keepAddresses(run)
            run.notes +=
                if (custody == null) {
                    "This server's custody provider isn't Circle, so it has no fee wallets of its own."
                } else {
                    "This server has no Circle API key for $network."
                }
            return outcomeOf(run, canSign = false, noted = true)
        }
        importKnown(run, custody)
        createMissing(run, custody)
        return outcomeOf(run, custody.canSign(network), noted = false)
    }

    /** The enabled chains a fee can be taken on, the default chain first. */
    private fun feeChains(settings: NetworkSettings): List<String> =
        settings.chains
            .filter { it.enabled && FeeChains.canCarryFee(it.blockchain) }
            .map { it.blockchain }
            .sortedBy { if (it == settings.defaultChain) 0 else 1 }

    /** Without Circle, the addresses fees are already collected at are recorded as they are. */
    private suspend fun keepAddresses(run: Run) {
        for (chain in run.chains) {
            val address = run.addresses[chain] ?: continue
            if (run.rows[chain] == null) run.rows[chain] = addressOnly(chain, address)
        }
    }

    private suspend fun importKnown(run: Run, custody: TreasuryCustody) {
        for (chain in run.chains) {
            val row = run.rows[chain]
            if (row?.circleWalletId != null) continue
            val address = row?.address ?: run.addresses[chain] ?: continue
            when (val found = lookUp(custody, run.network, chain, address)) {
                is Resource.Error -> {
                    if (row == null) run.rows[chain] = addressOnly(chain, address)
                    run.notes += "$chain: the fee wallet couldn't be looked up at Circle (${found.error.message})"
                }

                is Resource.Success -> {
                    val wallet = found.data
                    if (wallet == null) {
                        if (row == null) run.rows[chain] = addressOnly(chain, address)
                        run.notes +=
                            "$chain: the fee address isn't a wallet of this Circle account; fees are still " +
                            "collected at it, but its balance can't be read and nothing can be withdrawn from it."
                    } else {
                        run.rows[chain] = record(chain, address, wallet, FeeWalletOrigin.IMPORTED)
                        run.walletSetId = run.walletSetId ?: wallet.walletSetId.ifEmpty { null }
                    }
                }
            }
        }
    }

    /**
     * Circaso's own wallet on [chain] at [address], or null when this Circle account has none: asked by the address
     * as stored, then in lower case (Circle answers EVM addresses that way), then among the wallets under our `refId`.
     */
    private suspend fun lookUp(
        custody: TreasuryCustody,
        network: Network,
        chain: String,
        address: String,
    ): Resource<TreasuryWallet?> {
        val wanted = Addresses.normalise(address)

        fun match(wallets: List<TreasuryWallet>) =
            wallets.firstOrNull {
                it.developerOwned && it.blockchain == chain && Addresses.normalise(it.address) == wanted
            }
        val searches =
            listOfNotNull<suspend () -> Resource<List<TreasuryWallet>>>(
                { custody.findWallets(network, address = address, blockchain = chain) },
                if (wanted !=
                    address
                ) {
                    ({ custody.findWallets(network, address = wanted, blockchain = chain) })
                } else {
                    null
                },
                { custody.findWallets(network, blockchain = chain, refId = FeeChains.REF_ID) },
            )
        for (search in searches) {
            when (val found = search()) {
                is Resource.Error -> return found
                is Resource.Success -> match(found.data)?.let { return Resource.success(it) }
            }
        }
        return Resource.success(null)
    }

    private suspend fun createMissing(run: Run, custody: TreasuryCustody) {
        fun missing() = run.chains.filter { run.rows[it] == null }
        if (missing().isEmpty()) return
        var anchor = run.rows.values.firstOrNull { it.circleWalletId != null }
        if (anchor ==
            null
        ) {
            anchor = adoptEarlier(run, custody) ?: if (run.failures.isEmpty()) create(run, custody) else null
        }
        val source = anchor ?: return
        val sourceId = source.circleWalletId ?: return
        for (chain in missing()) {
            when (
                val derived =
                    custody.deriveWallet(run.network, sourceId, chain, FeeChains.WALLET_NAME, FeeChains.REF_ID)
            ) {
                is Resource.Error -> {
                    run.failures += "$chain: the fee wallet couldn't be derived (${derived.error.message})"
                }

                is Resource.Success -> {
                    val wallet = derived.data
                    if (wallet.blockchain != chain || Addresses.normalise(wallet.address) != source.address) {
                        run.failures += "$chain: Circle derived a wallet at another address; it isn't used."
                    } else {
                        run.rows[chain] = record(chain, wallet.address, wallet, FeeWalletOrigin.CREATED)
                    }
                }
            }
        }
    }

    /**
     * Fee wallets made earlier under our `refId` that no row knows (the database was restored, or a server stopped
     * between Circle's answer and the insert): those on a chain we need are recorded; any one of them can be derived
     * from. Null when there are none.
     */
    private suspend fun adoptEarlier(run: Run, custody: TreasuryCustody): FeeWallet? {
        val found = custody.findWallets(run.network, refId = FeeChains.REF_ID)
        if (found is Resource.Error) {
            run.failures += "Circle couldn't be asked for fee wallets made earlier (${found.error.message})"
            return null
        }
        val ours =
            (found as Resource.Success).data.filter {
                it.developerOwned &&
                    it.refId == FeeChains.REF_ID &&
                    it.accountType == EOA &&
                    FeeChains.canCarryFee(it.blockchain) &&
                    Network.ofChain(it.blockchain) == run.network
            }
        var anchor: FeeWallet? = null
        for (wallet in ours) {
            val adopted =
                if (wallet.blockchain in run.chains && run.rows[wallet.blockchain] == null) {
                    record(wallet.blockchain, wallet.address, wallet, FeeWalletOrigin.IMPORTED).also {
                        run.rows[wallet.blockchain] = it
                    }
                } else {
                    wallet.toFeeWallet(wallet.blockchain, wallet.address, FeeWalletOrigin.IMPORTED)
                }
            anchor = anchor ?: adopted.takeIf { it.circleWalletId != null }
            run.walletSetId = run.walletSetId ?: wallet.walletSetId.ifEmpty { null }
        }
        return anchor
    }

    /** The wallet set (unless one is known) and the first wallet, on the default chain when it can carry a fee. */
    private suspend fun create(run: Run, custody: TreasuryCustody): FeeWallet? {
        val network = run.network
        if (!custody.canSign(network)) {
            run.notes += "${noSecret(network)} Fee wallets can't be created without it."
            return null
        }
        val setId =
            run.walletSetId ?: when (
                val made =
                    custody.createWalletSet(
                        network,
                        FeeChains.WALLET_SET_NAME,
                        FeeChains.creationKey(network, "wallet-set"),
                    )
            ) {
                is Resource.Error -> {
                    run.failures += "The wallet set couldn't be created (${made.error.message})"
                    return null
                }

                is Resource.Success -> {
                    run.walletSetId = made.data
                    // Kept at once: if the next step fails, the next run uses this set instead of making another.
                    repository.saveNetwork(
                        FeeWalletNetwork(
                            network,
                            made.data,
                            FeeWalletStatus.NOT_CREATED,
                            "Creating the fee wallets.",
                            clock.instant(),
                        ),
                    )
                    made.data
                }
            }
        val first =
            run.defaultChain.takeIf { it in run.chains && run.rows[it] == null }
                ?: run.chains.first { run.rows[it] == null }
        val request =
            TreasuryWalletRequest(
                walletSetId = setId,
                blockchains = listOf(first),
                name = FeeChains.WALLET_NAME,
                refId = FeeChains.REF_ID,
                idempotencyKey = FeeChains.creationKey(network, "wallet:$first"),
            )
        return when (val made = custody.createWallets(network, request)) {
            is Resource.Error -> {
                run.failures += "$first: the fee wallet couldn't be created (${made.error.message})"
                null
            }

            is Resource.Success -> {
                val wallet = made.data.firstOrNull { it.blockchain == first }
                if (wallet == null) {
                    run.failures += "$first: Circle created no wallet on that chain."
                    null
                } else {
                    record(first, wallet.address, wallet, FeeWalletOrigin.CREATED).also { run.rows[first] = it }
                }
            }
        }
    }

    private fun outcomeOf(
        run: Run,
        canSign: Boolean,
        noted: Boolean,
    ): Outcome {
        val without = run.chains.filter { run.rows[it] == null }
        val status =
            when {
                run.failures.isNotEmpty() -> FeeWalletStatus.FAILED
                without.isEmpty() -> FeeWalletStatus.READY
                else -> FeeWalletStatus.NOT_CREATED
            }
        val parts = run.failures + run.notes
        val extra =
            when {
                status == FeeWalletStatus.READY && !canSign && !noted -> {
                    listOf("${noSecret(run.network)} Fees are collected; withdrawals aren't available.")
                }

                status == FeeWalletStatus.READY && noted -> {
                    listOf("Fees are collected at the configured addresses; balances and withdrawals aren't available.")
                }

                status != FeeWalletStatus.READY && without.isNotEmpty() -> {
                    listOf("No fee is taken on ${without.joinToString(", ")} until it has a fee wallet.")
                }

                else -> {
                    emptyList()
                }
            }
        val reason = (parts + extra).joinToString(" ").take(REASON_LENGTH).ifBlank { null }
        return Outcome(status, reason, run.walletSetId)
    }

    private suspend fun addressOnly(chain: String, address: String): FeeWallet =
        repository.record(
            FeeWallet(
                blockchain = chain,
                address = Addresses.normalise(address),
                circleWalletId = null,
                walletSetId = null,
                origin = FeeWalletOrigin.ADDRESS_ONLY,
                createdAt = clock.instant(),
            ),
        )

    private suspend fun record(
        chain: String,
        address: String,
        wallet: TreasuryWallet,
        origin: FeeWalletOrigin,
    ): FeeWallet = repository.record(wallet.toFeeWallet(chain, address, origin))

    private fun TreasuryWallet.toFeeWallet(
        chain: String,
        address: String,
        origin: FeeWalletOrigin,
    ) = FeeWallet(
        blockchain = chain,
        address = Addresses.normalise(address),
        circleWalletId = walletId,
        walletSetId = walletSetId.ifEmpty { null },
        origin = origin,
        createdAt = clock.instant(),
    )

    companion object {
        private const val EOA = "EOA"
        private const val REASON_LENGTH = 1_000

        /** Names the secret that is missing (its name, never a value). */
        fun noSecret(network: Network): String {
            val name = if (network == Network.TESTNET) "CIRCLE_ENTITY_SECRET" else "CIRCLE_MAINNET_ENTITY_SECRET"
            return "This server has no entity secret for $network ($name)."
        }
    }
}
