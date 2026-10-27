package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.ToolCall
import org.slf4j.LoggerFactory
import kotlin.math.ceil

/**
 * A chat completion streamed with `stream: true`: OpenAI-style server-sent events, one `data: {chunk}` line per
 * piece and `data: [DONE]` at the end. A chunk's `choices[0].delta` holds the next text (`content`) or pieces of tool
 * calls (`tool_calls`, joined by their `index`: the id and name come once, the arguments a fragment at a time).
 * Reasoning (`reasoning_content`) is never passed on.
 *
 * The answer is whole at `[DONE]`, or at the end of the body after a `finish_reason`; a body that stops before
 * either, an error chunk or a chunk that isn't JSON is a failure, as is an answer with neither text nor a tool call.
 * Usage comes from the chunk that carries it (`stream_options.include_usage`); when Workers AI leaves it out it is
 * estimated at four characters a token, so metering never counts a turn as free. Nothing of the answer is logged.
 */
internal class ChatCompletionStream(private val lines: GatewayStream, private val requestChars: Int) : ModelStream {
    private val log = LoggerFactory.getLogger(ChatCompletionStream::class.java)
    private val pending = ArrayDeque<ModelStreamEvent>()
    private val text = StringBuilder()
    private val calls = sortedMapOf<Int, CallParts>()
    private var usage: ModelUsage? = null
    private var finishReason: String? = null
    private var isOver = false

    private class CallParts(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    override suspend fun next(): ModelStreamEvent? {
        while (pending.isEmpty()) {
            if (isOver) return null
            read()
        }
        return pending.removeFirst()
    }

    override suspend fun close() {
        isOver = true
        pending.clear()
        lines.close()
    }

    private suspend fun read() {
        val line = lines.nextLine() ?: return end(sawDone = false)
        if (!line.startsWith(DATA)) return // blank lines between events, `:` comments, `event:` names
        val data = line.removePrefix(DATA).trim()
        if (data == DONE) return end(sawDone = true)

        val chunk =
            runCatching { WorkersAiJson.decodeFromString(ChatCompletion.serializer(), data) }.getOrNull()
                ?: return fail("a chunk that isn't a chat completion chunk")
        if (chunk.error != null) return fail("an error chunk")
        read(chunk)
    }

    private fun read(chunk: ChatCompletion) {
        chunk.usage?.let { usage = it.toModel() }
        val choice = chunk.choices.firstOrNull() ?: return
        choice.finishReason?.let { finishReason = it }
        val delta = choice.delta ?: return

        delta.content?.takeIf { it.isNotEmpty() }?.let {
            text.append(it)
            pending.add(ModelStreamEvent.Text(it))
        }
        delta.toolCalls.forEachIndexed { position, call -> toolCallPiece(call, position) }
    }

    private fun toolCallPiece(call: ChatToolCall, position: Int) {
        val index = call.index ?: position
        val name = call.function?.name
        val arguments = call.function?.arguments.orEmpty()

        val parts = calls.getOrPut(index) { CallParts() }
        if (parts.id == null) parts.id = call.id
        if (parts.name == null) parts.name = name
        parts.arguments.append(arguments)
        pending.add(ModelStreamEvent.ToolCallDelta(index, call.id, name, arguments))
    }

    private fun end(sawDone: Boolean) {
        if (lines.isBroken) return fail("the answer was cut off")
        if (!sawDone && finishReason == null) return fail("the answer stopped before its end")
        isOver = true
        lines.close()

        val toolCalls = toolCalls()
        val answer = text.toString().takeIf { it.isNotBlank() }
        if (answer == null && toolCalls.isEmpty()) {
            log.warn("workers-ai chat stream was empty: finish_reason={}", finishReason)
            pending.add(ModelStreamEvent.Failed(IntegrationError.Unavailable(WorkersAiGateway.VENDOR)))
            return
        }
        pending.add(ModelStreamEvent.Done(ModelResponse(answer, toolCalls, usage ?: estimate(toolCalls))))
    }

    /** A call with no name can't be run; one whose id never came gets one, so its result can still answer it. */
    private fun toolCalls(): List<ToolCall> =
        calls.mapNotNull { (index, parts) ->
            val name = parts.name ?: return@mapNotNull null
            ToolCall(parts.id ?: "call-$index", name, parts.arguments.toString().ifEmpty { "{}" })
        }

    private fun estimate(toolCalls: List<ToolCall>): ModelUsage {
        val written = text.length + toolCalls.sumOf { it.name.length + it.argumentsJson.length }
        return ModelUsage(tokensOf(requestChars), tokensOf(written))
    }

    private fun tokensOf(chars: Int) = ceil(chars / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)

    private fun fail(why: String) {
        log.warn("workers-ai chat stream failed: {}", why)
        isOver = true
        lines.close()
        pending.add(ModelStreamEvent.Failed(IntegrationError.Unavailable(WorkersAiGateway.VENDOR)))
    }

    private companion object {
        const val DATA = "data:"
        const val DONE = "[DONE]"
        const val CHARS_PER_TOKEN = 4.0
    }
}
