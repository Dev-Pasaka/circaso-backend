package com.circaso.integrations.impl.coingecko

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/**
 * CoinGecko's answers, only the fields we read. Numbers are read as text (lenient JSON takes an unquoted number into a
 * string), so `2232.83` and `1.2E-5` stay exactly as the feed wrote them instead of passing through a double.
 */
internal val coinGeckoJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

/** A number as the feed wrote it (also `"$0.0123"` or `"1,234.5"`), or null. */
internal fun decimalOf(text: String?): BigDecimal? =
    text
        ?.replace("$", "")
        ?.replace(",", "")
        ?.trim()
        ?.toBigDecimalOrNull()

/** `{"usd": …}`, the one currency we ask for. */
@Serializable
internal data class UsdValue(val usd: String? = null)

/** `/simple/price`: each coin id to its price. */
internal val simplePriceSerializer = MapSerializer(String.serializer(), UsdValue.serializer())

/** `/search`. */
@Serializable
internal data class SearchBody(val coins: List<SearchCoin> = emptyList())

@Serializable
internal data class SearchCoin(
    val id: String = "",
    val name: String? = null,
    val symbol: String? = null,
    @SerialName("market_cap_rank") val marketCapRank: String? = null,
)

/** `/coins/{id}` without tickers, community or developer data. */
@Serializable
internal data class CoinBody(
    val id: String = "",
    val symbol: String? = null,
    val name: String? = null,
    @SerialName("market_cap_rank") val marketCapRank: String? = null,
    val categories: List<String?> = emptyList(),
    val links: CoinLinks? = null,
    val description: CoinDescription? = null,
    @SerialName("market_data") val marketData: MarketData? = null,
    @SerialName("last_updated") val lastUpdated: String? = null,
)

@Serializable
internal data class CoinLinks(val homepage: List<String?> = emptyList())

@Serializable
internal data class CoinDescription(val en: String? = null)

@Serializable
internal data class MarketData(
    @SerialName("current_price") val currentPrice: UsdValue? = null,
    @SerialName("price_change_percentage_24h") val change24h: String? = null,
    @SerialName("price_change_percentage_7d") val change7d: String? = null,
    @SerialName("market_cap") val marketCap: UsdValue? = null,
    @SerialName("total_volume") val totalVolume: UsdValue? = null,
    @SerialName("high_24h") val high24h: UsdValue? = null,
    @SerialName("low_24h") val low24h: UsdValue? = null,
    val ath: UsdValue? = null,
    @SerialName("ath_date") val athDate: UsdValue? = null,
    @SerialName("ath_change_percentage") val athChange: UsdValue? = null,
    @SerialName("circulating_supply") val circulatingSupply: String? = null,
    @SerialName("max_supply") val maxSupply: String? = null,
    @SerialName("last_updated") val lastUpdated: String? = null,
)

/** `/search/trending`. */
@Serializable
internal data class TrendingBody(val coins: List<TrendingEntry> = emptyList())

@Serializable
internal data class TrendingEntry(val item: TrendingItem? = null)

@Serializable
internal data class TrendingItem(
    val id: String = "",
    val name: String? = null,
    val symbol: String? = null,
    @SerialName("market_cap_rank") val marketCapRank: String? = null,
    val data: TrendingData? = null,
)

@Serializable
internal data class TrendingData(
    val price: String? = null,
    @SerialName("price_change_percentage_24h") val change24h: UsdValue? = null,
)
