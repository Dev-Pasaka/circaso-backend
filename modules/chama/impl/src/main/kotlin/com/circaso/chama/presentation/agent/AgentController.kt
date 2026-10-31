package com.circaso.chama.presentation.agent

import com.circaso.chama.api.AgentChamaResponse
import com.circaso.chama.api.AgentChamasResponse
import com.circaso.chama.presentation.mappers.chatIdOf
import com.circaso.chama.presentation.mappers.toAgentResponse
import com.circaso.chama.presentation.services.ChamaAgentService
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

private const val AGENT = "Agent routes"
private const val SCOPE = "Scope: `agent` (read-only), the caller's own chamas only."

/**
 * Read-only routes for the assistant (`/v1/agent/chama/…`, agent token only, decision 2026-10-31). They call
 * service methods that read and nothing else; the gateway also refuses anything but `GET` here.
 */
fun Route.agentController(chamas: ChamaAgentService) {
    route("/agent/chama") {
        authenticate(Scope.AGENT) {
            get {
                val result = chamas.listChamas(call.userId())
                call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
            }.docs<NoBody, AgentChamasResponse>(
                "The user's chamas: each group with a goal, its progress and open requests",
                AGENT,
                notes = "$SCOPE At most 20.",
            )

            get("{chatId}") {
                val result = chatIdOf(call.parameters["chatId"]).flatMap { chamas.getChama(call.userId(), it) }
                call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
            }.docs<NoBody, AgentChamaResponse>(
                "One of the user's chamas: who put in what, open requests and the latest ledger lines",
                AGENT,
                listOf("chama.not_a_member", "chama.no_goal", "request.malformed"),
                notes = SCOPE,
            )
        }
    }
}
