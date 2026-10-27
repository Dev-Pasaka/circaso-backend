package com.circaso.assistant.presentation.controllers

import com.circaso.assistant.domain.models.LiveEnd
import com.circaso.assistant.domain.models.LiveEvent
import com.circaso.assistant.domain.support.LiveConversation
import com.circaso.assistant.domain.support.LiveOutput
import com.circaso.assistant.presentation.dto.LiveAuthFrame
import com.circaso.assistant.presentation.dto.LiveClientFrame
import com.circaso.assistant.presentation.dto.LiveErrorFrame
import com.circaso.assistant.presentation.dto.ToolResultsRequest
import com.circaso.assistant.presentation.mappers.toClosing
import com.circaso.assistant.presentation.mappers.toCommand
import com.circaso.assistant.presentation.mappers.toJson
import com.circaso.assistant.presentation.mappers.toReadyJson
import com.circaso.assistant.presentation.mappers.toRequest
import com.circaso.assistant.presentation.services.LiveVoiceService
import com.circaso.shared.CircasoJson
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import io.ktor.server.plugins.callid.callId
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.assistant.presentation.controllers.LiveVoice")

/** As on the main socket: the first frame must authenticate within this window. */
private const val AUTH_WINDOW_MILLIS = 10_000L

/**
 * Frames on this socket are at most 64 KB both ways, like the main socket (decision 2026-10-25). Opus packets are far
 * smaller; Asha's audio is split to fit. A frame over the limit closes with 1009 and no `closing` frame.
 */
internal const val MAX_FRAME_BYTES = 64 * 1024

/**
 * A11: the live voice socket, `wss://…/v1/assistant/voice/live` (Assistant → Live voice, Phase 1). Like the main
 * socket it authenticates with its first frame, not a header, and takes the user token only. Binary frames are audio
 * both ways (Opus from the phone, Asha's mulaw back); text frames are JSON with a `type` (the protocol is in
 * docs/mobile-integration.md). Nothing said is logged, and no audio is kept.
 */
fun Route.liveVoiceController(live: LiveVoiceService) {
    route("/assistant/voice") {
        webSocket("live") { serve(live) }
    }
}

private suspend fun DefaultWebSocketServerSession.serve(live: LiveVoiceService) {
    val auth = readAuth()
    if (auth == null) {
        // the socket may already be closed (a first frame over 64 KB)
        runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth required")) }
        return
    }
    val conversation =
        when (val opened = live.open(auth.toRequest(call.callId))) {
            is Resource.Success -> opened.data
            is Resource.Error -> return refuse(opened.error)
        }

    val end =
        try {
            send(Frame.Text(conversation.line.toReadyJson()))
            log.info("live voice opened: user={}", conversation.line.userId)
            converse(conversation)
        } finally {
            // from a successful opening on, the provider sessions are closed however this ends
            withContext(NonCancellable) { conversation.release() }
        }
    say(end)
    log.info(
        "live voice closed: user={} end={} turns={} voiceSeconds={}",
        conversation.line.userId,
        end::class.simpleName,
        conversation.turnsAsked.get(),
        conversation.secondsMetered.get(),
    )
}

/**
 * Tells the phone why the server is closing, then closes. A revoked session closes like a bad token on the main socket:
 * 1008 `unauthorized` and no frame.
 */
private suspend fun DefaultWebSocketServerSession.say(end: LiveEnd) {
    if (end is LiveEnd.SessionRevoked) {
        runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized")) }
        return
    }
    if (end is LiveEnd.FrameTooBig) {
        // Ktor's own close reason, kept for the backstop; already sent when the plugin limit closed the socket
        end.bytes?.let { runCatching { close(CloseReason(CloseReason.Codes.TOO_BIG, "Frame is too big: $it")) } }
        return
    }
    val closing = end.toClosing() ?: return
    runCatching {
        send(Frame.Text(closing.toJson()))
        close(CloseReason(end.closeCode(), closing.reason))
    }
}

private suspend fun DefaultWebSocketServerSession.readAuth(): LiveAuthFrame? {
    val first =
        withTimeoutOrNull(AUTH_WINDOW_MILLIS) { incoming.receiveCatching().getOrNull() } as? Frame.Text ?: return null
    val auth = runCatching { CircasoJson.decodeFromString(LiveAuthFrame.serializer(), first.readText()) }.getOrNull()
    return auth?.takeIf { it.type == "auth" && it.accessToken.isNotBlank() }
}

/**
 * A refused opening. A bad or agent token closes the socket the way the main socket does; a language without a live
 * voice, a used-up allowance or a provider outage first says why in a `closing` frame, so the phone can fall back to
 * clips (A3), show the Asha Plus offer, or try later.
 */
