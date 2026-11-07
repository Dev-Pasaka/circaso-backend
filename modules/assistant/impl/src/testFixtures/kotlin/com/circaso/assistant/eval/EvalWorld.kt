package com.circaso.assistant.eval

import com.circaso.assistant.data.tools.AllAgentTools
import com.circaso.assistant.data.tools.CryptoTools
import com.circaso.assistant.data.tools.HttpAgentTools
import com.circaso.assistant.data.tools.MoneyTools
import com.circaso.assistant.data.tools.OwnTools
import com.circaso.assistant.domain.models.ChatSnippet
import com.circaso.assistant.domain.models.MascotSceneState
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.MascotScenes
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.SearchAllowance
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.integrations.api.CoinMarket
import com.circaso.integrations.api.CryptoMarkets
import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.TrendingCoin
import com.circaso.integrations.api.UsdRate
import com.circaso.integrations.api.WebResult
import com.circaso.integrations.api.WebSearch
import com.circaso.messaging.api.AgentChatDto
import com.circaso.messaging.api.AgentChatMemberDto
import com.circaso.messaging.api.AgentChatsResponse
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.AgentContactDto
import com.circaso.users.api.AgentContactsResponse
import com.circaso.users.api.AgentProfileResponse
import com.circaso.wallet.api.AgentAssetValueDto
import com.circaso.wallet.api.AgentAssetValuesResponse
import com.circaso.wallet.api.AgentBalanceDto
import com.circaso.wallet.api.AgentChainDto
import com.circaso.wallet.api.AgentCounterpartyDto
import com.circaso.wallet.api.AgentCounterpartyFlowDto
import com.circaso.wallet.api.AgentFeeQuoteResponse
import com.circaso.wallet.api.AgentFlowDto
import com.circaso.wallet.api.AgentLimitDto
import com.circaso.wallet.api.AgentMonthFlowDto
import com.circaso.wallet.api.AgentRateDto
import com.circaso.wallet.api.AgentSummaryResponse
import com.circaso.wallet.api.AgentTransactionDto
import com.circaso.wallet.api.AgentTransactionResponse
import com.circaso.wallet.api.AgentTransactionsResponse
import com.circaso.wallet.api.AgentWalletListingDto
import com.circaso.wallet.api.AgentWalletResponse
import com.circaso.wallet.api.AgentWalletsResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.KSerializer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The eval's whole world, fixed so every run sees the same numbers: one user in Nairobi (display currency KES) with a
 * wallet, five weeks of payments, four chats on the phone, and the day's rates and prices.
 *
 * Asha's real tools run unchanged ([HttpAgentTools], [MoneyTools], [CryptoTools], [OwnTools], wired as the module
 * wires them), so their specs, labels, arguments and output are production's. Only what they read is fake: the rate
 * and price ports, a stand-in for the other modules' `/v1/agent/` routes (Ktor's mock engine), and the phone that
 * answers `searchChats` and `readChat`. Nothing reaches a wallet, a vendor or the network.
 *
 * A fresh world per case and repetition, so no case sees another's calls.
 */
class EvalWorld {
    val userId: UserId = UserId(UUID.fromString("0190f0c0-0000-7000-8000-000000000001"))

    /** What the fake agent routes were asked, in order (path and query), for debugging a transcript. */
    val routeCalls = mutableListOf<String>()

    private val config = AssistantConfig()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val quota = EvalQuota()
    private val plans =
        object : EntitlementRepository {
            override suspend fun planOf(userId: UserId) = Plan.FREE
        }

    val tools: AgentTools =
        AllAgentTools(
            listOf(
                HttpAgentTools(HttpClient(MockEngine { respondTo(it) }), "http://agent-routes.eval"),
                OwnTools(
                    EvalWeb,
                    UsageReader(plans, quota, config, clock),
                    SearchAllowance(plans, quota, config, clock),
                ),
                CryptoTools(EvalMarkets, EvalRates),
                MoneyTools(EvalRates, EvalPrices, EvalMarkets, clock),
            ),
        )

    val scenes: MascotScenes = NoScenes

    // ---- the phone (device tools) ----

