package com.circaso.integrations.impl.coingecko

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.impl.PriceSymbols
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * CoinGecko's simple price (`GET https://api.coingecko.com/api/v3/simple/price`, Demo key in `x-cg-demo-api-key`;
 * decision 2026-11-06). One call asks for every coin in [PriceSymbols.COINGECKO_IDS] at once and the table is kept in
 * Redis under `px:usd`, so every node shares it and a wallet read asks the feed at most once per [freshFor]. When
 * the feed is down the last table is used while it is under [MAX_AGE] old; after that there is no price and the fiat
 * value is left out. A failed fetch isn't tried again for [RETRY_AFTER]. Nothing here ever throws to the caller.
 *
 * Only asked when something non-dollar needs a price, so a wallet of stablecoins never spends the Demo plan's calls.
 */
class CoinGeckoPrices(
    private val http: HttpClient,
    private val redis: RedisScope,
    private val key: String,
    private val freshFor: Duration = DEFAULT_FRESH_FOR,
    private val clock: Clock = Clock.systemUTC(),
) : CryptoPrices {
    private val log = LoggerFactory.getLogger(CoinGeckoPrices::class.java)

    /** One fetch at a time per node; the others wait and read what it stored. */
    private val refresh = Mutex()

    override suspend fun usdPrices(symbols: Set<String>): Map<String, BigDecimal> {
        if (symbols.all { PriceSymbols.isDollar(it) }) return PriceSymbols.answer(symbols) { null }
        val table =
            try {
                table()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("crypto prices unavailable: error={}", e::class.simpleName)
                null
            }
        return PriceSymbols.answer(symbols) { symbol -> table?.prices?.get(symbol)?.toBigDecimalOrNull() }
    }

    private suspend fun table(): StoredPrices? {
        val kept = stored()
        if (kept != null && kept.isFresh()) return kept
        return refresh.withLock {
            val again = stored()
            when {
                again != null && again.isFresh() -> again
                redis.exists(FAILED_KEY) -> again?.takeIf { it.isUsable() }
                else -> fetch()?.also { store(it) } ?: failed(again)
            }
        }
    }

    private suspend fun failed(last: StoredPrices?): StoredPrices? {
        redis.set(FAILED_KEY, "1", RETRY_AFTER)
        return last?.takeIf { it.isUsable() }
    }

    private suspend fun fetch(): StoredPrices? {
        val text =
            try {
                val response =
                    http.get(PATH) {
                        operation("simple_price")
                        header(KEY_HEADER, key)
                        parameter("ids", IDS)
                        parameter("vs_currencies", "usd")
                        parameter("precision", "full")
                    }
                if (!response.status.isSuccess()) return unreadable("status ${response.status.value}")
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CircuitOpenException) {
                return null
            } catch (e: Exception) {
                log.warn("crypto prices unreachable: error={}", e::class.simpleName)
                return null
            }
        val byId =
            runCatching { coinGeckoJson.decodeFromString(simplePriceSerializer, text) }.getOrNull()
                ?: return unreadable("body")
        val prices =
            PriceSymbols.COINGECKO_IDS
                .mapNotNull { (symbol, id) ->
                    decimalOf(byId[id]?.usd)?.takeIf { it.signum() > 0 }?.let { symbol to it.toPlainString() }
                }.toMap()
        if (prices.isEmpty()) return unreadable("no prices")
        return StoredPrices(clock.millis(), prices)
    }

    private fun unreadable(what: String): StoredPrices? {
        log.warn("crypto prices source answered something we can't use: {}", what)
        return null
    }

    private suspend fun stored(): StoredPrices? =
        redis.get(TABLE_KEY)?.let { runCatching { json.decodeFromString<StoredPrices>(it) }.getOrNull() }

    /** Kept for as long as it may still be used, so the last table outlives a feed that is down. */
    private suspend fun store(table: StoredPrices) {
        redis.set(TABLE_KEY, json.encodeToString(StoredPrices.serializer(), table), MAX_AGE)
    }

    private fun StoredPrices.age(): Duration = Duration.between(Instant.ofEpochMilli(fetchedAt), clock.instant())

    private fun StoredPrices.isFresh(): Boolean = age() < freshFor

    private fun StoredPrices.isUsable(): Boolean = age() < MAX_AGE

    /** The table as Redis keeps it: when it was fetched (epoch ms) and each price as a decimal string by symbol. */
    @Serializable
    internal data class StoredPrices(val fetchedAt: Long, val prices: Map<String, String>)

    companion object {
        const val BASE_URL = "https://api.coingecko.com"
        private const val PATH = "/api/v3/simple/price"
        private const val KEY_HEADER = "x-cg-demo-api-key"

        /** How long a table is used before the feed is asked again (decision 2026-11-06: about a minute). */
        val DEFAULT_FRESH_FOR: Duration = Duration.ofSeconds(60)

        /** How old a table may be and still be shown when the feed is down: crypto moves, so not long. */
        val MAX_AGE: Duration = Duration.ofMinutes(15)

        /** After a failed fetch, the feed isn't asked again for this long. */
        val RETRY_AFTER: Duration = Duration.ofSeconds(60)

        /** The connect and request timeouts: a wallet read waits on this, so a slow feed is cut short. */
        val TIMEOUT: Duration = Duration.ofSeconds(4)

        private const val TABLE_KEY = "usd"
        private const val FAILED_KEY = "usd:failed"
        private val IDS =
            PriceSymbols.COINGECKO_IDS.values
                .distinct()
                .sorted()
                .joinToString(",")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
