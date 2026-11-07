package com.circaso.assistant.domain.support

import java.util.Currency

/**
 * What people call money, turned into a code (decision 2026-11-07): "shillings" is KES (Circaso is Kenyan first;
 * "Ugandan shillings" is UGX), "dollars" USD, "euro" EUR, "bitcoin" BTC. An ISO 4217 code is a fiat currency; anything
 * else is taken as a coin's symbol or name, for the price feed to find.
 */
object MoneyNames {
    sealed interface Named {
        val code: String
    }

    /** A fiat currency, by its ISO 4217 code. */
    data class Fiat(override val code: String) : Named

    /** A coin: [code] is its symbol when we know it (BTC), else the words as given, for the feed to search. */
    data class Coin(override val code: String) : Named

    private val ISO: Set<String> by lazy { Currency.getAvailableCurrencies().mapTo(HashSet()) { it.currencyCode } }

    private val FIAT_NAMES: Map<String, String> =
        buildMap {
            fun add(code: String, vararg names: String) = names.forEach { put(it, code) }
            add(
                "KES",
                "shilling",
                "shillings",
                "kenyan shilling",
                "kenyan shillings",
                "ksh",
                "kshs",
                "ksh.",
                "kes",
                "bob",
                "shilingi",
                "pesa ya kenya",
            )
            add("UGX", "ugandan shilling", "ugandan shillings", "ush", "ugx")
            add("TZS", "tanzanian shilling", "tanzanian shillings", "tsh", "tshs", "tzs")
            add("USD", "dollar", "dollars", "us dollar", "us dollars", "usd", "bucks", "dola", "$", "us$")
            add("EUR", "euro", "euros", "eur", "€")
            add("GBP", "pound", "pounds", "british pound", "british pounds", "sterling", "pound sterling", "£")
            add("NGN", "naira", "nigerian naira")
            add("ZAR", "rand", "south african rand")
            add("GHS", "cedi", "cedis", "ghanaian cedi")
            add("RWF", "rwandan franc", "rwandan francs", "rwf")
            add("ETB", "birr", "ethiopian birr")
            add("INR", "rupee", "rupees", "indian rupee", "indian rupees", "₹")
            add("JPY", "yen", "japanese yen", "¥")
            add("CNY", "yuan", "renminbi", "rmb", "chinese yuan")
            add("AED", "dirham", "dirhams", "uae dirham")
            add("CAD", "canadian dollar", "canadian dollars")
            add("AUD", "australian dollar", "australian dollars")
            add("CHF", "swiss franc", "swiss francs")
            add("SAR", "riyal", "riyals", "saudi riyal")
        }

    private val COIN_NAMES: Map<String, String> =
        mapOf(
            "bitcoin" to "BTC",
            "bitcoins" to "BTC",
            "btc" to "BTC",
            "ether" to "ETH",
            "ethereum" to "ETH",
            "eth" to "ETH",
            "solana" to "SOL",
            "sol" to "SOL",
            "usd coin" to "USDC",
            "usdc" to "USDC",
            "tether" to "USDT",
            "usdt" to "USDT",
            "polygon" to "POL",
            "matic" to "POL",
            "pol" to "POL",
            "avalanche" to "AVAX",
            "avax" to "AVAX",
            "euro coin" to "EURC",
            "eurc" to "EURC",
        )

    /** [text] as a currency or a coin, or null when it is blank or too long to be one. */
    fun of(text: String): Named? {
        val words = text.trim().lowercase().replace(Regex("\\s+"), " ")
        if (words.isEmpty() || words.length > MAX_CHARS) return null
        FIAT_NAMES[words]?.let { return Fiat(it) }
        COIN_NAMES[words]?.let { return Coin(it) }
        val code = words.uppercase()
        if (code in ISO) return Fiat(code)
        return Coin(if (SYMBOL.matches(code)) code else text.trim())
    }

    private const val MAX_CHARS = 50
    private val SYMBOL = Regex("[A-Z0-9.]{2,12}")
}
