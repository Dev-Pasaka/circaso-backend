package com.circaso.users.presentation.controllers

import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.users.presentation.dto.ConnectRequest
import com.circaso.users.presentation.dto.ConnectResponse
import com.circaso.users.presentation.dto.ContactsResponse
import com.circaso.users.presentation.dto.InviteResponse
import com.circaso.users.presentation.dto.RemovedResponse
import com.circaso.users.presentation.dto.SyncRequest
import com.circaso.users.presentation.dto.SyncResponse
import com.circaso.users.presentation.mappers.parseUserId
import com.circaso.users.presentation.mappers.toCommand
import com.circaso.users.presentation.mappers.toDto
import com.circaso.users.presentation.mappers.toResponse
import com.circaso.users.presentation.mappers.toVia
import com.circaso.users.presentation.services.ContactService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private const val TAG = "Contacts"
private const val CONNECTIONS = "Connections and invites"
private const val SCOPE = "Scope: `app`."

/** Contacts, connections and invites. U7, U8, U11, U12 and U16. */
fun Route.contactController(contacts: ContactService) {
    authenticate(Scope.APP) {
        route("/contacts") {
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                call.respondDefault(contacts.listContacts(call.userId(), call.request.queryParameters["cursor"], limit).toDefaultResponse { it.toResponse() })
            }.docs<NoBody, ContactsResponse>("U8 · Your contacts with public profiles", TAG, notes = "$SCOPE Query: `cursor`, `limit` (default 50, at most 200).")

            post("sync") {
                val result = call.receive<SyncRequest>().toCommand(call.userId()).flatMap { contacts.sync(it) }
                call.respondDefault(result.toDefaultResponse { matches -> SyncResponse(matches.map { it.toDto() }) })
            }.docs<SyncRequest, SyncResponse>(
                "U7 · Upload a batch of phone-book numbers (up to 1,000) and get back the ones on Circaso", TAG, listOf("users.sync_too_large", "request.rate_limited"),
                notes = "$SCOPE 5 syncs per user per hour. Names from the phone book never leave the phone.",
            )

            delete("{userId}") {
                val target = parseUserId(call.parameters["userId"])
                val result: Resource<Unit> =
                    if (target == null) Resource.error(SharedError.Malformed(mapOf("userId" to "is not valid"))) else contacts.remove(call.userId(), target)
                call.respondDefault(result.map { RemovedResponse(true) }.toDefaultResponse { it })
            }.docs<NoBody, RemovedResponse>("U16 · Remove a contact; a sync won't add them back", TAG, listOf("users.contact_not_found"), notes = SCOPE)
        }

        route("/connections") {
            post {
                val body = call.receive<ConnectRequest>()
                val result = body.toVia().flatMap { contacts.connect(call.userId(), it, body.value) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<ConnectRequest, ConnectResponse>(
                "U11 · Connect through a @handle, QR code or invite link", CONNECTIONS,
                listOf("users.handle_not_found", "users.invite_invalid", "users.not_reachable"),
                notes = "$SCOPE `via` is `handle`, `qr` or `invite`. A QR code or invite connects both ways; a typed @handle only one way.",
            )
        }

        route("/invites") {
            post {
                call.respondDefault(contacts.createInvite(call.userId()).toDefaultResponse(HttpStatusCode.Created) { it.toResponse() })
            }.docs<NoBody, InviteResponse>("U12 · A shareable link and QR payload, valid 7 days", CONNECTIONS, notes = SCOPE)

            delete("{token}") {
                call.respondDefault(contacts.revokeInvite(call.userId(), call.parameters["token"].orEmpty()).toDefaultResponse { it })
            }.docs<NoBody, NoBody>("U12 · Revoke an invite you made", CONNECTIONS, listOf("users.invite_invalid"), notes = SCOPE)
        }
    }
}
