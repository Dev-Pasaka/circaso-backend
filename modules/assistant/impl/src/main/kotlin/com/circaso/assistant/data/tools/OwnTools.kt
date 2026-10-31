package com.circaso.assistant.data.tools

import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ToolSpec
import com.circaso.integrations.api.WebSearch
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import kotlinx.serialization.Serializable

/**
 * The tools that need no agent token (decision 2026-10-31): Asha's own allowance, read in this module, and the
 * public web through [WebSearch]. A search query is the model's, and the prompt forbids putting the user's private
 * data in it; results are other people's words, so the agent treats them as untrusted.
 */
class OwnTools(private val search: WebSearch, private val usage: UsageReader) : AgentTools {
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
    ): Resource<String> = if (toolName == GET_USAGE) usage(context) else search(queryOf(argumentsJson))

    private suspend fun usage(context: ToolContext): Resource<String> {
        val today = usage.today(context.userId)
        val answer =
            UsageAnswer(
                plan = today.plan.name,
                questionsUsed = today.textTurnsUsed,
                questionsLeft = today.textTurnsLeft,
                voiceSecondsUsed = today.voiceSecondsUsed,
                voiceSecondsLeft = today.voiceSecondsLeft,
                resetsAt = today.resetsAt.toString(),
            )
        return Resource.success(CircasoJson.encodeToString(UsageAnswer.serializer(), answer))
    }

    private suspend fun search(query: String): Resource<String> {
        if (query.isBlank()) return ToolOutput.message("Say what to search for.")
        val results =
            when (val found = search.search(query.take(MAX_QUERY_CHARS), MAX_RESULTS)) {
                is Resource.Error -> return Resource.error(IntegrationError.Unavailable("web search"), found.cause)
                is Resource.Success -> found.data
            }
        val answer =
            SearchAnswer(
                results.take(MAX_RESULTS).map {
                    SearchResult(it.title.take(MAX_TITLE_CHARS), it.url, it.snippet.take(MAX_SNIPPET_CHARS))
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
    }
}

@Serializable
private data class UsageAnswer(
    val plan: String,
    val questionsUsed: Int,
    val questionsLeft: Int,
    val voiceSecondsUsed: Int,
    val voiceSecondsLeft: Int,
    val resetsAt: String,
)

@Serializable
private data class SearchResult(val title: String, val url: String, val snippet: String)

@Serializable
private data class SearchAnswer(val results: List<SearchResult>)