    /** What the phone answers to `searchChats`: snippets that hold any word of the query (3 letters or more). */
    fun searchChats(argumentsJson: String): List<ChatSnippet> {
        val args = jsonArgs(argumentsJson)
        val query = args["query"].orEmpty().lowercase()
        val chatIds =
            args["chatIds"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val since = args["since"]?.let { runCatching { Instant.parse(it) }.getOrNull() ?: dayStart(it) }
        val words = Regex("[\\p{L}\\p{N}]{3,}").findAll(query).map { it.value }.toList()
        return SNIPPETS
            .filter { chatIds.isEmpty() || it.chatId in chatIds }
            .filter { since == null || it.at >= since }
            .filter { snippet ->
                val haystack = "${snippet.text} ${snippet.sender} ${snippet.chatName}".lowercase()
                words.any { word -> word in haystack || haystack.split(Regex("\\W+")).any { it.startsWith(word) } }
            }.take(config.maxSnippets)
    }

    /** What the phone answers to `readChat`: the chat's messages, oldest first. */
    fun readChat(argumentsJson: String): List<ChatSnippet> {
        val chatId = jsonArgs(argumentsJson)["chatId"].orEmpty()
        return SNIPPETS.filter { it.chatId == chatId }
    }

    // ---- the agent routes ----

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondTo(request: HttpRequestData) =
        run {
            val path = request.url.encodedPath
            val query = request.url.parameters
            routeCalls += path + (
                request.url.encodedQuery
                    .takeIf { it.isNotEmpty() }
                    ?.let { "?$it" } ?: ""
            )
            val currency = query["currency"]?.uppercase()
            val rate = currency?.let { USD_RATES[it] }?.let { AgentRateDto(currency, plain(it), RATES_AT.toString()) }
            val body: String? =
                when {
                    path == "/v1/agent/users/me" -> {
                        ok(AgentProfileResponse.serializer(), PROFILE)
                    }

                    path == "/v1/agent/users/contacts/search" -> {
                        val q = query["q"].orEmpty().trim().lowercase()
                        ok(
                            AgentContactsResponse.serializer(),
                            AgentContactsResponse(
                                CONTACTS.filter {
                                    q.isNotEmpty() &&
                                        (q in it.displayName.lowercase() || q in it.handle.orEmpty().lowercase())
                                },
                            ),
                        )
                    }

                    path == "/v1/agent/users/contacts" -> {
                        ok(AgentContactsResponse.serializer(), AgentContactsResponse(CONTACTS))
                    }

                    path == "/v1/agent/users/blocks" -> {
                        """{"blocked":[]}""".let(::envelope)
                    }

                    path == "/v1/agent/messaging/chats" -> {
                        ok(AgentChatsResponse.serializer(), AgentChatsResponse(CHATS))
                    }

                    path == "/v1/agent/wallet" && query["walletId"] == null -> {
                        ok(AgentWalletResponse.serializer(), wallet(rate))
                    }

                    path == "/v1/agent/wallet/wallets" -> {
                        ok(
                            AgentWalletsResponse.serializer(),
                            AgentWalletsResponse(
                                listOf(
                                    AgentWalletListingDto(
                                        WALLET_ID,
                                        "PERSON",
                                        "ACTIVE",
                                        true,
                                        listOf("BASE", "SOLANA"),
                                    ),
                                ),
                            ),
                        )
                    }

                    path == "/v1/agent/wallet/values" -> {
                        ok(AgentAssetValuesResponse.serializer(), values(query["assets"], query["amount"], currency))
                    }

                    path == "/v1/agent/wallet/fee" -> {
                        fee(query["amount"], query["asset"] ?: "USDC", query["blockchain"], rate)
                    }

                    path == "/v1/agent/wallet/transactions" -> {
                        ok(
                            AgentTransactionsResponse.serializer(),
                            AgentTransactionsResponse(
                                matching(query).take(query["limit"]?.toIntOrNull() ?: 20).map { it.withFiat(rate) },
                                rate = rate,
                            ),
                        )
                    }

                    path == "/v1/agent/wallet/transactions/summary" -> {
                        ok(AgentSummaryResponse.serializer(), summary(query, rate))
                    }

                    path.startsWith("/v1/agent/wallet/transactions/") -> {
                        val id = path.substringAfterLast('/')
                        TRANSACTIONS.firstOrNull { it.id == id }?.let {
                            ok(AgentTransactionResponse.serializer(), AgentTransactionResponse(it.withFiat(rate), rate))
                        }
                    }

                    path == "/v1/agent/chama" -> {
                        """{"chamas":[]}""".let(::envelope)
                    }

                    else -> {
                        null
                    }
                }
            if (body != null) {
                respond(body, HttpStatusCode.OK, JSON)
            } else {
                respond(
                    """{"httpStatusCode":404,"status":false,"message":"Not found",""" +
                        """"code":"request.not_found","requestId":"eval"}""",
                    HttpStatusCode.NotFound,
                    JSON,
                )
            }
        }

    private fun wallet(rate: AgentRateDto?): AgentWalletResponse {
        val perUsd = rate?.let { BigDecimal(it.usdRate) }

        fun fiat(asset: String, amount: BigDecimal) = perUsd?.let { money(amount * PRICES.getValue(asset) * it) }
        return AgentWalletResponse(
            status = "ACTIVE",
            balances =
                BALANCES.map { (asset, chain, amount) ->
                    AgentBalanceDto(asset, plain(amount), chain, fiat(asset, amount))
                },
            totals =
                BALANCES
                    .groupBy { it.first }
                    .map { (asset, rows) ->
                        rows.sumOf { it.third }.let { AgentBalanceDto(asset, plain(it), null, fiat(asset, it)) }
                    },
            chains =
                listOf(
                    AgentChainDto("BASE", "0x7a3f9c2e8b1d4a6f0c5e9b2d7a1f3c8e6b4d2a90", "LIVE", true),
                    AgentChainDto("SOLANA", "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", "LIVE", true),
                ),
            fiatTotal =
                perUsd?.let { rate ->
                    money(BALANCES.sumOf { (asset, _, amount) -> amount * PRICES.getValue(asset) } * rate)
                },
            rate = rate,
        )
    }

    private fun values(
        assets: String?,
        amount: String?,
        currency: String?,
    ): AgentAssetValuesResponse {
        val asked = assets?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
        val held = BALANCES.map { it.first }.distinct() + listOf("USDT")
        val perUsd = currency?.let { USD_RATES[it] }
        val quantity = amount?.replace(",", "")?.toBigDecimalOrNull()
        return AgentAssetValuesResponse(
            currency = if (perUsd != null) currency else "USD",
            source = "circaso",
            values =
                (asked ?: held).map { asset ->
                    val usd = PRICES[asset]
                    if (usd == null) {
                        AgentAssetValueDto(asset, priced = false)
                    } else {
                        val unit = usd * (perUsd ?: BigDecimal.ONE)
                        AgentAssetValueDto(
                            asset = asset,
                            priced = true,
                            usdPrice = plain(usd),
                            unitValue = money(unit),
                            value = quantity?.let { money(it * unit) },
                        )
                    }
                },
            amount = quantity?.let(::plain),
            rate = perUsd?.let { AgentRateDto(currency, plain(it), RATES_AT.toString()) },
        )
    }

    private fun fee(
        amountText: String?,
        asset: String,
        blockchain: String?,
        rate: AgentRateDto?,
    ): String {
        val amount = amountText?.replace(",", "")?.toBigDecimalOrNull() ?: return envelopeError()
        val chain = blockchain?.uppercase() ?: "BASE"
        val balance = BALANCES.filter { it.first == asset.uppercase() && it.second == chain }.sumOf { it.third }
        val perUsd = rate?.let { BigDecimal(it.usdRate) }
        val answer =
            AgentFeeQuoteResponse(
                blockchain = chain,
                asset = asset.uppercase(),
                amount = plain(amount),
                fee = "0",
                rateBps = 0,
                minimum = false,
                total = plain(amount),
                balance = plain(balance),
                maxAmount = plain(balance),
                limit =
                    AgentLimitDto(
                        perPaymentMax = "1000",
                        dailyMax = "2000",
                        used = "0",
                        left = "2000",
                        windowHours = 24,
                    ),
                noFeeReason = "Payments to people on Circaso are free",
                problem = if (amount > balance) "INSUFFICIENT_FUNDS" else null,
                fiatAmount = perUsd?.let { money(amount * it) },
                fiatFee = perUsd?.let { "0.00" },
                fiatTotal = perUsd?.let { money(amount * it) },
                rate = rate,
            )
        return ok(AgentFeeQuoteResponse.serializer(), answer)
    }

    private fun matching(query: io.ktor.http.Parameters): List<AgentTransactionDto> {
        val month = query["month"]
        val from = query["from"]?.let(::dayStart)
        val to = query["to"]?.let { dayStart(it)?.plusSeconds(DAY_SECONDS) }
        val direction = query["direction"]?.uppercase()
        val counterparty = query["counterparty"]
        val asset = query["asset"]?.uppercase()
        return TRANSACTIONS.filter { tx ->
            (month == null || tx.at.startsWith(month)) &&
                (from == null || Instant.parse(tx.at) >= from) &&
                (to == null || Instant.parse(tx.at) < to) &&
                (direction == null || tx.direction == direction) &&
                (counterparty == null || counterparty in listOf(tx.counterparty.userId, tx.counterparty.address)) &&
                (asset == null || tx.asset == asset)
        }
    }

    private fun summary(query: io.ktor.http.Parameters, rate: AgentRateDto?): AgentSummaryResponse {
        val counted = matching(query).filter { it.state != "FAILED" }
        val perUsd = rate?.let { BigDecimal(it.usdRate) }

        fun sum(rows: List<AgentTransactionDto>, direction: String) =
            rows.filter { it.direction == direction }.sumOf { BigDecimal(it.amount) }
        val totals =
            counted.groupBy { it.asset }.map { (asset, rows) ->
                val totalIn = sum(rows, "IN")
                val totalOut = sum(rows, "OUT")
                AgentFlowDto(
                    asset = asset,
                    totalIn = plain(totalIn),
                    totalOut = plain(totalOut),
                    net = plain(totalIn - totalOut),
                    countIn = rows.count { it.direction == "IN" },
                    countOut = rows.count { it.direction == "OUT" },
                    fiatIn = perUsd?.let { money(totalIn * it) },
                    fiatOut = perUsd?.let { money(totalOut * it) },
                )
            }
        val top =
            counted
                .groupBy { (it.counterparty.userId ?: it.counterparty.address) to it.asset }
                .map { (_, rows) ->
                    AgentCounterpartyFlowDto(
                        rows.first().counterparty,
                        rows.first().asset,
                        plain(sum(rows, "IN")),
                        plain(sum(rows, "OUT")),
                        rows.size,
                    )
                }.sortedByDescending { BigDecimal(it.totalOut) + BigDecimal(it.totalIn) }
                .take(query["top"]?.toIntOrNull() ?: 5)
        val months =
            counted
                .groupBy { it.at.take(7) to it.asset }
                .map { (key, rows) ->
                    AgentMonthFlowDto(key.first, key.second, plain(sum(rows, "IN")), plain(sum(rows, "OUT")), rows.size)
                }.sortedBy { it.month }
        val month = query["month"]
        return AgentSummaryResponse(
            from = month?.let { "$it-01T00:00:00Z" } ?: query["from"]?.let { "${it}T00:00:00Z" },
            until =
                month?.let {
                    java.time.YearMonth
                        .parse(it)
                        .plusMonths(1)
                        .toString() + "-01T00:00:00Z"
                },
            count = counted.size,
            totals = totals,
            topCounterparties = top,
            byMonth = months,
            rate = rate,
        )
    }

    private fun AgentTransactionDto.withFiat(rate: AgentRateDto?) =
        rate?.let { copy(fiat = money(BigDecimal(amount) * PRICES.getValue(asset) * BigDecimal(it.usdRate))) } ?: this

    private fun <T> ok(serializer: KSerializer<T>, data: T) = envelope(CircasoJson.encodeToString(serializer, data))

    private fun envelope(data: String) =
        """{"httpStatusCode":200,"status":true,"message":"Success","requestId":"eval","data":$data}"""

    private fun envelopeError() =
        """{"httpStatusCode":400,"status":false,"message":"Bad amount",""" +
            """"code":"wallet.amount_invalid","requestId":"eval"}"""

    private fun dayStart(day: String): Instant? =
        runCatching {
            java.time.LocalDate
                .parse(day.take(10))
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
        }.getOrNull()

    private fun jsonArgs(json: String): Map<String, String> = EvalGrader.flatArgs(json)

    companion object {
        /** The eval's "now": Saturday 7 November 2026, 09:30 in Nairobi. */
        val NOW: Instant = Instant.parse("2026-11-07T06:30:00Z")
        const val TODAY = "2026-11-07"
        const val CURRENCY = "KES"
        const val AGENT_TOKEN = "eval-agent-token"
        private const val WALLET_ID = "0190f0c0-0000-7000-8000-00000000aa01"
        private const val DAY_SECONDS = 86_400L
        val RATES_AT: Instant = Instant.parse("2026-11-07T00:02:31Z")
        val PRICES_AT: Instant = Instant.parse("2026-11-07T06:29:10Z")
        private val JSON = headersOf(HttpHeaders.ContentType, "application/json")

        /**
         * One US dollar in each currency (ExchangeRate-API stand-in). No ETB: the eval's currency with no rate. Round
         * numbers, so the expected answers can be checked by hand (1 EUR = 129.25 / 0.8 = 161.5625 KES).
         */
        val USD_RATES: Map<String, BigDecimal> =
            mapOf(
                "KES" to "129.25",
                "EUR" to "0.8",
                "GBP" to "0.75",
                "UGX" to "3700",
                "TZS" to "2600",
                "NGN" to "1550",
            ).mapValues { BigDecimal(it.value) }

        /** One coin in US dollars (CoinGecko stand-in). WIF (dogwifhat) is found but has no price. */
        val PRICES: Map<String, BigDecimal> =
            mapOf("USDC" to "1", "USDT" to "1", "BTC" to "68000", "ETH" to "3500", "SOL" to "160")
                .mapValues { BigDecimal(it.value) }

        /** The user's wallet: asset, network, amount. 152.4 USDC, 0.5 SOL and 0.01 ETH: KES 34,561.45 in all. */
        val BALANCES: List<Triple<String, String, BigDecimal>> =
            listOf(
                Triple("USDC", "BASE", BigDecimal("100")),
                Triple("USDC", "SOLANA", BigDecimal("52.4")),
                Triple("SOL", "SOLANA", BigDecimal("0.5")),
                Triple("ETH", "BASE", BigDecimal("0.01")),
            )

        private val PROFILE =
            AgentProfileResponse(
                userId = "0190f0c0-0000-7000-8000-000000000001",
                displayName = "Wanjiku Kamau",
                handle = "wanjiku",
                memberSince = "2026-03-14T10:00:00Z",
            )

        private val AMINA = AgentContactDto("0190f0c0-0000-7000-8000-0000000000a1", "Amina Wanjiru", "amina")
        private val BRIAN = AgentContactDto("0190f0c0-0000-7000-8000-0000000000b2", "Brian Otieno", "brian_o")
        private val NJERI = AgentContactDto("0190f0c0-0000-7000-8000-0000000000c3", "Mama Njeri", "njeri.w")
        val CONTACTS = listOf(AMINA, BRIAN, NJERI)

        private fun member(c: AgentContactDto) = AgentChatMemberDto(c.userId, c.displayName, c.handle, "MEMBER")

        private val CHATS =
            listOf(
                AgentChatDto("chat-njeri", "DIRECT", 2, listOf(member(NJERI)), false, "2026-11-06T17:02:00Z"),
                AgentChatDto(
                    "chat-family",
                    "GROUP",
                    4,
                    listOf(member(AMINA), member(BRIAN), member(NJERI)),
                    true,
                    "2026-11-05T19:40:00Z",
                ),
                AgentChatDto("chat-brian", "DIRECT", 2, listOf(member(BRIAN)), false, "2026-11-01T08:15:00Z"),
                AgentChatDto("chat-amina", "DIRECT", 2, listOf(member(AMINA)), false, "2026-10-30T13:00:00Z"),
            )

        /** What the phone holds: the chats' recent messages (decrypted there, never on the server). */
        val SNIPPETS =
            listOf(
                ChatSnippet(
                    "chat-amina",
                    "Amina Wanjiru",
                    "Amina Wanjiru",
                    Instant.parse("2026-10-30T13:00:00Z"),
                    "Thanks for lunch yesterday! Next one is on me",
                ),
                ChatSnippet(
                    "chat-brian",
                    "Brian Otieno",
                    "Brian Otieno",
                    Instant.parse("2026-11-01T08:10:00Z"),
                    "Rent ni 24,000 this month, tugawanye mara tatu: 8,000 kila mtu",
                ),
                ChatSnippet(
                    "chat-brian",
                    "Brian Otieno",
                    "You",
                    Instant.parse("2026-11-01T08:15:00Z"),
                    "Poa, nitakutumia kesho",
                ),
                ChatSnippet(
                    "chat-family",
                    "Family Harambee",
                    "Amina Wanjiru",
                    Instant.parse("2026-11-05T19:31:00Z"),
                    "Jamani, kila mtu atoe 1,500 kwa harambee ya Jumamosi",
                ),
                ChatSnippet(
                    "chat-family",
                    "Family Harambee",
                    "Brian Otieno",
                    Instant.parse("2026-11-05T19:40:00Z"),
                    "Sawa, nitatuma Ijumaa",
                ),
                ChatSnippet(
                    "chat-njeri",
                    "Mama Njeri",
                    "Mama Njeri",
                    Instant.parse("2026-11-06T16:58:00Z"),
                    "Habari mwanangu. Tafadhali nitumie 3,000 ya dawa kesho",
                ),
                ChatSnippet(
                    "chat-njeri",
                    "Mama Njeri",
                    "You",
                    Instant.parse("2026-11-06T17:02:00Z"),
                    "Sawa mama, nitatuma asubuhi",
                ),
            )

        private fun tx(
            id: String,
            direction: String,
            amount: String,
            chain: String,
            other: AgentContactDto?,
            at: String,
            state: String = "CONFIRMED",
            address: String? = null,
        ) = AgentTransactionDto(
            id = id,
            direction = direction,
            state = state,
            asset = "USDC",
            amount = amount,
            blockchain = chain,
            counterparty = AgentCounterpartyDto(other?.userId, other?.displayName, other?.handle, address),
            at = at,
            origin = if (direction == "OUT") "APP" else null,
        )

        /** Newest first. October's money out (not failed): 30 + 15.5 + 40 = 85.5 USDC. */
        val TRANSACTIONS =
            listOf(
                tx("0190f0c0-0000-7000-8000-0000000t0001", "OUT", "25", "BASE", BRIAN, "2026-11-03T18:20:00Z"),
                tx("0190f0c0-0000-7000-8000-0000000t0002", "IN", "40", "BASE", AMINA, "2026-11-02T09:05:00Z"),
                tx("0190f0c0-0000-7000-8000-0000000t0003", "OUT", "30", "BASE", NJERI, "2026-10-28T15:45:00Z"),
                tx("0190f0c0-0000-7000-8000-0000000t0004", "OUT", "15.5", "SOLANA", BRIAN, "2026-10-15T11:00:00Z"),
                tx("0190f0c0-0000-7000-8000-0000000t0005", "OUT", "40", "BASE", AMINA, "2026-10-05T12:30:00Z"),
                tx(
                    "0190f0c0-0000-7000-8000-0000000t0006",
                    "OUT",
                    "12",
                    "BASE",
                    BRIAN,
                    "2026-10-02T07:50:00Z",
                    state = "FAILED",
                ),
                tx(
                    "0190f0c0-0000-7000-8000-0000000t0007",
                    "IN",
                    "100",
                    "BASE",
                    null,
                    "2026-10-01T10:00:00Z",
                    address = "0x51c2e0f4a9b7d3e1f6a8c0b2d4e6f8a0b1c3d5e7",
                ),
            )

        fun plain(value: BigDecimal): String =
            value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

        fun money(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}

/** The rates port: [EvalWorld.USD_RATES], published at [EvalWorld.RATES_AT]. */
object EvalRates : ExchangeRates {
    override suspend fun usdRate(currency: String): UsdRate? =
        EvalWorld.USD_RATES[currency.uppercase()]?.let { UsdRate(currency.uppercase(), it, EvalWorld.RATES_AT) }
}

/** The wallet's own price port: [EvalWorld.PRICES]. */
object EvalPrices : CryptoPrices {
    override suspend fun usdPrices(symbols: Set<String>): Map<String, BigDecimal> =
        symbols.mapNotNull { s -> EvalWorld.PRICES[s.uppercase()]?.let { s to it } }.toMap()
}

/** The market feed: BTC, ETH and SOL with full data; WIF (dogwifhat) found but with no price at all. */
object EvalMarkets : CryptoMarkets {
    private fun market(
        id: String,
        symbol: String,
        name: String,
        rank: Int?,
        usd: String?,
        change24h: String?,
        cap: String?,
        ath: String?,
    ) = CoinMarket(
        id = id,
        symbol = symbol,
        name = name,
        marketCapRank = rank,
        usdPrice = usd?.let(::BigDecimal),
        change24hPercent = change24h?.let(::BigDecimal),
        change7dPercent = change24h?.let { BigDecimal(it).multiply(BigDecimal("2")) },
        marketCapUsd = cap?.let(::BigDecimal),
        volume24hUsd = cap?.let { BigDecimal(it).divide(BigDecimal("40")) },
        high24hUsd = usd?.let { BigDecimal(it).multiply(BigDecimal("1.02")) },
        low24hUsd = usd?.let { BigDecimal(it).multiply(BigDecimal("0.97")) },
        allTimeHighUsd = ath?.let(::BigDecimal),
        allTimeHighAt = ath?.let { Instant.parse("2025-10-06T00:00:00Z") },
        fromAllTimeHighPercent = null,
        circulatingSupply = null,
        maxSupply = null,
        categories = emptyList(),
        homepage = null,
        description = null,
        updatedAt = EvalWorld.PRICES_AT,
    )

    private val coins =
        listOf(
            market("bitcoin", "BTC", "Bitcoin", 1, "68000", "1.8", "1350000000000", "126000"),
            market("ethereum", "ETH", "Ethereum", 2, "3500", "-0.6", "422000000000", "4950"),
            market("solana", "SOL", "Solana", 6, "160", "3.2", "87000000000", "295"),
            market("dogwifcoin", "WIF", "dogwifhat", null, null, null, null, null),
        )

    override suspend fun coin(query: String): CoinMarket? {
        val q = query.trim().lowercase()
        return coins.firstOrNull { q == it.symbol.lowercase() || q == it.name.lowercase() || q == it.id }
    }

    override suspend fun trending(): List<TrendingCoin> =
        coins
            .take(
                3,
            ).map { TrendingCoin(it.id, it.symbol, it.name, it.marketCapRank, it.usdPrice, it.change24hPercent) }
}

/** The web: a few fixed results whatever the query, dated, so a search never reaches out. */
object EvalWeb : WebSearch {
    override suspend fun search(query: String, limit: Int): Resource<List<WebResult>> =
        Resource.success(
            listOf(
                WebResult(
                    "How M-Pesa works",
                    "https://www.safaricom.co.ke/personal/m-pesa",
                    "M-Pesa lets you send and receive money from your phone.",
                    Instant.parse("2026-06-01T00:00:00Z"),
                ),
            ).take(limit),
        )
}

private object NoScenes : MascotScenes {
    override suspend fun find(userId: UserId, conversationId: UUID): MascotSceneState? = null

    override suspend fun save(
        userId: UserId,
        conversationId: UUID,
        state: MascotSceneState,
    ) = Unit

    override suspend fun forget(userId: UserId) = Unit
}

/** Asha's daily counters in memory, as Redis keeps them in production. */
private class EvalQuota : QuotaCounters {
    private val counters = mutableMapOf<String, Long>()

    private fun add(key: String, by: Long) = synchronized(counters) { counters.merge(key, by, Long::plus)!! }

    private fun read(key: String) = synchronized(counters) { counters[key] ?: 0 }

    private fun capped(key: String, cap: Int): Long {
        val count = add(key, 1)
        if (count > cap) add(key, -1)
        return count
    }

    override suspend fun countTextTurn(
        userId: UserId,
        day: String,
        cap: Int,
    ) = capped("t:$userId:$day", cap)

    override suspend fun textTurns(userId: UserId, day: String) = read("t:$userId:$day")

    override suspend fun addVoiceSeconds(
        userId: UserId,
        day: String,
        seconds: Int,
    ) = add("v:$userId:$day", seconds.toLong())

    override suspend fun voiceSeconds(userId: UserId, day: String) = read("v:$userId:$day")

    override suspend fun addVoiceSecondsUpTo(
        userId: UserId,
        day: String,
        seconds: Int,
        cap: Int,
    ): Long {
        val now = read("v:$userId:$day")
        return add("v:$userId:$day", minOf(seconds.toLong(), (cap - now).coerceAtLeast(0)))
    }

    override suspend fun addTokens(
        userId: UserId,
        day: String,
        input: Int,
        output: Int,
    ) {
        add("i:$userId:$day", input.toLong())
        add("o:$userId:$day", output.toLong())
    }

    override suspend fun tokens(userId: UserId, day: String) = read("i:$userId:$day") to read("o:$userId:$day")

    override suspend fun countWebSearch(
        userId: UserId,
        day: String,
        cap: Int,
    ) = capped("s:$userId:$day", cap)

    override suspend fun webSearches(userId: UserId, day: String) = read("s:$userId:$day")

    override suspend fun forget(userId: UserId, days: List<String>) = synchronized(counters) { counters.clear() }
}
