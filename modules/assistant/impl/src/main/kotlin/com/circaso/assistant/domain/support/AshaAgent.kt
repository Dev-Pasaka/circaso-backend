package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.ChatSnippet
import com.circaso.assistant.domain.models.MascotSceneState
import com.circaso.assistant.domain.models.ReadNote
import com.circaso.assistant.domain.models.ToolStatus
import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.BlockKind
import com.circaso.assistant.domain.ports.DeviceTools
import com.circaso.assistant.domain.ports.MascotScenes
import com.circaso.assistant.domain.ports.PendingCall
import com.circaso.assistant.domain.ports.PendingMessage
import com.circaso.assistant.domain.ports.PendingReadNote
import com.circaso.assistant.domain.ports.ReplyBlocks
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

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

/** Who and what one turn is for: the tool context, whether the phone's chat tools are on, which model, which day. */
data class AgentTurn(
    val turnId: UUID,
    val userId: UserId,
    val agentToken: String?,
    val currency: String?,
    val deviceTools: Boolean,
    val purpose: ModelPurpose,
    val today: String,
    /** The conversation the turn is in, for the mascot's scene between turns; null keeps no scene. */
    val conversationId: UUID? = null,
    /** The user's question, read only for the mascot's hardship net. Never logged. */
    val question: String = "",
) {
    val isVoice: Boolean get() = purpose == ModelPurpose.VOICE

    override fun toString(): String = "AgentTurn($turnId, $userId, $purpose)"
}

/**
 * The loop around the model. The model asks for a tool, the tool answers, the model continues, until it speaks or
 * asks the phone. Asha's own tools only read, and the token-bound ones run with the user's agent token, so the other
 * modules' routes refuse anything else; whatever the model says, it cannot write.
 *
 * Every step streams (decision 2026-10-27): its text goes to the [ReplyListener] as the model writes it, including
 * text the model writes before calling a tool ("Let me look that up."), because the user has already seen or heard
 * it. The answer saved is all of that text, a blank line between steps, so the chat keeps what the user was shown.
 * On its way out the text passes a [BlockFilter] (decision 2026-10-31): rich blocks reach the app checked and whole,
 * or not at all, and a spoken turn carries none. Each tool call is announced to the listener with the server's own
 * label before it runs, and again when it has answered.
 *
 * The mascot rides along (decision 2026-11-04): the model's `<mascot/>` tag is taken out of the text by a
 * [MascotTagFilter] and turned by a [TurnMascot] into hints for the listener, the first before any reply text, the
 * tool and block activity on the way, `done` at the end. No extra model call: the tag comes with the answer.
 */
