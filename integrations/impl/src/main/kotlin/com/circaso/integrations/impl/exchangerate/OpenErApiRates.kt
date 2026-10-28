package com.circaso.integrations.impl.exchangerate

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.UsdRate
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ExchangeRate-API's open endpoint (`GET https://open.er-api.com/v6/latest/USD`: free, no key, one table a day;
 * decision 2026-10-28). The whole table is kept in Redis until the source says its next one is out
 * (`time_next_update_unix`), so a balance read never waits on the source once a day has been fetched. When the source
 * is down, the last table is used while it is under 48 hours old; after that there is no rate and the fiat value is
 * left out. A failed fetch is not tried again for [RETRY_AFTER], so a source that is down isn't asked on every read.
 *
 * The terms ask for a visible "Rates by Exchange Rate API" credit wherever the rates are shown; the app carries it.
 */
class OpenErApiRates(
    private val http: HttpClient,
    private val redis: RedisScope,
    private val clock: Clock = Clock.systemUTC(),
) : ExchangeRates {
    private val log = LoggerFactory.getLogger(OpenErApiRates::class.java)

    /** One fetch at a time per node; the others wait and read what it stored. */
    private val refresh = Mutex()

    override suspend fun usdRate(currency: String): UsdRate? =
        try {
            table()?.let { rateOf(it, currency) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("exchange rates unavailable: error={}", e::class.simpleName)
            null
        }

    private fun rateOf(table: StoredTable, currency: String): UsdRate? {
        val rate = table.rates[currency]?.takeIf { it > 0.0 } ?: return null
        return UsdRate(currency, exact(rate), Instant.ofEpochSecond(table.updatedAt))
    }

    /** The rate as the source wrote it: 129.25 stays 129.25 and 130 is 130, not 130.0. */
    private fun exact(rate: Double): BigDecimal {
        val stripped = BigDecimal.valueOf(rate).stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    private suspend fun table(): StoredTable? {
        val kept = stored()
        if (kept != null && kept.isCurrent()) return kept
        return refresh.withLock {
            val again = stored()
            when {
                again != null && again.isCurrent() -> again
                redis.exists(FAILED_KEY) -> again?.takeIf { it.isUsable() }
                else -> fetch()?.also { store(it) } ?: failed(again)
            }
        }
    }

    private suspend fun failed(last: StoredTable?): StoredTable? {
        redis.set(FAILED_KEY, "1", RETRY_AFTER)
        return last?.takeIf { it.isUsable() }
    }

    private suspend fun fetch(): StoredTable? {
        val text =
            try {
                val response = http.get("/v6/latest/USD") { operation("latest_rates") }
                if (!response.status.isSuccess()) return unreadable("status ${response.status.value}")
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CircuitOpenException) {
                return null
            } catch (e: Exception) {
                log.warn("exchange rates unreachable: error={}", e::class.simpleName)
                return null
            }
        val latest = runCatching { json.decodeFromString<LatestRates>(text) }.getOrNull() ?: return unreadable("body")
        if (latest.result != "success" || latest.baseCode != "USD" || latest.rates.isEmpty()) {
            return unreadable("result")
        }
        return StoredTable(latest.timeLastUpdateUnix, nextUpdateOf(latest), latest.rates)
    }

    /**
     * When to ask again: the source's next update, or a day after its last one if it gave none. A next update already
     * past (the source runs late) is retried after [RETRY_AFTER] rather than on every read.
     */
    private fun nextUpdateOf(latest: LatestRates): Long {
        val announced =
            latest.timeNextUpdateUnix.takeIf { it > latest.timeLastUpdateUnix }
                ?: (latest.timeLastUpdateUnix + Duration.ofDays(1).seconds)
        val soonest = clock.instant().plus(RETRY_AFTER).epochSecond
        return if (announced <= clock.instant().epochSecond) soonest else announced
    }

    private fun unreadable(what: String): StoredTable? {
        log.warn("exchange rates source answered something we can't use: {}", what)
        return null
    }

    private suspend fun stored(): StoredTable? =
        redis.get(TABLE_KEY)?.let { runCatching { json.decodeFromString<StoredTable>(it) }.getOrNull() }

    /** Kept for as long as it may still be used, so the last table outlives a source that is down. */
    private suspend fun store(table: StoredTable) {
        val left = Duration.between(clock.instant(), Instant.ofEpochSecond(table.updatedAt).plus(MAX_AGE))
        redis.set(TABLE_KEY, json.encodeToString(StoredTable.serializer(), table), maxOf(left, MIN_KEEP))
    }

    private fun StoredTable.isCurrent(): Boolean = clock.instant().isBefore(Instant.ofEpochSecond(nextUpdate))

    private fun StoredTable.isUsable(): Boolean =
        clock.instant().isBefore(Instant.ofEpochSecond(updatedAt).plus(MAX_AGE))

    /** The table as Redis keeps it under `fx:usd`: times in epoch seconds, rates per ISO 4217 code. */
    @Serializable
    internal data class StoredTable(val updatedAt: Long, val nextUpdate: Long, val rates: Map<String, Double>)

    /** The source's answer; its `rates` object is a map from currency code to the value of one dollar. */
    @Serializable
    internal data class LatestRates(
        val result: String = "",
        @SerialName("base_code") val baseCode: String = "",
        @SerialName("time_last_update_unix") val timeLastUpdateUnix: Long = 0,
        @SerialName("time_next_update_unix") val timeNextUpdateUnix: Long = 0,
        val rates: Map<String, Double> = emptyMap(),
    )

    companion object {
        const val BASE_URL = "https://open.er-api.com"

        /** How old a table may be and still be shown when the source is down (decision 2026-10-28). */
        val MAX_AGE: Duration = Duration.ofHours(48)

        /** After a failed fetch, the source isn't asked again for this long. */
        val RETRY_AFTER: Duration = Duration.ofMinutes(5)

        private val MIN_KEEP: Duration = Duration.ofMinutes(1)
        private const val TABLE_KEY = "usd"
        private const val FAILED_KEY = "usd:failed"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
