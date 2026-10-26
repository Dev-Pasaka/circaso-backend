package com.circaso.assistant.presentation.controllers

import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.presentation.dto.ChatResponse
import com.circaso.assistant.presentation.dto.DeltaEvent
import com.circaso.assistant.presentation.dto.DoneEvent
import com.circaso.assistant.presentation.dto.SendMessageRequest
import com.circaso.assistant.presentation.dto.ToolRequestEvent
import com.circaso.assistant.presentation.dto.ToolResultsRequest
import com.circaso.assistant.presentation.mappers.parseUuid
import com.circaso.assistant.presentation.mappers.toCommand
import com.circaso.assistant.presentation.mappers.toDto
import com.circaso.assistant.presentation.mappers.toResponse
import com.circaso.assistant.presentation.services.ChatService
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.web.EnvelopeSent
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.respondError
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Asha chat"
private const val SCOPE = "Scope: `app` (the user token)."
private const val AGENT_TOKEN_HEADER = "X-Agent-Token"

/** Words per streamed chunk. The reply is complete before it is sent; chunking lets the app show it as it arrives. */
private const val WORDS_PER_CHUNK = 4

/** The Asha chat: asking, the phone's answers to device tools, reading and deleting the history. A1, A2, A5, A6 and A9. */
fun Route.chatController(chat: ChatService) {
    authenticate(Scope.APP) {
        route("/assistant/chat") {
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                call.respondDefault(
                    chat
                        .listChat(
                            call.userId(),
                            call.request.queryParameters["cursor"],
                            limit,
                        ).toDefaultResponse {
                            it.toResponse()
                        },
                )
            }.docs<NoBody, ChatResponse>(
                "A1 · The Asha chat, newest first",
                TAG,
                notes = "$SCOPE Query: `cursor`, `limit` (default 50, at most 100).",
            )

            delete {
                call.respondDefault(chat.deleteAll(call.userId(), call.callId).toDefaultResponse { it })
            }.docs<NoBody, NoBody>("A5 · Delete the whole history with Asha", TAG, notes = SCOPE)

            route("/messages") {
                post {
                    val command =
                        call.receive<SendMessageRequest>().toCommand(
                            call.userId(),
                            call.request.header(AGENT_TOKEN_HEADER),
                            call.callId,
                        )
                    call.stream(command.flatMap { chat.ask(it) })
                }.docs<SendMessageRequest, NoBody>(
                    "A2 · Ask Asha; the reply streams as server-sent events",
                    TAG,
                    listOf(
                        "assistant.quota_exceeded",
                        "assistant.model_unavailable",
                        "auth.token_invalid",
                        "request.malformed",
                    ),
                    notes =
                        "$SCOPE Send the user's own agent token in `X-Agent-Token` (another user's is refused) " +
                            "so Asha's tools can read " +
                            "contacts. " +
                            "The response is `text/event-stream`, not the envelope (errors before any text are " +
                            "the " +
                            "envelope): " +
                            "`message` events carry `{delta}`; a `tool_request` event `{turnId, callId, name, " +
                            "arguments}` asks the phone to run a device tool " +
                            "and answer with A9; `done` carries the saved reply.",
                )

                route("/{messageId}") {
                    delete {
                        val id = parseUuid(call.parameters["messageId"])
                        val result: Resource<Unit> =
                            if (id ==
                                null
                            ) {
                                Resource.error(SharedError.Malformed(mapOf("messageId" to "is not valid")))
                            } else {
                                chat.deleteOne(call.userId(), id, call.callId)
                            }
                        call.respondDefault(result.toDefaultResponse { it })
                    }.docs<NoBody, NoBody>("A6 · Delete one exchange", TAG, notes = SCOPE)

                    post("tool-results") {
                        val command =
                            call
                                .receive<ToolResultsRequest>()
                                .toCommand(
                                    call.userId(),
                                    call.parameters["messageId"],
                                    call.request.header(AGENT_TOKEN_HEADER),
                                    call.callId,
                                )
                        call.stream(command.flatMap { chat.answerDevice(it) })
                    }.docs<ToolResultsRequest, NoBody>(
                        "A9 · The phone's answer to a device tool; the reply then continues as server-sent events",
                        TAG,
                        listOf("assistant.turn_not_found", "auth.token_invalid", "request.malformed"),
                        notes =
                            "$SCOPE `messageId` is the `turnId` from the `tool_request` event. At most 30 short " +
                                "snippets; they are used for this turn and never stored.",
                    )
                }
            }
        }
    }
}

/** An error before any text answers with the envelope; otherwise the reply, or the question for the phone, streams as SSE. */
private suspend fun ApplicationCall.stream(result: Resource<TurnResult>) {
    val turn =
        when (result) {
            is Resource.Error -> return respondError(result.error)
            is Resource.Success -> result.data
        }
    attributes.put(EnvelopeSent, true)
    response.cacheControl(CacheControl.NoCache(null))
    respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
        fun event(name: String, json: String) {
            write("event: $name\ndata: $json\n\n")
            flush()
        }
        when (turn) {
            is TurnResult.Answer -> {
                turn.message.text.split(' ').chunked(WORDS_PER_CHUNK).forEachIndexed { i, words ->
                    val delta = (if (i == 0) "" else " ") + words.joinToString(" ")
                    event("message", CircasoJson.encodeToString(DeltaEvent.serializer(), DeltaEvent(delta)))
                }
                event("done", CircasoJson.encodeToString(DoneEvent.serializer(), DoneEvent(turn.message.toDto())))
            }

            is TurnResult.NeedsDevice -> {
                event(
                    "tool_request",
                    CircasoJson.encodeToString(
                        ToolRequestEvent.serializer(),
                        ToolRequestEvent(turn.turnId.toString(), turn.callId, turn.toolName, turn.argumentsJson),
                    ),
                )
            }
        }
    }
}
