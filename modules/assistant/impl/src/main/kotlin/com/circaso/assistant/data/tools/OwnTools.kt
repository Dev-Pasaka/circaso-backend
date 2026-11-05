package com.circaso.assistant.data.tools

import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.assistant.domain.support.SearchAllowance
import com.circaso.assistant.domain.support.SearchVerdict
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ToolSpec
import com.circaso.integrations.api.WebSearch
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The tools that need no agent token (decision 2026-10-31): Asha's own allowance, read in this module, and the
 * public web through [WebSearch]. A search query is the model's, and the prompt forbids putting the user's private
 * data in it; results are other people's words, so the agent treats them as untrusted.
 *
 * Searches are capped per turn and per day by [SearchAllowance] (decision 2026-11-05) and counted before they run; a
 * search refused by a cap answers the model in words, so it says it couldn't check rather than guessing.
 */
class OwnTools(private val search: WebSearch, private val usage: UsageReader, private val allowance: SearchAllowance) :
    AgentTools {
    override fun specs(hasAgentToken: Boolean): List<ToolSpec> = listOf(AshaPrompt.getAshaUsage, AshaPrompt.searchWeb)

    override fun handles(toolName: String) = toolName == GET_USAGE || toolName == SEARCH_WEB

    override fun label(toolName: String, argumentsJson: String): String =
        if (toolName == GET_USAGE) {
            "Checking your Asha allowance…"
        } else {
            "Searching the web for ${quoted(queryOf(argumentsJson))}…"
        }

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        context: ToolContext,
    ): Resource<String> = if (toolName == GET_USAGE) usage(context) else search(queryOf(argumentsJson), context)

    private suspend fun usage(context: ToolContext): Resource<String> {
        val today = usage.today(context.userId)
        val answer =
            UsageAnswer(
                plan = today.plan.name,
                questionsUsed = today.textTurnsUsed,
                questionsLeft = today.textTurnsLeft,
                voiceSecondsUsed = today.voiceSecondsUsed,
                voiceSecondsLeft = today.voiceSecondsLeft,
                searchesUsed = today.webSearchesUsed,
                searchesLeft = today.webSearchesLeft,
                resetsAt = today.resetsAt.toString(),
            )
        return Resource.success(CircasoJson.encodeToString(UsageAnswer.serializer(), answer))
    }

    private suspend fun search(query: String, context: ToolContext): Resource<String> {
        if (query.isBlank()) return ToolOutput.message("Say what to search for.")
        val earlier = context.calledBefore.count { it == SEARCH_WEB }
        when (allowance.take(context.userId, earlier)) {
            SearchVerdict.TURN_LIMIT -> return ToolOutput.message(TURN_LIMIT)
            SearchVerdict.DAY_LIMIT -> return ToolOutput.message(DAY_LIMIT)
            SearchVerdict.ALLOWED -> Unit
        }
        val results =
            when (val found = search.search(query.take(MAX_QUERY_CHARS), MAX_RESULTS)) {
                is Resource.Error -> return Resource.error(IntegrationError.Unavailable("web search"), found.cause)
                is Resource.Success -> found.data
            }
        val answer =
            SearchAnswer(
                results.take(MAX_RESULTS).map {
                    SearchResult(
                        title = it.title.take(MAX_TITLE_CHARS),
                        url = it.url,
                        snippet = it.snippet.take(MAX_SNIPPET_CHARS),
                        published = it.publishedAt?.let { at -> LocalDate.ofInstant(at, ZoneOffset.UTC).toString() },
                    )
                },
            )
        return ToolOutput.encode(answer, SearchAnswer.serializer()) { it.results.half()?.let(::SearchAnswer) }
    }

    private fun queryOf(argumentsJson: String): String =
        runCatching { CircasoJson.decodeFromString(QueryArgs.serializer(), argumentsJson) }.getOrNull()?.query.orEmpty()

    private companion object {
        const val GET_USAGE = "getAshaUsage"
        const val SEARCH_WEB = "searchWeb"
        const val MAX_RESULTS = 5
        const val MAX_QUERY_CHARS = 200
        const val MAX_TITLE_CHARS = 120
        const val MAX_SNIPPET_CHARS = 400
        const val TURN_LIMIT =
            "No more web searches in this answer. Answer with what you found, and say what you couldn't check."
        const val DAY_LIMIT =
            "The user's web searches for today are used up. Answer without searching, say plainly what you " +
                "couldn't check, and that searching is back tomorrow."
    }
}

@Serializable
private data class UsageAnswer(
    val plan: String,
    val questionsUsed: Int,
    val questionsLeft: Int,
    val voiceSecondsUsed: Int,
    val voiceSecondsLeft: Int,
    val searchesUsed: Int,
    val searchesLeft: Int,
    val resetsAt: String,
)

/** [published] is the page's date (`YYYY-MM-DD`) when the search vendor knows it. */
@Serializable
private data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val published: String? = null,
)

@Serializable
private data class SearchAnswer(val results: List<SearchResult>)
