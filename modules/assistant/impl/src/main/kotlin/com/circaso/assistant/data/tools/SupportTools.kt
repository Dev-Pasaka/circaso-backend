package com.circaso.assistant.data.tools

import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.support.api.NewTicket
import com.circaso.support.api.SupportTickets
import com.circaso.support.api.TicketCategory
import com.circaso.support.api.TicketContext
import com.circaso.support.api.TicketSource
import com.circaso.support.api.TicketStatus
import kotlinx.serialization.Serializable

/**
 * Asha's one tool that writes (decision 2026-11-08): `openSupportTicket` opens a support ticket for the user she is
 * talking to, with source `ASHA`, through support:api's [SupportTickets], the same use case as the app's SU1, so the
 * same rules and the same daily cap. The rules for when (only after trying to help, only once the user said yes) are
 * in its description and the prompt; the server holds her to once per turn.
 *
 * It needs no agent token, like [OwnTools]: the agent token is the read-only proof for the other modules' agent
 * routes, and this is the user's own action, taken on their word in this conversation, in-process with their user id.
 * So it is offered whether or not the app sent a token.
 *
 * Once per turn, strictly, so a model that loops can't open two tickets for one request: a second call in the same
 * answer is refused whatever became of the first. So the tool fits the texts to support's bounds itself (a subject cut
 * at a word to 120 characters, a summary to 4,000), and every refusal that is left (texts too short, a payment or
 * chat that isn't theirs, the daily cap) answers the model in words to pass on, with "in your next answer" where a
 * retry makes sense, never an error, so a refused ticket doesn't end her answer.
 */
class SupportTools(private val tickets: SupportTickets) : AgentTools {
    override fun specs(hasAgentToken: Boolean): List<ToolSpec> = listOf(AshaPrompt.openSupportTicket)

    override fun handles(toolName: String) = toolName == OPEN_TICKET

    override fun label(toolName: String, argumentsJson: String): String = "Opening a support ticket…"

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        context: ToolContext,
    ): Resource<String> {
        if (OPEN_TICKET in context.calledBefore) return ToolOutput.message(ONCE)
        val args =
            runCatching { CircasoJson.decodeFromString(TicketArgs.serializer(), argumentsJson) }.getOrNull()
                ?: return ToolOutput.message(ARGUMENTS)
        val category = TicketCategory.entries.firstOrNull { it.name == args.category?.trim()?.uppercase() }
        val transferId = args.transferId?.let { Uuid7.parse(it.trim()) ?: return ToolOutput.message(BAD_TRANSFER) }
        val chatId = args.chatId?.let { Uuid7.parse(it.trim()) ?: return ToolOutput.message(BAD_CHAT) }
        val ticket =
            NewTicket(
                userId = context.userId,
                subject =
                    fit(
                        args.subject
                            .orEmpty()
                            .replace('\n', ' ')
                            .trim(),
                        MAX_SUBJECT,
                    ),
                description = fit(args.summary.orEmpty().trim(), MAX_SUMMARY),
                category = category ?: TicketCategory.OTHER,
                context = TicketContext(transferId, chatId),
                source = TicketSource.ASHA,
            )
        return when (val opened = tickets.open(ticket)) {
            is Resource.Success -> {
                val answer = OpenedAnswer(opened.data.number, TicketStatus.OPEN.name)
                Resource.success(CircasoJson.encodeToString(OpenedAnswer.serializer(), answer))
            }

            is Resource.Error -> {
                ToolOutput.message(refusal(opened.error.code))
            }
        }
    }

    /** [text] in at most [max] characters, cut at the last space before it when there is one. */
    private fun fit(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        return cut.substringBeforeLast(' ', cut).trimEnd()
    }

    private fun refusal(code: String): String =
        when (code) {
            "support.subject_invalid", "support.description_invalid" -> TOO_SHORT
            "support.context_invalid" -> CONTEXT
            "support.rate_limited" -> RATE_LIMITED
            else -> UNAVAILABLE
        }

    private companion object {
        const val OPEN_TICKET = "openSupportTicket"
        const val ONCE = "A ticket was already opened in this answer. Don't open another; tell the user its number."
        const val ARGUMENTS = "Give a subject, a summary and a category."
        const val BAD_TRANSFER = "transferId must be a payment id exactly as a tool gave it, or left out."
        const val BAD_CHAT = "chatId must be a chat id exactly as a tool gave it, or left out."
        const val MAX_SUBJECT = 120
        const val MAX_SUMMARY = 4_000
        const val TOO_SHORT =
            "The ticket wasn't opened: the subject needs at least 3 characters and the summary at least 10. Ask " +
                "the user for a little more detail, then open it in your next answer."
        const val CONTEXT =
            "The ticket wasn't opened: that payment or chat isn't one of the user's. Check the id with the wallet " +
                "or chat tools, or leave it out, and open the ticket in your next answer."
        const val RATE_LIMITED =
            "The ticket wasn't opened: the user has opened the most tickets allowed today. Don't try again. Tell " +
                "them, and that they can add to a ticket they already have from Support in the app."
        const val UNAVAILABLE =
            "The ticket couldn't be opened right now. Tell the user, and that they can open one from Support in " +
                "the app."
    }
}

/** `openSupportTicket` (its schema is in `AshaPrompt`). */
@Serializable
private data class TicketArgs(
    val subject: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val transferId: String? = null,
    val chatId: String? = null,
)

@Serializable
private data class OpenedAnswer(val ticketNumber: String, val status: String)
