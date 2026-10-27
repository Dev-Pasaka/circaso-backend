package com.circaso.messaging.presentation.controllers

import com.circaso.messaging.presentation.dto.AckRequest
import com.circaso.messaging.presentation.dto.AckResponse
import com.circaso.messaging.presentation.dto.InboxResponse
import com.circaso.messaging.presentation.dto.MessageDeletedResponse
import com.circaso.messaging.presentation.dto.MessagingSettingsRequest
import com.circaso.messaging.presentation.dto.MessagingSettingsResponse
import com.circaso.messaging.presentation.dto.SendChatMessageRequest
import com.circaso.messaging.presentation.dto.SentMessageResponse
import com.circaso.messaging.presentation.mappers.parseChatId
import com.circaso.messaging.presentation.mappers.parseInboxQuery
import com.circaso.messaging.presentation.mappers.parseMessageId
import com.circaso.messaging.presentation.mappers.parseMessageIds
import com.circaso.messaging.presentation.mappers.toCommand
import com.circaso.messaging.presentation.mappers.toResponse
import com.circaso.messaging.presentation.services.MessageService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Messages and delivery"
private const val SCOPE = "Scope: `app`."

/** Sending and delivery, M12 to M14, and the receipt and typing settings, M22. */
fun Route.messageController(messages: MessageService, pageMax: Int) {
    route("/chats/{chatId}/messages") {
        authenticate(Scope.APP) {
            messageRoutes(messages)
        }
    }
    route("/inbox") {
        authenticate(Scope.APP) {
            inboxRoutes(messages, pageMax)
        }
    }
    route("/messaging") {
        authenticate(Scope.APP) {
            settingsRoutes(messages)
        }
    }
}

private fun Route.messageRoutes(messages: MessageService) {
    post {
        val sender = call.userId()
        val request = call.receive<SendChatMessageRequest>()
        val result =
            parseChatId(call.parameters["chatId"])
                .flatMap { request.toCommand(sender, it) }
                .flatMap { messages.send(it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<SendChatMessageRequest, SentMessageResponse>(
        "M12 · Send one encrypted message",
        TAG,
        listOf(
            "messaging.chat_not_found",
            "messaging.message_too_large",
            "messaging.recipients_mismatch",
            "users.not_reachable",
            "request.malformed",
        ),
        notes =
            "$SCOPE `kind` is `MESSAGE`, `SENDER_KEY` or `CONTROL`. A direct message puts its ciphertext in " +
                "`recipients` (one entry, the other person); a group `MESSAGE` or `CONTROL` puts its one " +
                "ciphertext in `ciphertext`; a `SENDER_KEY` names the members it is for in `recipients`, each with " +
                "their own copy. Ciphertext is base64, at most 64 KB decoded. A direct send needs reach both ways " +
                "(`users.not_reachable`). A retry with the same `clientMessageId` (within 24 hours) returns the " +
                "first `messageId` and sends nothing. Recipients get a `messaging.message.new` frame; offline ones " +
                "get a data-only push if `pushable` (never for `SENDER_KEY` or `CONTROL`).",
    )

    delete("{messageId}") {
        val caller = call.userId()
        val result =
            parseChatId(call.parameters["chatId"]).flatMap { chatId ->
                parseMessageId(call.parameters["messageId"]).flatMap { messageId ->
                    messages.delete(
                        caller,
                        chatId,
                        messageId,
                    )
                }
            }
        call.respondDefault(result.toDefaultResponse { MessageDeletedResponse(deleted = true) })
    }.docs<NoBody, MessageDeletedResponse>(
        "M14 · Delete a message for everyone",
        TAG,
        listOf("messaging.chat_not_found", "messaging.delete_window_passed", "request.malformed"),
        notes =
            "$SCOPE The sender only, within 48 hours. Copies not yet delivered are dropped, and the other " +
                "members get a `messaging.message.deleted` frame so their phones remove it, delivered or not.",
    )
}

private fun Route.inboxRoutes(messages: MessageService, pageMax: Int) {
    get {
        val caller = call.userId()
        val result =
            call.inboxQuery(pageMax).flatMap { (after, limit) ->
                messages.inbox(
                    caller,
                    after,
                    limit,
                )
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, InboxResponse>(
        "M13 · Messages waiting for you, oldest first",
        TAG,
        listOf("request.malformed"),
        notes =
            "$SCOPE `?after=<messageId>&limit=100` (at most $pageMax). For catching up after the socket was " +
                "closed and after a push wake; acknowledge what you have with `POST /inbox/ack`, or `messaging.ack` " +
                "over the socket. `next` is the `after` of the next page, absent on the last. Messages are kept 30 " +
                "days at most.",
    )

    post("ack") {
        val caller = call.userId()
        val request = call.receive<AckRequest>()
        val result = parseMessageIds(request.messageIds, pageMax).flatMap { messages.ack(caller, it) }
        call.respondDefault(result.toDefaultResponse { AckResponse(it) })
    }.docs<AckRequest, AckResponse>(
        "M13 · Acknowledge messages you have",
        TAG,
        listOf("request.malformed"),
        notes =
            "$SCOPE 1 to $pageMax ids. Each message leaves your inbox and its sender gets a delivered receipt " +
                "(in a group, batched every 5 seconds). `acked` counts the ids that were still waiting; others are " +
                "skipped.",
    )
}

private fun Route.settingsRoutes(messages: MessageService) {
    patch("settings") {
        val caller = call.userId()
        val request = call.receive<MessagingSettingsRequest>()
        val result =
            messages.settings(
                caller,
                request.readReceipts,
                request.typing,
            )
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<MessagingSettingsRequest, MessagingSettingsResponse>(
        "M22 · Turn read receipts and typing indicators on or off",
        TAG,
        notes =
            "$SCOPE Both are on until turned off. A field left out stays as it is, so `{}` answers the current " +
                "settings. With read receipts off, nobody gets your `read` receipts; with typing off, nobody sees " +
                "you typing.",
    )
}

private fun ApplicationCall.inboxQuery(pageMax: Int) =
    parseInboxQuery(
        request.queryParameters["after"],
        request.queryParameters["limit"],
        pageMax,
    )
