package com.circaso.messaging.presentation.agent

import com.circaso.messaging.api.AgentChatsResponse
import com.circaso.messaging.presentation.mappers.agentChatLimit
import com.circaso.messaging.presentation.mappers.toAgentResponse
import com.circaso.messaging.presentation.services.MessagingAgentService
import com.circaso.shared.Scope
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val AGENT = "Agent routes"

/**
 * Read-only route for the assistant (`/v1/agent/messaging/…`, agent token only, decision 2026-10-31). It calls a
 * service method that reads and nothing else; the gateway also refuses anything but `GET` here.
 */
fun Route.agentController(chats: MessagingAgentService) {
    route("/agent/messaging") {
        authenticate(Scope.AGENT) {
            get("chats") {
                val caller = call.userId()
                val limit = agentChatLimit(call.request.queryParameters["limit"])
                call.respondDefault(chats.listChats(caller).toDefaultResponse { it.toAgentResponse(caller, limit) })
            }.docs<NoBody, AgentChatsResponse>(
                "The user's chats: who is in each and when it was last active",
                AGENT,
                notes =
                    "Scope: `agent` (read-only). Query: `limit` (default 30, at most 100). Group names and messages " +
                        "are end-to-end encrypted and never included; Asha reads them on the phone.",
            )
        }
    }
}
