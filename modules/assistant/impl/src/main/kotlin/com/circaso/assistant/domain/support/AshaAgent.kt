package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.ChatSnippet
import com.circaso.assistant.domain.models.ReadNote
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.DeviceTools
import com.circaso.assistant.domain.ports.PendingCall
import com.circaso.assistant.domain.ports.PendingMessage
import com.circaso.assistant.domain.ports.PendingReadNote
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.Resource

/** Where the model's loop stopped: with an answer, or waiting for the phone. */
sealed interface AgentOutcome {
    val inputTokens: Int
    val outputTokens: Int
    val toolsUsed: List<String>

    data class Answered(
        val text: String,
        override val inputTokens: Int,
        override val outputTokens: Int,
        override val toolsUsed: List<String>,
    ) : AgentOutcome

    data class WaitingForDevice(
        val call: ToolCall,
        val conversation: List<PendingMessage>,
        val steps: Int,
        override val inputTokens: Int,
        override val outputTokens: Int,
        override val toolsUsed: List<String>,
    ) : AgentOutcome
}

/**
 * The loop around the model. The model asks for a tool, the tool answers, the model continues, until it speaks or
 * asks the phone. Asha's own tools only read, and they run with the user's agent token, so the other modules'
 * routes refuse anything else; whatever the model says, it cannot write.
 *
 * Every step streams (decision 2026-10-27): its text goes to the [ReplyListener] as the model writes it, including
 * text the model writes before calling a tool ("Let me look that up."), because the user has already seen or heard
 * it. The answer saved is all of that text, a blank line between steps, so the chat keeps what the user was shown.
 */
class AshaAgent(private val model: LanguageModel, private val tools: AgentTools, private val config: AssistantConfig) {
    suspend fun run(
        history: List<ModelMessage>,
        agentToken: String?,
        deviceTools: Boolean,
        startSteps: Int = 0,
        usedBefore: List<String> = emptyList(),
        /** Text of this turn the user was shown before it paused for the phone (A9 carries on after it). */
        shownBefore: String = "",
        listener: ReplyListener = ReplyListener.None,
    ): Resource<AgentOutcome> {
        val messages = history.toMutableList()
        val specs = specs(agentToken != null, deviceTools)
        val used = usedBefore.toMutableList()
        val reply = ShownReply(shownBefore, listener)
        var input = 0
        var output = 0

        var steps = startSteps
        while (steps < config.maxToolSteps) {
            steps++
            reply.newStep()
            val response =
                when (val r = step(ModelRequest(AshaPrompt.system, messages, specs), reply)) {
                    is Resource.Success -> r.data
                    is Resource.Error -> return Resource.error(AssistantError.ModelUnavailable, r.cause)
                }
            input += response.usage.inputTokens
            output += response.usage.outputTokens

            if (response.toolCalls.isEmpty()) {
                return Resource.success(AgentOutcome.Answered(reply.text, input, output, used))
            }

            // One tool at a time keeps the protocol simple: extra calls are answered as skipped.
            val call = response.toolCalls.first()
            val skipped = response.toolCalls.drop(1)
            messages += ModelMessage.Assistant(response.text, listOf(call) + skipped)
            used += call.name

            if (call.name in DeviceTools.names) {
                skipped.forEach {
                    messages +=
                        ModelMessage.ToolResult(it.id, it.name, "Skipped: call one tool at a time.")
                }
                return Resource.success(
                    AgentOutcome.WaitingForDevice(call, messages.toPending(), steps, input, output, used),
                )
            }

            messages += ModelMessage.ToolResult(call.id, call.name, runServerTool(call, agentToken))
            skipped.forEach { messages += ModelMessage.ToolResult(it.id, it.name, "Skipped: call one tool at a time.") }
        }
        reply.newStep()
        reply.add(GAVE_UP)
        return Resource.success(AgentOutcome.Answered(reply.text, input, output, used))
    }

