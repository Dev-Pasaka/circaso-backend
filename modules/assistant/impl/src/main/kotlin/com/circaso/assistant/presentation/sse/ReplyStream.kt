package com.circaso.assistant.presentation.sse

import com.circaso.assistant.domain.models.ToolStatus
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.support.ReplyListener
import com.circaso.assistant.presentation.dto.DeltaEvent
import com.circaso.assistant.presentation.dto.DoneEvent
import com.circaso.assistant.presentation.dto.ErrorEvent
import com.circaso.assistant.presentation.dto.ToolDoneEvent
import com.circaso.assistant.presentation.dto.ToolRequestEvent
import com.circaso.assistant.presentation.dto.ToolStatusEvent
import com.circaso.assistant.presentation.mappers.toDoneEvent
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.web.EnvelopeSent
import com.circaso.web.respondError
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.Writer

/** What goes down the event stream while the turn runs: a piece of text, or a tool starting or finishing. */
private sealed interface StreamItem {
    data class Text(val delta: String) : StreamItem

    data class ToolStarted(val status: ToolStatus) : StreamItem

    data class ToolFinished(val status: ToolStatus, val isOk: Boolean) : StreamItem
}

/** Puts the turn's text and tool activity on [items], in the order they happen. */
private class StreamingListener(private val items: Channel<StreamItem>) : ReplyListener {
    override suspend fun text(delta: String) = items.send(StreamItem.Text(delta))

    override suspend fun toolStarted(status: ToolStatus) = items.send(StreamItem.ToolStarted(status))

    override suspend fun toolFinished(status: ToolStatus, isOk: Boolean) =
        items.send(StreamItem.ToolFinished(status, isOk))
}

/**
 * Runs the turn and streams its reply as the model writes it (decision 2026-10-27): a `message` event per piece of
 * text, `tool_status` and `tool_done` around each tool call (decision 2026-10-31), then `done` (or `tool_request`).
 * A refusal before anything is sent answers with the envelope and its status; a failure after that can only be an
 * `error` event, since the `200` is already sent.
 *
 * The turn runs to its end even if the phone goes away, as a spoken turn does: once the model is asked, the exchange
 * is saved.
 */
internal suspend fun ApplicationCall.streamReply(turn: suspend (ReplyListener) -> Resource<TurnResult>) =
    coroutineScope {
        val items = Channel<StreamItem>(Channel.UNLIMITED)
        val result =
            async {
                withContext(NonCancellable) { turn(StreamingListener(items)) }.also { items.close() }
            }

        val first = items.receiveCatching().getOrNull()
        val refused = (if (first == null) result.await() else null) as? Resource.Error
        if (refused != null) return@coroutineScope respondError(refused.error)

        attributes.put(EnvelopeSent, true)
        response.cacheControl(CacheControl.NoCache(null))
        respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            first?.let { item(it) }
            for (item in items) item(item)
            finished(result.await())
        }
    }

private fun Writer.event(name: String, json: String) {
    write("event: $name\ndata: $json\n\n")
    flush()
}

private fun Writer.item(item: StreamItem) =
    when (item) {
        is StreamItem.Text -> {
            event("message", CircasoJson.encodeToString(DeltaEvent.serializer(), DeltaEvent(item.delta)))
        }

        is StreamItem.ToolStarted -> {
            val status = item.status
            val data = ToolStatusEvent(status.turnId.toString(), status.callId, status.tool, status.label)
            event("tool_status", CircasoJson.encodeToString(ToolStatusEvent.serializer(), data))
        }

        is StreamItem.ToolFinished -> {
            val status = item.status
            val data = ToolDoneEvent(status.turnId.toString(), status.callId, status.tool, item.isOk)
            event("tool_done", CircasoJson.encodeToString(ToolDoneEvent.serializer(), data))
        }
    }

private fun Writer.finished(result: Resource<TurnResult>) {
    val turn =
        when (result) {
            is Resource.Error -> {
                val error = ErrorEvent(result.error.code, result.error.message)
                return event("error", CircasoJson.encodeToString(ErrorEvent.serializer(), error))
            }

            is Resource.Success -> {
                result.data
            }
        }
    when (turn) {
        is TurnResult.Answer -> {
            event("done", CircasoJson.encodeToString(DoneEvent.serializer(), turn.toDoneEvent()))
        }

        is TurnResult.NeedsDevice -> {
            val request =
                ToolRequestEvent(
                    turn.turnId.toString(),
                    turn.callId,
                    turn.toolName,
                    turn.argumentsJson,
                    turn.conversationId.toString(),
                )
            event("tool_request", CircasoJson.encodeToString(ToolRequestEvent.serializer(), request))
        }
    }
}
