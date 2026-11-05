package com.circaso.assistant.data.tools

import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.chama.api.AgentChamaResponse
import com.circaso.chama.api.AgentChamasResponse
import com.circaso.identity.api.AgentSecurityResponse
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ToolSpec
import com.circaso.messaging.api.AgentChatsResponse
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.status.api.AgentMomentsResponse
import com.circaso.users.api.AgentBlocksResponse
import com.circaso.users.api.AgentContactDto
import com.circaso.users.api.AgentContactsResponse
import com.circaso.users.api.AgentProfileResponse
import com.circaso.wallet.api.AgentAssetValuesResponse
import com.circaso.wallet.api.AgentFeeQuoteResponse
import com.circaso.wallet.api.AgentSummaryResponse
import com.circaso.wallet.api.AgentTransactionResponse
import com.circaso.wallet.api.AgentTransactionsResponse
import com.circaso.wallet.api.AgentWalletResponse
import com.circaso.wallet.api.AgentWalletsResponse
import com.circaso.web.DefaultResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Asha's tools on the other modules' read-only `/v1/agent/` routes, called with the user's agent token as an outside
 * client would, so they are bound by the same rules: the gateway refuses the agent token anywhere else and for
 * anything but `GET`, and gives it its own rate-limit bucket. Every route answers only the caller's own data.
 * Whatever comes back may hold other people's words, so the agent treats it as untrusted.
 *
 * Typed both ways (decision 2026-10-27): the model's arguments (an opaque JSON string on the wire) are read into one
 * class per tool, and a route's answer into the envelope around its data, which alone goes back to the model. Every
 * answer is held under [ToolOutput.MAX_CHARS]: a list too long is cut, with a note saying so (decision 2026-10-31).
 */
class HttpAgentTools(private val http: HttpClient, private val baseUrl: String) : AgentTools {
    private val log = LoggerFactory.getLogger(HttpAgentTools::class.java)

    override fun specs(hasAgentToken: Boolean): List<ToolSpec> =
        if (hasAgentToken) {
            listOf(
                AshaPrompt.getProfile,
                AshaPrompt.searchContacts,
                AshaPrompt.listContacts,
                AshaPrompt.listBlocked,
                AshaPrompt.getSecurityOverview,
                AshaPrompt.listChats,
                AshaPrompt.getWallet,
                AshaPrompt.listWallets,
                AshaPrompt.getAssetValues,
                AshaPrompt.getFeeQuote,
                AshaPrompt.listTransactions,
                AshaPrompt.summarizeTransactions,
                AshaPrompt.getTransaction,
                AshaPrompt.listChamas,
                AshaPrompt.getChama,
                AshaPrompt.getMyMoments,
            )
        } else {
            emptyList()
        }

    override fun handles(toolName: String) = toolName in NAMES

