package com.circaso.identity.presentation.agent

import com.circaso.identity.api.AgentSecurityResponse
import com.circaso.identity.presentation.mappers.toAgentResponse
import com.circaso.identity.presentation.mappers.toPrincipal
import com.circaso.identity.presentation.services.IdentityAgentService
import com.circaso.shared.Scope
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val AGENT = "Agent routes"

/**
 * Read-only route for the assistant (`/v1/agent/identity/…`, agent token only, decision 2026-10-31). It calls a
 * service method that reads and nothing else; the gateway also refuses anything but `GET` here.
 */
fun Route.agentController(security: IdentityAgentService) {
    route("/agent/identity") {
        authenticate(Scope.AGENT) {
            get("security") {
                val result = security.getSecurity(call.principal().toPrincipal())
                call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
            }.docs<NoBody, AgentSecurityResponse>(
                "The user's account and security overview: masked phone, sign-ins, this phone, recent activity",
                AGENT,
                notes =
                    "Scope: `agent` (read-only). The phone and emails are masked; no ids, keys or tokens. The " +
                        "latest 10 security log lines.",
            )
        }
    }
}
