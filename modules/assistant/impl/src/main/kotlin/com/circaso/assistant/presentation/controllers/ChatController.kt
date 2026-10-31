package com.circaso.assistant.presentation.controllers

import com.circaso.assistant.domain.models.ToolStatus
import com.circaso.assistant.presentation.dto.ChatResponse
import com.circaso.assistant.presentation.dto.DoneEvent
import com.circaso.assistant.presentation.dto.SendMessageRequest
import com.circaso.assistant.presentation.dto.ToolResultsRequest
import com.circaso.assistant.presentation.mappers.parseUuid
import com.circaso.assistant.presentation.mappers.toCommand
import com.circaso.assistant.presentation.mappers.toResponse
import com.circaso.assistant.presentation.services.ChatService
import com.circaso.assistant.presentation.sse.streamReply
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.io.Writer

private const val TAG = "Asha chat"
private const val SCOPE = "Scope: `app` (the user token)."
private const val AGENT_TOKEN_HEADER = "X-Agent-Token"

/**
 * The Asha chat: asking, the phone's answers to device tools, reading and deleting the history. A1, A2, A5, A6 and A9.
 * A1 and A5 cover every conversation; one conversation is C1 to C5 (ConversationController).
 */
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
                notes =
                    "$SCOPE Query: `cursor`, `limit` (default 50, at most 100). Every conversation's messages, " +
                        "each with its `conversationId`; one conversation's are C5.",
            )

            delete {
                call.respondDefault(chat.deleteAll(call.userId(), call.callId).toDefaultResponse { it })
            }.docs<NoBody, NoBody>(
                "A5 · Delete the whole history with Asha",
                TAG,
                notes = "$SCOPE Every conversation and every message.",
            )

            route("/messages") {
                post {
                    val command =
                        call.receive<SendMessageRequest>().toCommand(
                            call.userId(),
                            call.request.header(AGENT_TOKEN_HEADER),
                            call.callId,
                        )
                    call.streamReply { listener -> command.flatMap { chat.ask(it, listener) } }
                }.docs<SendMessageRequest, NoBody>(
                    "A2 · Ask Asha; the reply streams as server-sent events",
                    TAG,
                    listOf(
                        "assistant.quota_exceeded",
                        "assistant.model_unavailable",
                        "assistant.conversation_not_found",
                        "auth.token_invalid",
                        "request.malformed",
                    ),
                    notes =
                        "$SCOPE `conversationId` (C1, C2) picks the conversation; without it the turn goes to the " +
                            "user's most recent one, or a new one, and Asha reads only that conversation as " +
                            "context. `done` carries `conversationId` and the `conversation` (titled after its " +
                            "first exchange). " +
                            "Send the user's own agent token in `X-Agent-Token` (another user's is refused) " +
                            "so Asha's tools can read the user's own data, and `currency` (ISO 4217) for amounts " +
                            "in the user's money. `tool_status` `{turnId, callId, tool, label}` and `tool_done` " +
                            "`{turnId, callId, tool, ok}` events bracket each tool call. Replies are markdown with " +
                            "```chart, ```statement and ```diagram blocks (docs: Asha → Rich replies). " +
                            "The response is `text/event-stream`, not the envelope (errors before any text are " +
                            "the envelope): `message` events carry `{delta}`, each piece as the model writes it; " +
                            "a `tool_request` event `{turnId, callId, name, arguments}` asks the phone to run a " +
                            "device tool and answer with A9; `done` carries the saved reply; an `error` event " +
                            "`{code, message}` means the model failed after text had gone out (nothing is saved).",
                )

                route("/{messageId}") {
                    delete {
                        val id = parseUuid(call.parameters["messageId"])
                        val result: Resource<Unit> =
                            if (id ==
                                null
                            ) {
                                Resource.error(SharedError.Malformed("messageId", "is not valid"))
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
                        call.streamReply { listener -> command.flatMap { chat.answerDevice(it, listener) } }
                    }.docs<ToolResultsRequest, NoBody>(
                        "A9 · The phone's answer to a device tool; the reply then continues as server-sent events",
                        TAG,
                        listOf(
                            "assistant.turn_not_found",
                            "assistant.model_unavailable",
                            "auth.token_invalid",
                            "request.malformed",
                        ),
                        notes =
                            "$SCOPE `messageId` is the `turnId` from the `tool_request` event. At most 30 short " +
                                "snippets; they are used for this turn and never stored. The response is a new " +
                                "`text/event-stream` carrying on the same reply (errors before any text are the " +
                                "envelope): `message` events `{delta}` as the model writes, another `tool_request` " +
                                "if Asha needs the phone again, `done` with the saved reply (including what was " +
                                "said before the pause), or an `error` event `{code, message}` if the model fails " +
                                "after text has gone out (nothing is saved).",
                    )
                }
            }
        }
    }
}