private suspend fun DefaultWebSocketServerSession.refuse(error: DomainError) {
    if (error is SharedError) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        return
    }
    val closing = error.toClosing()
    log.info("live voice refused: code={}", error.code)
    send(Frame.Text(closing.toJson()))
    val code =
        if (error.kind ==
            ErrorKind.UPSTREAM
        ) {
            CloseReason.Codes.INTERNAL_ERROR
        } else {
            CloseReason.Codes.VIOLATED_POLICY
        }
    close(CloseReason(code, closing.reason))
}

/** Runs the conversation while reading the phone's frames, until one side ends it. */
private suspend fun DefaultWebSocketServerSession.converse(conversation: LiveConversation): LiveEnd =
    coroutineScope {
        val output = LiveOutput { event -> deliver(event, conversation) }
        val running = async(start = CoroutineStart.UNDISPATCHED) { conversation.run(output) }
        val reading = launch { readPhone(conversation) }
        val end = running.await()
        reading.cancel()
        end
    }

private suspend fun DefaultWebSocketServerSession.readPhone(conversation: LiveConversation) {
    try {
        for (frame in incoming) {
            if (frame.data.size > MAX_FRAME_BYTES) {
                // the gateway's plugin limit closes first; this is the backstop, with the same reason
                conversation.hangUp(LiveEnd.FrameTooBig(frame.data.size.toLong()))
                return
            }
            when (frame) {
                is Frame.Binary -> conversation.audio(frame.readBytes())
                is Frame.Text -> phoneSaid(frame.readText(), conversation)
                else -> Unit
            }
        }
    } catch (_: FrameTooBigException) {
        // Ktor has already closed the socket with 1009 "Frame is too big: N"; this ends the conversation normally
        conversation.hangUp(LiveEnd.FrameTooBig(null))
        return
    }
    conversation.hangUp(LiveEnd.PhoneGone)
}

/**
 * `end` hangs up; `tool_results` answers a `tool_request`; `auth` replaces the tokens before they expire. Anything else
 * is ignored, as on the main socket.
 */
private suspend fun DefaultWebSocketServerSession.phoneSaid(text: String, conversation: LiveConversation) {
    val frame = runCatching { CircasoJson.decodeFromString(LiveClientFrame.serializer(), text) }.getOrNull()
    if (frame == null) {
        // a `tool_results` the phone got wrong is answered, so the phone can send it again; the turn keeps waiting
        if (typeOf(text) == "tool_results") sendError(SharedError.Malformed("snippets", "are not valid"))
        return
    }
    when (frame.type) {
        "end" -> {
            conversation.hangUp()
        }

        "auth" -> {
            conversation.refresh(frame.accessToken, frame.agentToken?.takeIf { it.isNotBlank() })
        }

        "tool_results" -> {
            val result =
                ToolResultsRequest(frame.callId, frame.snippets).toCommand(
                    conversation.line.userId,
                    frame.turnId,
                    conversation.agentToken,
                    conversation.line.start.requestId,
                )
            when (result) {
                is Resource.Success -> conversation.deviceResult(result.data)
                is Resource.Error -> sendError(result.error)
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.deliver(event: LiveEvent, conversation: LiveConversation) {
    try {
        val json = event.toJson()
        if (json != null) {
            send(Frame.Text(json))
        } else if (event is LiveEvent.ReplyAudio) {
            sendAudio(event.bytes)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug("live voice frame not sent: error={}", e::class.simpleName)
        conversation.hangUp(LiveEnd.PhoneGone)
    }
}

/** A piece of Asha's speech, in frames of at most [MAX_FRAME_BYTES]. */
private suspend fun DefaultWebSocketServerSession.sendAudio(bytes: ByteArray) {
    var start = 0
    while (start < bytes.size) {
        val end = minOf(start + MAX_FRAME_BYTES, bytes.size)
        send(Frame.Binary(true, bytes.copyOfRange(start, end)))
        start = end
    }
}

private suspend fun DefaultWebSocketServerSession.sendError(error: DomainError) =
    send(
        Frame.Text(
            CircasoJson.encodeToString(LiveErrorFrame.serializer(), LiveErrorFrame(error.code, error.message)),
        ),
    )

/** The `type` of a frame that didn't decode as a whole, if it is JSON with one. */
private fun typeOf(text: String): String? =
    runCatching {
        CircasoJson
            .parseToJsonElement(text)
            .jsonObject["type"]
            ?.jsonPrimitive
            ?.content
    }.getOrNull()

private fun LiveEnd.closeCode(): CloseReason.Codes =
    when (this) {
        is LiveEnd.OutOfAllowance -> CloseReason.Codes.VIOLATED_POLICY
        is LiveEnd.ProviderFailed -> CloseReason.Codes.INTERNAL_ERROR
        else -> CloseReason.Codes.NORMAL
    }