    override fun label(toolName: String, argumentsJson: String): String =
        when (toolName) {
            GET_PROFILE -> "Looking at your profile…"
            SEARCH_CONTACTS -> "Looking for ${quoted(arguments(argumentsJson, QueryArgs()).query)} in your contacts…"
            LIST_CONTACTS -> "Going through your contacts…"
            LIST_BLOCKED -> "Checking who you've blocked…"
            GET_SECURITY -> "Checking your account security…"
            LIST_CHATS -> "Looking at your chats…"
            GET_WALLET -> "Checking your balance…"
            LIST_WALLETS -> "Looking at your wallets…"
            GET_ASSET_VALUES -> "Checking today's rate…"
            GET_FEE_QUOTE -> "Working out the fee…"
            GET_MY_MOMENTS -> "Looking at your moments…"
            LIST_TRANSACTIONS -> "Checking your payments${about(arguments(argumentsJson, HistoryArgs()))}…"
            SUMMARIZE_TRANSACTIONS -> "Adding up your payments${about(arguments(argumentsJson, HistoryArgs()))}…"
            GET_TRANSACTION -> "Opening that payment…"
            LIST_CHAMAS -> "Looking at your chamas…"
            else -> "Looking at your chama…"
        }

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        context: ToolContext,
    ): Resource<String> {
        val token = context.agentToken ?: return Resource.error(IntegrationError.Rejected("no agent token"))
        return try {
            when (toolName) {
                GET_PROFILE -> getProfile(token)
                SEARCH_CONTACTS -> searchContacts(arguments(argumentsJson, QueryArgs()), token)
                LIST_CONTACTS -> listContacts(arguments(argumentsJson, LimitArgs()), token)
                LIST_BLOCKED -> listBlocked(token)
                GET_SECURITY -> getSecurity(token)
                LIST_CHATS -> listChats(arguments(argumentsJson, LimitArgs()), token)
                GET_WALLET -> getWallet(arguments(argumentsJson, WalletArgs()), context, token)
                LIST_WALLETS -> listWallets(token)
                GET_ASSET_VALUES -> getAssetValues(arguments(argumentsJson, ValueArgs()), context, token)
                GET_FEE_QUOTE -> getFeeQuote(arguments(argumentsJson, FeeArgs()), context, token)
                GET_MY_MOMENTS -> getMyMoments(token)
                LIST_TRANSACTIONS -> listTransactions(arguments(argumentsJson, HistoryArgs()), context, token)
                SUMMARIZE_TRANSACTIONS -> summarize(arguments(argumentsJson, HistoryArgs()), context, token)
                GET_TRANSACTION -> getTransaction(arguments(argumentsJson, IdArgs()), context, token)
                LIST_CHAMAS -> listChamas(token)
                else -> getChama(arguments(argumentsJson, ChatArgs()), token)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("agent route call failed: operation={} error={}", toolName, e::class.simpleName)
            Resource.error(IntegrationError.Unavailable(LOOKUP), e)
        }
    }

    // ---- users and identity ----

    private suspend fun getProfile(token: String): Resource<String> =
        dataOf(get("/v1/agent/users/me", token) {}, AgentProfileResponse.serializer())
            .encoded(AgentProfileResponse.serializer()) { null }

    private suspend fun searchContacts(arguments: QueryArgs, token: String): Resource<String> =
        contacts(arguments.query, token).encoded(AgentContactsResponse.serializer(), ::fewerContacts)

    private suspend fun contacts(query: String, token: String): Resource<AgentContactsResponse> =
        dataOf(
            get("/v1/agent/users/contacts/search", token) {
                parameter("q", query)
                parameter("limit", SEARCH_LIMIT)
            },
            AgentContactsResponse.serializer(),
        )

    private suspend fun listContacts(arguments: LimitArgs, token: String): Resource<String> {
        val limit = (arguments.limit ?: LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)
        val response = get("/v1/agent/users/contacts", token) { parameter("limit", limit) }
        return dataOf(response, AgentContactsResponse.serializer())
            .encoded(AgentContactsResponse.serializer(), ::fewerContacts)
    }

    private suspend fun listBlocked(token: String): Resource<String> {
        val response = get("/v1/agent/users/blocks", token) { parameter("limit", MAX_LIST_LIMIT) }
        return dataOf(response, AgentBlocksResponse.serializer()).encoded(AgentBlocksResponse.serializer()) {
            it.blocked.half()?.let { fewer -> it.copy(blocked = fewer) }
        }
    }

    private suspend fun getSecurity(token: String): Resource<String> =
        dataOf(get("/v1/agent/identity/security", token) {}, AgentSecurityResponse.serializer())
            .encoded(AgentSecurityResponse.serializer()) { null }

    // ---- messaging ----

    private suspend fun listChats(arguments: LimitArgs, token: String): Resource<String> {
        val limit = (arguments.limit ?: CHATS_LIMIT).coerceIn(1, MAX_CHATS_LIMIT)
        val response = get("/v1/agent/messaging/chats", token) { parameter("limit", limit) }
        return dataOf(response, AgentChatsResponse.serializer()).encoded(AgentChatsResponse.serializer()) {
            it.chats.half()?.let { fewer -> it.copy(chats = fewer) }
        }
    }

    // ---- wallet ----

    private suspend fun getWallet(
        arguments: WalletArgs,
        context: ToolContext,
        token: String,
    ): Resource<String> {
        val walletId = arguments.walletId?.trim()?.takeIf { it.isNotEmpty() }
        if (walletId != null && !ID.matches(walletId)) return ToolOutput.message(NO_SUCH_WALLET)
        val response =
            get("/v1/agent/wallet", token) {
                currency(context)
                walletId?.let { parameter("walletId", it) }
            }
        return dataOf(response, AgentWalletResponse.serializer()).encoded(AgentWalletResponse.serializer()) { null }
    }

    private suspend fun listWallets(token: String): Resource<String> =
        dataOf(get("/v1/agent/wallet/wallets", token) {}, AgentWalletsResponse.serializer())
            .encoded(AgentWalletsResponse.serializer()) { it.wallets.half()?.let { fewer -> it.copy(wallets = fewer) } }

    /** The wallet's own rate (decision 2026-11-05): never a market price, so Asha's numbers match the app's. */
    private suspend fun getAssetValues(
        arguments: ValueArgs,
        context: ToolContext,
        token: String,
    ): Resource<String> {
        val response =
            get("/v1/agent/wallet/values", token) {
                arguments.assets
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { parameter("assets", it.take(MAX_ASSETS).joinToString(",")) }
                arguments.amount
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { parameter("amount", it) }
                (arguments.currency?.trim()?.takeIf { it.isNotEmpty() } ?: context.currency)
                    ?.let { parameter("currency", it) }
            }
        return dataOf(response, AgentAssetValuesResponse.serializer())
            .encoded(AgentAssetValuesResponse.serializer()) { null }
    }

    /**
     * The fee for a payment the user is thinking of (decision 2026-11-05). A person named by the model becomes their
     * user id as in [counterparty]; nothing is held or written, and the user sees the real fee when they pay.
     */
    private suspend fun getFeeQuote(
        arguments: FeeArgs,
        context: ToolContext,
        token: String,
    ): Resource<String> {
        val history = HistoryArgs(counterpartyName = arguments.counterpartyName, counterparty = arguments.toUserId)
        val person =
            when (val resolved = counterparty(history, token)) {
                is Counterparty.Chosen -> resolved.value
                is Counterparty.Answer -> return resolved.answer
            }
        val response =
            get("/v1/agent/wallet/fee", token) {
                parameter("amount", arguments.amount.trim())
                arguments.asset?.let { parameter("asset", it) }
                arguments.blockchain?.let { parameter("blockchain", it) }
                when {
                    person != null -> parameter("toUserId", person)
                    arguments.toGroupId != null -> parameter("toGroupId", arguments.toGroupId)
                    arguments.toAddress != null -> parameter("toAddress", arguments.toAddress)
                }
                arguments.walletId?.let { parameter("walletId", it) }
                currency(context)
            }
        return dataOf(response, AgentFeeQuoteResponse.serializer()).encoded(AgentFeeQuoteResponse.serializer()) { null }
    }

    private suspend fun listTransactions(
        arguments: HistoryArgs,
        context: ToolContext,
        token: String,
    ): Resource<String> {
        val person =
            when (val resolved = counterparty(arguments, token)) {
                is Counterparty.Chosen -> resolved.value
                is Counterparty.Answer -> return resolved.answer
            }
        val limit = (arguments.limit ?: TRANSACTIONS_LIMIT).coerceIn(1, MAX_TRANSACTIONS_LIMIT)
        val response =
            get("/v1/agent/wallet/transactions", token) {
                filters(arguments, person, context)
                arguments.cursor?.let { parameter("cursor", it) }
                parameter("limit", limit)
            }
        return dataOf(response, AgentTransactionsResponse.serializer())
            .encoded(AgentTransactionsResponse.serializer()) {
                it.transactions.half()?.let { fewer -> it.copy(transactions = fewer) }
            }
    }

    private suspend fun summarize(
        arguments: HistoryArgs,
        context: ToolContext,
        token: String,
    ): Resource<String> {
        val person =
            when (val resolved = counterparty(arguments, token)) {
                is Counterparty.Chosen -> resolved.value
                is Counterparty.Answer -> return resolved.answer
            }
        val response =
            get("/v1/agent/wallet/transactions/summary", token) {
                filters(arguments, person, context)
                arguments.top?.let { parameter("top", it.coerceIn(0, MAX_TOP)) }
            }
        return dataOf(response, AgentSummaryResponse.serializer()).encoded(AgentSummaryResponse.serializer()) {
            // the oldest months go first: the latest are what people ask about
            val months = it.byMonth.takeLast(it.byMonth.size / 2)
            if (months.size == it.byMonth.size) null else it.copy(byMonth = months)
        }
    }

    private suspend fun getTransaction(
        arguments: IdArgs,
        context: ToolContext,
        token: String,
    ): Resource<String> {
        val id = arguments.id.trim().takeIf { ID.matches(it) } ?: return ToolOutput.message(NO_SUCH_PAYMENT)
        val response = get("/v1/agent/wallet/transactions/${id.encodeURLPathPart()}", token) { currency(context) }
        return dataOf(response, AgentTransactionResponse.serializer())
            .encoded(AgentTransactionResponse.serializer()) { null }
    }

    /**
     * A person named by the model ("Mum") becomes their user id through the contacts search, so one tool call
     * answers "what did I send Mum?". Two or more plausible matches, or none, go back to the model to ask the user.
     */
    private suspend fun counterparty(arguments: HistoryArgs, token: String): Counterparty {
        arguments.counterparty
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return Counterparty.Chosen(it) }
        val name = arguments.counterpartyName?.trim()?.takeIf { it.isNotEmpty() } ?: return Counterparty.Chosen(null)
        val found =
            when (val read = contacts(name, token)) {
                is Resource.Error -> return Counterparty.Answer(read)
                is Resource.Success -> read.data.contacts
            }
        val chosen = found.filter { it.matches(name) }.singleOrNull() ?: found.singleOrNull()
        if (chosen != null) return Counterparty.Chosen(chosen.userId)
        val answer =
            if (found.isEmpty()) {
                ToolOutput.message("No contact matches \"${name.take(MAX_QUOTED)}\". Ask the user who they mean.")
            } else {
                val choice = ContactChoice("More than one contact matches. Ask the user which one they mean.", found)
                ToolOutput.encode(choice, ContactChoice.serializer()) { null }
            }
        return Counterparty.Answer(answer)
    }

    private fun AgentContactDto.matches(name: String): Boolean {
        val wanted = name.removePrefix("@").lowercase()
        return displayName.lowercase() == wanted || handle?.lowercase() == wanted
    }

    private fun HttpRequestBuilder.filters(
        arguments: HistoryArgs,
        counterparty: String?,
        context: ToolContext,
    ) {
        arguments.from?.let { parameter("from", it) }
        arguments.to?.let { parameter("to", it) }
        arguments.month?.let { parameter("month", it) }
        arguments.direction?.let { parameter("direction", it) }
        counterparty?.let { parameter("counterparty", it) }
        arguments.asset?.let { parameter("asset", it) }
        arguments.blockchain?.let { parameter("blockchain", it) }
        currency(context)
    }

    private fun HttpRequestBuilder.currency(context: ToolContext) {
        context.currency?.let { parameter("currency", it) }
    }

    // ---- chama ----

    private suspend fun listChamas(token: String): Resource<String> =
        dataOf(get("/v1/agent/chama", token) {}, AgentChamasResponse.serializer())
            .encoded(AgentChamasResponse.serializer()) { it.chamas.half()?.let { fewer -> it.copy(chamas = fewer) } }

    private suspend fun getChama(arguments: ChatArgs, token: String): Resource<String> {
        val chatId = arguments.chatId.trim().takeIf { ID.matches(it) } ?: return ToolOutput.message(NO_SUCH_CHAMA)
        val response = get("/v1/agent/chama/${chatId.encodeURLPathPart()}", token) {}
        return dataOf(response, AgentChamaResponse.serializer()).encoded(AgentChamaResponse.serializer()) {
            val chama = it.chama
            val contributors = chama.contributors.half()
            val recent = chama.recent.half()
            if (contributors == null && recent == null) {
                null
            } else {
                it.copy(chama = chama.copy(contributors = contributors.orEmpty(), recent = recent.orEmpty()))
            }
        }
    }

    // ---- status ----

    private suspend fun getMyMoments(token: String): Resource<String> =
        dataOf(get("/v1/agent/status/moments", token) {}, AgentMomentsResponse.serializer())
            .encoded(AgentMomentsResponse.serializer()) { it.moments.half()?.let { fewer -> it.copy(moments = fewer) } }

    // ---- the route call ----

    private suspend fun get(
        path: String,
        token: String,
        query: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        http.get("$baseUrl$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            query()
        }

    /**
     * The route's data, read from its envelope. A refusal (a payment that isn't the user's, a malformed argument)
     * comes back as an error carrying the route's code, for the model to read.
     */
    private suspend fun <T : Any> dataOf(response: HttpResponse, serializer: KSerializer<T>): Resource<T> {
        val envelope =
            runCatching {
                CircasoJson.decodeFromString(DefaultResponse.serializer(serializer), response.bodyAsText())
            }.getOrNull()
        val data = envelope?.data
        if (!response.status.isSuccess()) {
            return Resource.error(IntegrationError.Rejected(envelope?.code ?: "status ${response.status.value}"))
        }
        if (data != null) return Resource.success(data)
        log.warn("agent route answer didn't decode: status={}", response.status.value)
        return Resource.error(IntegrationError.Unavailable(LOOKUP))
    }

    private fun <T : Any> Resource<T>.encoded(serializer: KSerializer<T>, shrink: (T) -> T?): Resource<String> =
        when (this) {
            is Resource.Error -> this
            is Resource.Success -> ToolOutput.encode(data, serializer, shrink)
        }

    private fun fewerContacts(answer: AgentContactsResponse) =
        answer.contacts.half()?.let { answer.copy(contacts = it) }

    /** The model's arguments; ones that don't read as the tool's (the model's mistake) fall back to its defaults. */
    private inline fun <reified T> arguments(json: String, fallback: T): T =
        runCatching { CircasoJson.decodeFromString<T>(json) }.getOrDefault(fallback)

    private fun about(arguments: HistoryArgs): String {
        val who = arguments.counterpartyName?.takeIf { it.isNotBlank() }?.let { " with ${it.trim().take(MAX_QUOTED)}" }
        val period =
            when {
                arguments.month != null -> " for ${arguments.month.take(MONTH_LENGTH)}"
                arguments.from != null || arguments.to != null -> " for those dates"
                else -> ""
            }
        return who.orEmpty() + period
    }

    /** A person chosen for a filter (or none), or an answer for the model instead of the lookup. */
    private sealed interface Counterparty {
        data class Chosen(val value: String?) : Counterparty

        data class Answer(val answer: Resource<String>) : Counterparty
    }

    private companion object {
        const val GET_PROFILE = "getProfile"
        const val SEARCH_CONTACTS = "searchContacts"
        const val LIST_CONTACTS = "listContacts"
        const val LIST_BLOCKED = "listBlocked"
        const val GET_SECURITY = "getSecurityOverview"
        const val LIST_CHATS = "listChats"
        const val GET_WALLET = "getWallet"
        const val LIST_TRANSACTIONS = "listTransactions"
        const val SUMMARIZE_TRANSACTIONS = "summarizeTransactions"
        const val GET_TRANSACTION = "getTransaction"
        const val LIST_CHAMAS = "listChamas"
        const val GET_CHAMA = "getChama"
        const val LIST_WALLETS = "listWallets"
        const val GET_ASSET_VALUES = "getAssetValues"
        const val GET_FEE_QUOTE = "getFeeQuote"
        const val GET_MY_MOMENTS = "getMyMoments"
        val NAMES =
            setOf(
                GET_PROFILE,
                SEARCH_CONTACTS,
                LIST_CONTACTS,
                LIST_BLOCKED,
                GET_SECURITY,
                LIST_CHATS,
                GET_WALLET,
                LIST_TRANSACTIONS,
                SUMMARIZE_TRANSACTIONS,
                GET_TRANSACTION,
                LIST_CHAMAS,
                GET_CHAMA,
                LIST_WALLETS,
                GET_ASSET_VALUES,
                GET_FEE_QUOTE,
                GET_MY_MOMENTS,
            )

        const val LOOKUP = "the lookup"
        const val NO_SUCH_PAYMENT = "That is not a payment id from listTransactions."
        const val NO_SUCH_CHAMA = "That is not a chatId from listChamas."
        const val NO_SUCH_WALLET = "That is not a walletId from listWallets."
        const val MAX_ASSETS = 10
        const val SEARCH_LIMIT = 5
        const val LIST_LIMIT = 20
        const val MAX_LIST_LIMIT = 50
        const val CHATS_LIMIT = 30
        const val MAX_CHATS_LIMIT = 100
        const val TRANSACTIONS_LIMIT = 20
        const val MAX_TRANSACTIONS_LIMIT = 50
        const val MAX_TOP = 20
        const val MAX_QUOTED = 40
        const val MONTH_LENGTH = 7
        val ID = Regex("^[0-9a-fA-F-]{32,36}$")
    }
}