class AshaAgent(
    private val model: LanguageModel,
    private val tools: AgentTools,
    private val config: AssistantConfig,
    private val blocks: ReplyBlocks,
    private val scenes: MascotScenes,
) {
    suspend fun run(
        history: List<ModelMessage>,
        turn: AgentTurn,
        startSteps: Int = 0,
        usedBefore: List<String> = emptyList(),
        /** Text of this turn the user was shown before it paused for the phone (A9 carries on after it). */
        shownBefore: String = "",
        listener: ReplyListener = ReplyListener.None,
        /** The device tool the phone has just answered (A9), told to the listener as `tool_done`. */
        deviceDone: ToolStatus? = null,
    ): Resource<AgentOutcome> {
        val mascot = TurnMascot(sceneOf(turn), turn.turnId, turn.question)
        deviceDone?.let { listener.toolFinished(it.copy(mascot = mascot.toolFinished()), isOk = true) }
        val messages = history.toMutableList()
        val specs = specs(turn)
        // The fixed prompt and this turn's part travel apart, so a provider's prompt cache keeps the fixed part.
        val turnPart = AshaPrompt.turn(PromptContext(turn.isVoice, turn.today, turn.currency, turn.deviceTools))
        val context = ToolContext(turn.userId, turn.agentToken, turn.currency)
        val used = usedBefore.toMutableList()
        val reply = ShownReply(shownBefore, listener, BlockFilter(blocks, turn.isVoice), mascot, turn.isVoice)
        var input = 0
        var output = 0

        var steps = startSteps
        while (steps < config.maxToolSteps) {
            steps++
            reply.newStep()
            val request = ModelRequest(AshaPrompt.system, messages, specs, turn.purpose, systemTail = turnPart)
            val response =
                when (val r = step(request, reply)) {
                    is Resource.Success -> r.data
                    is Resource.Error -> return Resource.error(AssistantError.ModelUnavailable, r.cause)
                }
            input += response.usage.inputTokens
            output += response.usage.outputTokens

            if (response.toolCalls.isEmpty()) {
                finish(turn, mascot, listener)
                return Resource.success(AgentOutcome.Answered(reply.text, input, output, used))
            }

            // One tool at a time keeps the protocol simple: extra calls are answered as skipped.
            val call = response.toolCalls.first()
            val skipped = response.toolCalls.drop(1)
            messages += ModelMessage.Assistant(response.text, listOf(call) + skipped)
            used += call.name

            if (call.name in DeviceTools.names && specs.any { it.name == call.name }) {
                skipped.forEach {
                    messages +=
                        ModelMessage.ToolResult(it.id, it.name, "Skipped: call one tool at a time.")
                }
                val label = DeviceTools.label(call.name)
                listener.toolStarted(ToolStatus(turn.turnId, call.id, call.name, label, mascot.toolStarted(call.name)))
                saveScene(turn, mascot.state)
                return Resource.success(
                    AgentOutcome.WaitingForDevice(call, messages.toPending(), steps, input, output, used),
                )
            }

            val answer =
                if (specs.none { it.name == call.name }) {
                    NOT_AVAILABLE
                } else {
                    runServerTool(call, turn, context.copy(calledBefore = used.dropLast(1)), listener, mascot)
                }
            messages += ModelMessage.ToolResult(call.id, call.name, answer)
            skipped.forEach { messages += ModelMessage.ToolResult(it.id, it.name, "Skipped: call one tool at a time.") }
        }
        reply.newStep()
        reply.show(GAVE_UP)
        finish(turn, mascot, listener)
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
                    is ModelStreamEvent.Done -> return Resource.success(event.response.also { reply.endStep(it.text) })
                    is ModelStreamEvent.Failed -> return Resource.error(event.error)
                }
            }
        } finally {
            stream.close()
        }
    }

    /** The turn has its answer: the mascot is done, and the conversation keeps its scene for the next turn. */
    private suspend fun finish(
        turn: AgentTurn,
        mascot: TurnMascot,
        listener: ReplyListener,
    ) {
        listener.mascotDone(mascot.done())
        saveScene(turn, mascot.state)
    }

    /** The mascot never fails a turn: a scene that can't be read starts from the default. */
    private suspend fun sceneOf(turn: AgentTurn): MascotSceneState {
        val conversationId = turn.conversationId ?: return MascotSceneState.NEW
        return runCatching { scenes.find(turn.userId, conversationId) }.getOrNull() ?: MascotSceneState.NEW
    }

    private suspend fun saveScene(turn: AgentTurn, state: MascotSceneState) {
        val conversationId = turn.conversationId ?: return
        runCatching { scenes.save(turn.userId, conversationId, state) }
    }

    /**
     * The reply as the user has been shown it: each step's text, a blank line between steps, after the [filter].
     * [hasModelText] is whether the model wrote anything in this step, held back by the filter or not.
     */
    private class ShownReply(
        before: String,
        private val listener: ReplyListener,
        private val filter: BlockFilter,
        private val mascot: TurnMascot,
        private val isVoice: Boolean,
    ) {
        private val shown = StringBuilder(before.trim())
        private val tags = MascotTagFilter()
        private var hasStepText = false
        private var hasModelText = false

        val text: String get() = shown.toString().trim()

        fun newStep() {
            hasStepText = false
            hasModelText = false
        }

        suspend fun add(delta: String) {
            hasModelText = true
            feed(tags.feed(delta))
        }

        /** The step is over: what the filters held goes out (a provider that sent its text only at the end too). */
        suspend fun endStep(stepText: String?) {
            if (!hasModelText && stepText != null) feed(tags.feed(stepText))
            feed(tags.finish())
            shownWithBlock(filter.finish())
        }

        /** The model's text without its mascot tags: each tag's hints go out first, then the text, block by block. */
        private suspend fun feed(piece: MascotTagFilter.Piece) {
            piece.tags.forEach { listener.mascot(mascot.proposed(it)) }
            if (piece.text.isNotEmpty()) shownWithBlock(filter.feed(piece.text))
        }

        /** Shows [text], then tells the mascot if a chart or a statement has started or ended (text replies only). */
        private suspend fun shownWithBlock(text: String) {
            show(text)
            val block = filter.openBlock
            if (block != lastBlock && !isVoice) listener.mascot(mascot.block(block))
            lastBlock = block
        }

        private var lastBlock: BlockKind? = null

        suspend fun show(delta: String) {
            var piece = if (hasStepText) delta else delta.trimStart()
            if (piece.isEmpty()) return
            if (!hasStepText && shown.isNotEmpty()) piece = STEP_BREAK + piece
            hasStepText = true
            shown.append(piece)
            if (!mascot.isAnnounced) listener.mascot(mascot.replying())
            listener.text(piece)
        }
    }

    private fun specs(turn: AgentTurn): List<ToolSpec> =
        tools.specs(turn.agentToken != null) +
            (if (turn.deviceTools) listOf(AshaPrompt.searchChats, AshaPrompt.readChat) else emptyList())

    private suspend fun runServerTool(
        call: ToolCall,
        turn: AgentTurn,
        context: ToolContext,
        listener: ReplyListener,
        mascot: TurnMascot,
    ): String {
        if (!tools.handles(call.name)) return NOT_AVAILABLE
        val label = tools.label(call.name, call.argumentsJson)
        val status = ToolStatus(turn.turnId, call.id, call.name, label, mascot.toolStarted(call.name))
        listener.toolStarted(status)
        val result = tools.call(call.name, call.argumentsJson, context)
        listener.toolFinished(status.copy(mascot = mascot.toolFinished()), result is Resource.Success)
        return when (result) {
            is Resource.Success -> "<untrusted>${result.data}</untrusted>"
            is Resource.Error -> "The lookup failed (${result.error.message}). Tell the user if it matters."
        }
    }

    companion object {
        private const val NOT_AVAILABLE = "That tool is not available."
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
