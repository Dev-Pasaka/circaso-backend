package com.circaso.integrations.impl.coingecko

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.CoinMarket
import com.circaso.integrations.api.CryptoMarkets
import com.circaso.integrations.api.TrendingCoin
import com.circaso.integrations.impl.PriceSymbols
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * CoinGecko's coin data for Asha (decision 2026-11-06), on the same Demo key and client as [CoinGeckoPrices]:
 * `GET /api/v3/search` turns a symbol or name into a coin id (kept a day), `GET /api/v3/coins/{id}` gives its market
 * data and description (kept a minute, and up to [STALE_FOR] when the feed is down), and `GET /api/v3/search/trending`
 * the trending coins (kept five minutes). Everything is kept in Redis under `px:`, so every node shares one call; a
 * failed call isn't tried again for [RETRY_AFTER]. Nothing here throws to the caller.
 *
 * A symbol several coins share (`ETH` copycats) resolves to the one with the best market-cap rank, and our own coins
 * resolve straight to their known ids ([PriceSymbols.COINGECKO_IDS]) without a search.
 */
class CoinGeckoMarkets(
    private val http: HttpClient,
    private val redis: RedisScope,
    private val key: String,
    private val freshFor: Duration = CoinGeckoPrices.DEFAULT_FRESH_FOR,
    private val clock: Clock = Clock.systemUTC(),
) : CryptoMarkets {
    private val log = LoggerFactory.getLogger(CoinGeckoMarkets::class.java)

    override suspend fun coin(query: String): CoinMarket? =
        safely {
            val id = idOf(query) ?: return@safely null
            cached("coin:$id", StoredCoin.serializer(), freshFor) { fetchCoin(id) }?.toCoin()
        }

    override suspend fun trending(): List<TrendingCoin> =
        safely {
            cached("trending", ListSerializer(StoredTrending.serializer()), TRENDING_FOR) { fetchTrending() }
                ?.map { it.toTrending() }
        }.orEmpty()

    private suspend fun <T> safely(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("crypto markets unavailable: error={}", e::class.simpleName)
            null
        }

    /** The coin id for [query]: one of ours by symbol, else the search's best match (kept a day). */
    private suspend fun idOf(query: String): String? {
        val asked = query.trim().lowercase().take(MAX_QUERY_CHARS)
        if (asked.isEmpty()) return null
        PriceSymbols.COINGECKO_IDS[PriceSymbols.normalise(asked)]?.let { return it }
        return cached("search:$asked", String.serializer(), SEARCH_FOR) { search(asked) }?.takeIf { it.isNotEmpty() }
    }

    /**
     * Keeps [fetch]'s answer under [key] for [fresh]; a failed fetch falls back to the last answer while under
     * [STALE_FOR] (or [fresh] when longer) and marks the key so the feed isn't asked again for [RETRY_AFTER].
     */
    private suspend fun <T> cached(
        key: String,
        serializer: KSerializer<T>,
        fresh: Duration,
        fetch: suspend () -> T?,
    ): T? {
        val envelope = Kept.serializer(serializer)
        val kept = redis.get(key)?.let { runCatching { json.decodeFromString(envelope, it) }.getOrNull() }
        val age = kept?.let { Duration.between(Instant.ofEpochMilli(it.fetchedAt), clock.instant()) }
        if (kept != null && age != null && age < fresh) return kept.data
        val keepFor = maxOf(fresh, STALE_FOR)
        val usable = if (kept != null && age != null && age < keepFor) kept.data else null
        if (redis.exists("$key:failed")) return usable
        val fetched = fetch()
        if (fetched == null) {
            redis.set("$key:failed", "1", RETRY_AFTER)
            return usable
        }
        redis.set(key, json.encodeToString(envelope, Kept(clock.millis(), fetched)), keepFor)
        return fetched
    }

    private suspend fun search(query: String): String? {
        val coins = call("search", SearchBody.serializer()) { parameter("query", query) }?.coins ?: return null
        val bySymbol = coins.filter { it.symbol?.lowercase() == query }
        val byName = coins.filter { it.name?.lowercase() == query || it.id == query }
        val best =
            (bySymbol + byName).minByOrNull { it.marketCapRank?.toIntOrNull() ?: Int.MAX_VALUE }
                ?: coins.firstOrNull()
        // An empty id is kept too, so a query that matches nothing isn't searched again for a day.
        return best?.id?.takeIf { ID.matches(it) } ?: ""
    }

    private suspend fun fetchCoin(id: String): StoredCoin? {
        if (!ID.matches(id)) return null
        val coin =
            call("coins/$id", CoinBody.serializer(), operation = "coin") {
                parameter("localization", "false")
                parameter("tickers", "false")
                parameter("market_data", "true")
                parameter("community_data", "false")
                parameter("developer_data", "false")
                parameter("sparkline", "false")
            } ?: return null
        val market = coin.marketData
        return StoredCoin(
            id = coin.id.ifBlank { id },
            symbol = coin.symbol.orEmpty().uppercase(),
            name = coin.name.orEmpty(),
            marketCapRank = coin.marketCapRank?.toIntOrNull(),
            usdPrice = market?.currentPrice.plain(),
            change24hPercent = decimalOf(market?.change24h)?.toPlainString(),
            change7dPercent = decimalOf(market?.change7d)?.toPlainString(),
            marketCapUsd = market?.marketCap.plain(),
            volume24hUsd = market?.totalVolume.plain(),
            high24hUsd = market?.high24h.plain(),
            low24hUsd = market?.low24h.plain(),
            allTimeHighUsd = market?.ath.plain(),
            allTimeHighAt = market?.athDate?.usd,
            fromAllTimeHighPercent = market?.athChange.plain(),
            circulatingSupply = decimalOf(market?.circulatingSupply)?.toPlainString(),
            maxSupply = decimalOf(market?.maxSupply)?.toPlainString(),
            categories =
                coin.categories
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .take(MAX_CATEGORIES),
            homepage = coin.links?.homepage?.firstOrNull { it?.startsWith("https://") == true },
            description = plain(coin.description?.en),
            updatedAt = market?.lastUpdated ?: coin.lastUpdated,
        )
    }

    private fun UsdValue?.plain(): String? = decimalOf(this?.usd)?.toPlainString()

    private suspend fun fetchTrending(): List<StoredTrending>? {
        val body = call("search/trending", TrendingBody.serializer(), operation = "trending") {} ?: return null
        return body.coins
            .mapNotNull { it.item?.takeIf { item -> item.id.isNotBlank() } }
            .map { item ->
                StoredTrending(
                    id = item.id,
                    symbol = item.symbol.orEmpty().uppercase(),
                    name = item.name.orEmpty(),
                    marketCapRank = item.marketCapRank?.toIntOrNull(),
                    usdPrice = decimalOf(item.data?.price)?.toPlainString(),
                    change24hPercent = item.data?.change24h.plain(),
                )
            }.take(MAX_TRENDING)
    }

    /** `GET /api/v3/<path>` with the key, read as [serializer]; null for any failure (logged without details). */
    private suspend fun <T> call(
        path: String,
        serializer: KSerializer<T>,
        operation: String = path,
        configure: HttpRequestBuilder.() -> Unit,
    ): T? {
        val text =
            try {
                val response =
                    http.get("$BASE_PATH/$path") {
                        operation(operation)
                        header(KEY_HEADER, key)
                        configure()
                    }
                if (!response.status.isSuccess()) {
                    log.warn("crypto markets answered status {} to {}", response.status.value, operation)
                    return null
                }
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CircuitOpenException) {
                return null
            } catch (e: Exception) {
                log.warn("crypto markets unreachable: error={}", e::class.simpleName)
                return null
            }
        return runCatching { coinGeckoJson.decodeFromString(serializer, text) }.getOrNull()
            ?: null.also { log.warn("crypto markets answered something we can't read to {}", operation) }
    }

    /** The feed's description without its HTML links, on one line, cut at a sentence near [MAX_DESCRIPTION]. */
    private fun plain(html: String?): String? {
        val text =
            html
                ?.replace(TAG, "")
                ?.replace(SPACE, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        if (text.length <= MAX_DESCRIPTION) return text
        val cut = text.take(MAX_DESCRIPTION)
        val end = cut.lastIndexOf(". ")
        return if (end > MAX_DESCRIPTION / 2) cut.take(end + 1) else "$cut…"
    }

    @Serializable
    internal data class Kept<T>(val fetchedAt: Long, val data: T)

    /** A coin as Redis keeps it: decimals as strings, exact. */
    @Serializable
    internal data class StoredCoin(
        val id: String,
        val symbol: String,
        val name: String,
        val marketCapRank: Int? = null,
        val usdPrice: String? = null,
        val change24hPercent: String? = null,
        val change7dPercent: String? = null,
        val marketCapUsd: String? = null,
        val volume24hUsd: String? = null,
        val high24hUsd: String? = null,
        val low24hUsd: String? = null,
        val allTimeHighUsd: String? = null,
        val allTimeHighAt: String? = null,
        val fromAllTimeHighPercent: String? = null,
        val circulatingSupply: String? = null,
        val maxSupply: String? = null,
        val categories: List<String> = emptyList(),
        val homepage: String? = null,
        val description: String? = null,
        val updatedAt: String? = null,
    ) {
        fun toCoin() =
            CoinMarket(
                id = id,
                symbol = symbol,
                name = name,
                marketCapRank = marketCapRank,
                usdPrice = usdPrice?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 },
                change24hPercent = change24hPercent?.toBigDecimalOrNull(),
                change7dPercent = change7dPercent?.toBigDecimalOrNull(),
                marketCapUsd = marketCapUsd?.toBigDecimalOrNull(),
                volume24hUsd = volume24hUsd?.toBigDecimalOrNull(),
                high24hUsd = high24hUsd?.toBigDecimalOrNull(),
                low24hUsd = low24hUsd?.toBigDecimalOrNull(),
                allTimeHighUsd = allTimeHighUsd?.toBigDecimalOrNull(),
                allTimeHighAt = allTimeHighAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                fromAllTimeHighPercent = fromAllTimeHighPercent?.toBigDecimalOrNull(),
                circulatingSupply = circulatingSupply?.toBigDecimalOrNull(),
                maxSupply = maxSupply?.toBigDecimalOrNull(),
                categories = categories,
                homepage = homepage,
                description = description,
                updatedAt = updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
    }

    @Serializable
    internal data class StoredTrending(
        val id: String,
        val symbol: String,
        val name: String,
        val marketCapRank: Int? = null,
        val usdPrice: String? = null,
        val change24hPercent: String? = null,
    ) {
        fun toTrending() =
            TrendingCoin(
                id = id,
                symbol = symbol,
                name = name,
                marketCapRank = marketCapRank,
                usdPrice = usdPrice?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 },
                change24hPercent = change24hPercent?.toBigDecimalOrNull(),
            )
    }

    companion object {
        private const val BASE_PATH = "/api/v3"
        private const val KEY_HEADER = "x-cg-demo-api-key"
        private val SEARCH_FOR: Duration = Duration.ofHours(24)
        private val TRENDING_FOR: Duration = Duration.ofMinutes(5)

        /** How long a coin's last data may still be used when the feed is down. */
        val STALE_FOR: Duration = Duration.ofMinutes(15)

        /** After a failed call, that call isn't tried again for this long. */
        val RETRY_AFTER: Duration = Duration.ofSeconds(60)

        private const val MAX_QUERY_CHARS = 50
        private const val MAX_DESCRIPTION = 600
        private const val MAX_TRENDING = 10
        private const val MAX_CATEGORIES = 5
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,99}")
        private val TAG = Regex("<[^>]*>")
        private val SPACE = Regex("\\s+")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