    /** One call to the model, its text passed on as it comes; the whole answer once it is done. */
    private suspend fun step(request: ModelRequest, reply: ShownReply): Resource<ModelResponse> {
        val stream =
            when (val opened = model.stream(request)) {
                is Resource.Success -> opened.data
                is Resource.Error -> return opened
            }
        try {
            while (true) {
                when (val event = stream.next() ?: return Resource.error(IntegrationError.Unavailable("model"))) {
                    is ModelStreamEvent.Text -> reply.add(event.delta)
                    is ModelStreamEvent.ToolCallDelta -> Unit
                    is ModelStreamEvent.Done -> return Resource.success(event.response.also { reply.settle(it.text) })
                    is ModelStreamEvent.Failed -> return Resource.error(event.error)
                }
            }
        } finally {
            stream.close()
        }
    }

    /** The reply as the user has been shown it: each step's text, a blank line between steps. */
    private class ShownReply(before: String, private val listener: ReplyListener) {
        private val shown = StringBuilder(before.trim())
        private var hasStepText = false

        val text: String get() = shown.toString().trim()

        fun newStep() {
            hasStepText = false
        }

        suspend fun add(delta: String) {
            var piece = if (hasStepText) delta else delta.trimStart()
            if (piece.isEmpty()) return
            if (!hasStepText && shown.isNotEmpty()) piece = STEP_BREAK + piece
            hasStepText = true
            shown.append(piece)
            listener.text(piece)
        }

        /** A provider that reported the step's text only in its final answer still shows it. */
        suspend fun settle(stepText: String?) {
            if (!hasStepText && stepText != null) add(stepText)
        }
    }

    private fun specs(serverTools: Boolean, deviceTools: Boolean): List<ToolSpec> =
        (if (serverTools) tools.specs() else emptyList()) +
            (if (deviceTools) listOf(AshaPrompt.searchChats, AshaPrompt.readChat) else emptyList())

    private suspend fun runServerTool(call: ToolCall, agentToken: String?): String {
        if (agentToken == null || !tools.handles(call.name)) return "That tool is not available."
        return when (val result = tools.call(call.name, call.argumentsJson, agentToken)) {
            is Resource.Success -> "<untrusted>${result.data}</untrusted>"
            is Resource.Error -> "The lookup failed (${result.error.code})."
        }
    }

    companion object {
        private const val GAVE_UP = "I couldn't finish looking that up. Please try asking in a simpler way."
        private const val STEP_BREAK = "\n\n"

        /** The text of this turn the user was shown before it paused: what the model wrote since the question. */
        fun shownSoFar(conversation: List<PendingMessage>): String =
            conversation
                .takeLastWhile { it !is PendingMessage.User }
                .filterIsInstance<PendingMessage.Assistant>()
                .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString(STEP_BREAK)

        /** The phone's snippets as the model sees them: clearly marked as other people's words. */
        fun snippetsAsText(snippets: List<ChatSnippet>): String =
            if (snippets.isEmpty()) {
                "No matching messages."
            } else {
                snippets.joinToString("\n", "<untrusted>\n", "\n</untrusted>") {
                    "[${it.chatName ?: it.chatId}] ${it.sender} (${it.at}): ${it.text}"
                }
            }

        fun noteOf(snippets: List<ChatSnippet>, before: PendingReadNote): ReadNote {
            val ids = (before.chatIds + snippets.map { it.chatId }).distinct()
            val names = (before.chatNames + snippets.mapNotNull { it.chatName }).distinct()
            return ReadNote(ids, before.messageCount + snippets.size, names)
        }
    }
}

internal fun List<ModelMessage>.toPending(): List<PendingMessage> =
    map {
        when (it) {
            is ModelMessage.User -> {
                PendingMessage.User(it.text)
            }

            is ModelMessage.Assistant -> {
                PendingMessage.Assistant(
                    it.text,
                    it.toolCalls.map { c ->
                        PendingCall(c.id, c.name, c.argumentsJson)
                    },
                )
            }

            is ModelMessage.ToolResult -> {
                PendingMessage.ToolResult(it.callId, it.name, it.content)
            }
        }
    }

internal fun List<PendingMessage>.toModel(): List<ModelMessage> =
    map {
        when (it) {
            is PendingMessage.User -> {
                ModelMessage.User(it.text)
            }

            is PendingMessage.Assistant -> {
                ModelMessage.Assistant(
                    it.text,
                    it.calls.map { c ->
                        ToolCall(c.id, c.name, c.argumentsJson)
                    },
                )
            }

            is PendingMessage.ToolResult -> {
                ModelMessage.ToolResult(it.callId, it.name, it.content)
            }
        }
    }
