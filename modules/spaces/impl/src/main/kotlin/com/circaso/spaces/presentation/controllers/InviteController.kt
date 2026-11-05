package com.circaso.spaces.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.spaces.presentation.dto.CancelledResponse
import com.circaso.spaces.presentation.dto.DeclinedResponse
import com.circaso.spaces.presentation.dto.InvitePeopleRequest
import com.circaso.spaces.presentation.dto.InvitePeopleResponse
import com.circaso.spaces.presentation.dto.InvitesResponse
import com.circaso.spaces.presentation.dto.MeDto
import com.circaso.spaces.presentation.dto.MyInvitesResponse
import com.circaso.spaces.presentation.dto.RequestAnswerResponse
import com.circaso.spaces.presentation.dto.SpaceDto
import com.circaso.spaces.presentation.mappers.parseSpaceId
import com.circaso.spaces.presentation.mappers.parseUserId
import com.circaso.spaces.presentation.mappers.parseUuid
import com.circaso.spaces.presentation.mappers.toDto
import com.circaso.spaces.presentation.mappers.toResponse
import com.circaso.spaces.presentation.services.InviteService
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.SharedError
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Requests to join and invites (Spaces API, SP14-SP22). */
fun Route.inviteController(invites: InviteService) {
    route("/spaces") {
        authenticate(Scope.APP) {
            mineRoutes(invites)
            route("/{spaceId}") { spaceInviteRoutes(invites) }
        }
    }
}

private fun Route.mineRoutes(invites: InviteService) {
    get("invites/mine") {
        val result = invites.mine(call.userId())
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, MyInvitesResponse>(
        "SP20 · Your invites",
        TAG,
        notes = "$SCOPE The invite cards at the top of Chats (SI2), newest first, live ones only.",
    )

    post("invites/{inviteId}/accept") {
        val result = parseUuid(call.parameters["inviteId"], "inviteId").flatMap { invites.accept(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NoBody, SpaceDto>(
        "SP21 · Accept an invite",
        TAG,
        listOf("spaces.invite_not_found", "spaces.banned", MALFORMED),
        notes = "$SCOPE $IDEMPOTENT You join as a member; the inviter gets `space.invite_accepted`.",
    )

    post("invites/{inviteId}/decline") {
        val result = parseUuid(call.parameters["inviteId"], "inviteId").flatMap { invites.decline(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { DeclinedResponse(true) })
    }.docs<NoBody, DeclinedResponse>(
        "SP22 · Decline an invite",
        TAG,
        listOf("spaces.invite_not_found", MALFORMED),
        notes = "$SCOPE $IDEMPOTENT Nobody is told. Show Undo for a few seconds before sending this.",
    )
}

private fun Route.spaceInviteRoutes(invites: InviteService) {
    post("requests") {
        val result = parseSpaceId(call.parameters["spaceId"]).flatMap { invites.request(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NoBody, MeDto>(
        "SP14 · Ask to join an invite-only space",
        TAG,
        listOf("spaces.request_too_soon", "spaces.banned", "spaces.already_member", "spaces.not_invite_only", NOT_FOUND),
        notes = "$SCOPE $IDEMPOTENT A live invite is accepted instead. After a decline, 30 days before asking again.",
    )

    delete("requests/mine") {
        val result = parseSpaceId(call.parameters["spaceId"]).flatMap { invites.cancelRequest(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NoBody, MeDto>("SP15 · Cancel your request", TAG, listOf("spaces.request_not_found", NOT_FOUND))

    get("invites") {
        val result = parseSpaceId(call.parameters["spaceId"]).flatMap { invites.list(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, InvitesResponse>(
        "SP16 · Requests and invites (admins)",
        TAG,
        listOf("spaces.not_admin", NOT_FOUND),
        notes = "$SCOPE Waiting requests, oldest first; invites from the last 90 days, newest first. A declined invite shows `INVITED` until it expires.",
    )

    post("requests/{userId}/accept") {
        val result = answer(call.parameters["spaceId"], call.parameters["userId"]) { space, user -> invites.answer(call.userId(), space, user, accept = true) }
        call.respondDefault(result.toDefaultResponse { RequestAnswerResponse(it.name) })
    }.docs<NoBody, RequestAnswerResponse>("SP17 · Accept a request (admins)", TAG, listOf("spaces.not_admin", "spaces.request_not_found", MALFORMED))

    post("requests/{userId}/decline") {
        val result = answer(call.parameters["spaceId"], call.parameters["userId"]) { space, user -> invites.answer(call.userId(), space, user, accept = false) }
        call.respondDefault(result.toDefaultResponse { RequestAnswerResponse(it.name) })
    }.docs<NoBody, RequestAnswerResponse>("SP17 · Decline a request (admins)", TAG, listOf("spaces.not_admin", "spaces.request_not_found", MALFORMED))

    post("invites") {
        val request = call.receive<InvitePeopleRequest>()
        val result =
            parseSpaceId(call.parameters["spaceId"]).flatMap { space ->
                val ids = request.userIds.map { UserId.parse(it) ?: return@flatMap Resource.error(SharedError.Malformed("userIds", "is not an id")) }
                invites.invite(call.userId(), space, ids)
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<InvitePeopleRequest, InvitePeopleResponse>(
        "SP18 · Invite people (admins)",
        TAG,
        listOf("spaces.not_admin", "spaces.invite_invalid", NOT_FOUND, MALFORMED),
        notes = "$SCOPE $IDEMPOTENT 1 to 50 people; each invite lives 14 days. `outcome` per person.",
    )

    delete("invites/{inviteId}") {
        val result =
            parseSpaceId(call.parameters["spaceId"]).flatMap { space ->
                parseUuid(call.parameters["inviteId"], "inviteId").flatMap { invites.cancelInvite(call.userId(), space, it) }
            }
        call.respondDefault(result.toDefaultResponse { CancelledResponse(true) })
    }.docs<NoBody, CancelledResponse>("SP19 · Cancel an invite (admins)", TAG, listOf("spaces.not_admin", "spaces.invite_not_found", MALFORMED))
}

private suspend fun <T> answer(
    space: String?,
    user: String?,
    block: suspend (com.circaso.spaces.api.SpaceId, UserId) -> Resource<T>,
): Resource<T> = parseSpaceId(space).flatMap { s -> parseUserId(user).flatMap { u -> block(s, u) } }
