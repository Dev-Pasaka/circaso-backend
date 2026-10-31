package com.circaso.messaging.presentation.controllers

import com.circaso.messaging.presentation.dto.ChatPresenceResponse
import com.circaso.messaging.presentation.mappers.parseChatId
import com.circaso.messaging.presentation.mappers.toPresenceResponse
import com.circaso.messaging.presentation.services.PresenceService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** P1: the chat's other members' online state and last seen, for the app to read when a chat opens. */
fun Route.presenceController(presence: PresenceService) {
    route("/chats/{chatId}/presence") {
        authenticate(Scope.APP) {
            get {
                val caller = call.userId()
                val result = parseChatId(call.parameters["chatId"]).flatMap { presence.get(caller, it) }
                call.respondDefault(result.toDefaultResponse { it.toPresenceResponse() })
            }.docs<NoBody, ChatPresenceResponse>(
                "P1 · Who in a chat is online, and when the others were last seen",
                "Chats and members",
                listOf("messaging.chat_not_found", "request.malformed"),
                notes =
                    "Scope: `app`. One entry per other member, the same shape as the `presence.changed` socket " +
                        "frame: `online` is true while their app is in the foreground (a heartbeat within 75 " +
                        "seconds); `lastSeenAt` is the last moment they were active, null when never seen or not " +
                        "in 30 days. Empty in a chat of more than 50 people; anyone either of you blocked is left " +
                        "out. To hear changes, send `presence.watch` on the socket.",
            )
        }
    }
}