/** `listContacts`, `listChats`. */
@Serializable
private data class LimitArgs(val limit: Int? = null)

/** `listTransactions` and `summarizeTransactions`. */
@Serializable
private data class HistoryArgs(
    val from: String? = null,
    val to: String? = null,
    val month: String? = null,
    val direction: String? = null,
    val counterpartyName: String? = null,
    val counterparty: String? = null,
    val asset: String? = null,
    val blockchain: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
    val top: Int? = null,
)

/** `getWallet`: absent for the user's own wallet, or a walletId from `listWallets`. */
@Serializable
private data class WalletArgs(val walletId: String? = null)

/** `getAssetValues`: assets by name (default every supported one), an amount in whole units, a currency. */
@Serializable
private data class ValueArgs(
    val assets: List<String> = emptyList(),
    val amount: String? = null,
    val currency: String? = null,
)

/** `getFeeQuote`: the payment the user is thinking of; one payee (a contact by name or id, a group, an address). */
@Serializable
private data class FeeArgs(
    val amount: String = "",
    val asset: String? = null,
    val blockchain: String? = null,
    val counterpartyName: String? = null,
    val toUserId: String? = null,
    val toGroupId: String? = null,
    val toAddress: String? = null,
    val walletId: String? = null,
)

/** `getTransaction`. */
@Serializable
private data class IdArgs(val id: String = "")

/** `getChama`. */
@Serializable
private data class ChatArgs(val chatId: String = "")

/** The answer when a name matches more than one contact: the model asks the user which. */
@Serializable
private data class ContactChoice(val message: String, val candidates: List<AgentContactDto>)
