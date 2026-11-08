package com.circaso.wallet.domain.support

import com.circaso.wallet.domain.models.GasEstimate
import com.circaso.wallet.domain.models.GasSample
import com.circaso.wallet.domain.models.RecentGas
import com.circaso.wallet.domain.repositories.FeeLedgerRepository
import com.circaso.wallet.domain.repositories.FeeQuoteRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * What a payment's network cost is priced on (decision 2026-11-08): what recent transfers on the chain actually cost,
 * not Circle's worst-case estimate (on Base Sepolia the estimate ran about twice the actual cost). For a chain and coin
 * it is the 75th percentile of the last [WINDOW_SIZE] collected transfers of the last [LOOKBACK]: the coin's own when
 * it has at least [MIN_SAMPLES], else every coin's on the chain (a token transfer costs about the same whatever the
 * coin), else none, and the caller falls back to Circle's estimate. The rule's margin goes on top in [Pricing].
 *
 * One query a minute per node, not one per quote: the samples are kept for [REFRESH].
 */
class NetworkCosts(
    private val ledger: FeeLedgerRepository,
    private val quotes: FeeQuoteRepository,
    private val clock: Clock,
) {
    private val lock = Mutex()

    @Volatile
    private var kept: Pair<List<GasSample>, Instant>? = null

    /** The recent cost of a payment of [asset] on [blockchain]; null while the chain has too little history. */
    suspend fun recent(blockchain: String, asset: String): RecentGas? {
        val (samples, at) = samples()
        val chain = samples.filter { it.blockchain == blockchain }
        return price(blockchain, asset, chain.filter { it.asset == asset }, at)
            ?: price(blockchain, null, chain, at)
    }

    /** Every chain's recent cost, pooled and per coin, for the staff fee editor. */
    suspend fun all(): List<RecentGas> {
        val (samples, at) = samples()
        return samples
            .groupBy { it.blockchain }
            .flatMap { (chain, rows) ->
                listOfNotNull(price(chain, null, rows, at)) +
                    rows.groupBy { it.asset }.mapNotNull { (asset, own) -> price(chain, asset, own, at) }
            }.sortedWith(compareBy({ it.blockchain }, { it.asset ?: "" }))
    }

    /** Per chain, the last Circle estimate quoted over the [LOOKBACK], for chains without recent history. */
    suspend fun estimates(): List<GasEstimate> = quotes.latestEstimates(clock.instant().minus(LOOKBACK))

    private fun price(
        blockchain: String,
        asset: String?,
        rows: List<GasSample>,
        at: Instant,
    ): RecentGas? {
        val window = rows.sortedByDescending { it.at }.take(WINDOW_SIZE)
        if (window.size < MIN_SAMPLES) return null
        return RecentGas(blockchain, asset, percentile75(window.map { it.gasMinor }), window.size, at)
    }

    private suspend fun samples(): Pair<List<GasSample>, Instant> {
        val now = clock.instant()
        kept?.takeIf { (_, at) -> Duration.between(at, now) < REFRESH }?.let { return it }
        return lock.withLock {
            kept?.takeIf { (_, at) -> Duration.between(at, now) < REFRESH }
                ?: (ledger.recentActualGas(now.minus(LOOKBACK), MAX_ROWS) to now).also { kept = it }
        }
    }

    companion object {
        /** How many recent transfers a chain's cost is taken from. */
        const val WINDOW_SIZE = 50

        /** Fewer than this and the chain has too little history: Circle's estimate prices it. */
        const val MIN_SAMPLES = 5

        val LOOKBACK: Duration = Duration.ofDays(30)
        val REFRESH: Duration = Duration.ofMinutes(1)

        /** Enough rows for every chain and coin's window. */
        private const val MAX_ROWS = 2_000

        /** The 75th percentile by nearest rank: always one of the samples. */
        fun percentile75(values: List<Long>): Long {
            val sorted = values.sorted()
            val rank = (sorted.size * 3 + 3) / 4
            return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
        }
    }
}
