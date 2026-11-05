package com.circaso.status.presentation.agent

import com.circaso.shared.Scope
import com.circaso.status.api.AgentMomentsResponse
import com.circaso.status.presentation.mappers.toAgentResponse
import com.circaso.status.presentation.services.StatusAgentService
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
 * Read-only routes for the assistant (`/v1/agent/status/…`, agent token only, decision 2026-11-05). They call service
 * methods that read and nothing else; the gateway also refuses anything but `GET` here.
 */
fun Route.agentController(reads: StatusAgentService) {
    route("/agent/status") {
        authenticate(Scope.AGENT) {
            get("moments") {
                val result = reads.listOwnMoments(call.userId())
                call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
            }.docs<NoBody, AgentMomentsResponse>(
                "The user's own live moments: when each was posted, and how many viewed and reacted",
                AGENT,
                notes =
                    "Scope: `agent` (read-only), the caller's own moments only. Never the sealed text or media, " +
                        "and never who viewed. At most 50, newest first.",
            )
        }
    }
}
