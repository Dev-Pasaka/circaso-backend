package com.circaso.users.presentation.agent

import com.circaso.shared.Scope
import com.circaso.users.api.AgentContactsResponse
import com.circaso.users.presentation.mappers.toAgentContacts
import com.circaso.users.presentation.mappers.toAgentResponse
import com.circaso.users.presentation.services.ContactService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val AGENT = "Agent routes"

/**
 * Read-only routes for the assistant (`/v1/agent/users/…`, agent token only), in their own smaller shape. They call
 * service methods that read (`list…`, `find…`) and nothing else; the gateway also refuses anything but `GET` here.
 */
fun Route.agentController(contacts: ContactService) {
    route("/agent/users") {
        authenticate(Scope.AGENT) {
            route("/contacts") {
                get {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    call.respondDefault(
                        contacts
                            .listContacts(
                                call.userId(),
                                call.request.queryParameters["cursor"],
                                limit,
                            ).toDefaultResponse {
                                it.toAgentResponse()
                            },
                    )
                }.docs<NoBody, AgentContactsResponse>(
                    "The user's contacts, so the assistant can resolve a name to a person",
                    AGENT,
                    notes = "Scope: `agent` (read-only). Query: `cursor`, `limit`.",
                )

                get("search") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                    val result =
                        contacts.findContacts(
                            call.userId(),
                            call.request.queryParameters["q"].orEmpty(),
                            limit,
                        )
                    call.respondDefault(result.toDefaultResponse { it.toAgentContacts() })
                }.docs<NoBody, AgentContactsResponse>(
                    "Contacts whose name or @handle contains the text",
                    AGENT,
                    notes = "Scope: `agent` (read-only). Query: `q`, `limit`.",
                )
            }
        }
    }
}
