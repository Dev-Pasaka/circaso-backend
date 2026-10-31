package com.circaso.assistant.presentation.controllers

import com.circaso.assistant.presentation.dto.ChatResponse
import com.circaso.assistant.presentation.dto.ConversationDto
import com.circaso.assistant.presentation.dto.ConversationsResponse
import com.circaso.assistant.presentation.dto.CreateConversationRequest
import com.circaso.assistant.presentation.dto.RenameConversationRequest
import com.circaso.assistant.presentation.mappers.parseUuid
import com.circaso.assistant.presentation.mappers.toDto
import com.circaso.assistant.presentation.mappers.toResponse
import com.circaso.assistant.presentation.services.ConversationService
import com.circaso.shared.CircasoJson
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
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

private const val TAG = "Asha chat"
private const val SCOPE = "Scope: `app` (the user token)."
private const val PAGING = "Query: `cursor`, `limit` (default 50, at most 100)."
private const val NOT_FOUND = "assistant.conversation_not_found"

/**
 * The user's conversations with Asha (decision 2026-10-31): C1 list, C2 start, C3 rename, C4 delete, C5 read one.
 * Asking in one is A2 with its `conversationId`.
 */
fun Route.conversationController(conversations: ConversationService) {
    authenticate(Scope.APP) {
        route("/assistant/conversations") {
            get {
                val result = conversations.list(call.userId(), call.cursor(), call.limit())
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, ConversationsResponse>(
                "C1 · Your conversations with Asha, newest first",
                TAG,
                listOf("request.malformed"),
                notes =
                    "$SCOPE $PAGING Newest first by `lastMessageAt` (an empty conversation counts from its " +
                        "creation).",
            )

            post {
                val title = call.createRequest()?.title
                call.respondDefault(conversations.create(call.userId(), title).toDefaultResponse { it.toDto() })
            }.docs<CreateConversationRequest, ConversationDto>(
                "C2 · Start a new conversation with Asha",
                TAG,
                listOf("request.malformed"),
                notes =
                    "$SCOPE Send an `Idempotency-Key`. The body may be empty or `{}`; without a `title` the " +
                        "conversation is called \"New chat\" until its first question titles it. It becomes the " +
                        "most recent, so A2 without a `conversationId` goes to it too.",
            )

            route("/{conversationId}") {
                patch {
                    val result =
                        call.conversationId().flatMap { id ->
                            val title = call.receive<RenameConversationRequest>().title
                            conversations.rename(call.userId(), id, title)
                        }
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<RenameConversationRequest, ConversationDto>(
                    "C3 · Rename a conversation",
                    TAG,
                    listOf(NOT_FOUND, "request.malformed"),
                    notes = "$SCOPE Send an `Idempotency-Key`. `title`: 1 to 80 characters; it is never replaced.",
                )

                delete {
                    val result =
                        call.conversationId().flatMap { id ->
                            conversations.delete(call.userId(), id, call.callId)
                        }
                    call.respondDefault(result.toDefaultResponse { it })
                }.docs<NoBody, NoBody>(
                    "C4 · Delete a conversation and every message in it",
                    TAG,
                    listOf(NOT_FOUND, "request.malformed"),
                    notes = "$SCOPE Send an `Idempotency-Key`. Asha keeps no copy of what was said in it.",
                )

                get("messages") {
                    val result =
                        call.conversationId().flatMap { id ->
                            conversations.messages(call.userId(), id, call.cursor(), call.limit())
                        }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<NoBody, ChatResponse>(
                    "C5 · One conversation's messages, newest first",
                    TAG,
                    listOf(NOT_FOUND, "request.malformed"),
                    notes = "$SCOPE $PAGING",
                )
            }
        }
    }
}

private fun ApplicationCall.conversationId(): Resource<UUID> =
    parseUuid(parameters["conversationId"])?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed("conversationId", "is not valid"))

/** C2's body is optional: an empty one is a conversation without a title. */
private suspend fun ApplicationCall.createRequest(): CreateConversationRequest? =
    receiveText()
        .takeIf { it.isNotBlank() }
        ?.let { CircasoJson.decodeFromString(CreateConversationRequest.serializer(), it) }

private fun ApplicationCall.cursor(): String? = request.queryParameters["cursor"]

private fun ApplicationCall.limit(): Int = request.queryParameters["limit"]?.toIntOrNull() ?: 50
