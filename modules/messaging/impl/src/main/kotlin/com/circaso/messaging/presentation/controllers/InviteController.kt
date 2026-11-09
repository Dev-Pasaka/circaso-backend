package com.circaso.messaging.presentation.controllers

import com.circaso.messaging.presentation.dto.ChatDto
import com.circaso.messaging.presentation.dto.ChatInvitesResponse
import com.circaso.messaging.presentation.dto.InviteCancelledResponse
import com.circaso.messaging.presentation.dto.InviteDeclinedResponse
import com.circaso.messaging.presentation.dto.InvitesResponse
import com.circaso.messaging.presentation.mappers.parseChatId
import com.circaso.messaging.presentation.mappers.parseUserId
import com.circaso.messaging.presentation.mappers.toDto
import com.circaso.messaging.presentation.services.InviteService
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.Uuid7
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

/** Invites are how people come into a group, so they sit with the members in the docs. */
private const val TAG = "Chats and members"
private const val SCOPE = "Scope: `app`."

/**
 * Group invites, M23 to M27 (decision 2026-11-09): adding someone to a group (M5, M9) invites them, and they join
 * only when they accept. `/chats/invites` is a fixed segment, so Ktor picks it before `/chats/{chatId}`.
 */
fun Route.inviteController(invites: InviteService) {
    route("/chats") {
        authenticate(Scope.APP) {
            route("/invites") {
                get {
                    val result = invites.mine(call.userId())
                    call.respondDefault(result.toDefaultResponse { list -> InvitesResponse(list.map { it.toDto() }) })
                }.docs<NoBody, InvitesResponse>(
                    "M23 · My open invites to groups, newest first",
                    TAG,
                    notes =
                        "$SCOPE Each with the group's sealed name and photo as they now are, its member count and " +
                            "who invited you; never its members or messages. `sealedKey` is the group's metadata " +
                            "key sealed 1:1 for you by the inviter's phone (open it as a `SENDER_KEY` copy from " +
                            "`invitedBy`), null when none was sent. Invites run out after 30 days.",
                )

                post("{inviteId}/accept") {
                    val caller = call.userId()
                    val result = call.inviteId().flatMap { invites.accept(caller, it) }
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<NoBody, ChatDto>(
                    "M24 · Accept an invite and join the group",
                    TAG,
                    listOf("messaging.invite_not_found", "messaging.group_full", "request.malformed"),
                    notes =
                        "$SCOPE You become a member, and every member gets `member.added` with `joined: true`: " +
                            "their phones send you their sender keys and the group's metadata key, as for any new " +
                            "member. A retry after it went through answers the group again. A full group answers " +
                            "`messaging.group_full` and the invite stays open.",
                )

                post("{inviteId}/decline") {
                    val caller = call.userId()
                    val result = call.inviteId().flatMap { invites.decline(caller, it) }
                    call.respondDefault(result.toDefaultResponse { InviteDeclinedResponse(declined = true) })
                }.docs<NoBody, InviteDeclinedResponse>(
                    "M25 · Decline an invite",
                    TAG,
                    listOf("messaging.invite_not_found", "request.malformed"),
                    notes =
                        "$SCOPE The invite is closed and nobody is told; the group's admins and the inviter get " +
                            "only `invites.changed`. Being invited again later makes a new invite.",
                )
            }

            route("/{chatId}/invites") {
                get {
                    val caller = call.userId()
                    val result = parseChatId(call.parameters["chatId"]).flatMap { invites.ofChat(caller, it) }
                    call.respondDefault(
                        result.toDefaultResponse { list -> ChatInvitesResponse(list.map { it.toDto() }) },
                    )
                }.docs<NoBody, ChatInvitesResponse>(
                    "M26 · A group's open invites, for group info's \"Invited\" list",
                    TAG,
                    listOf("messaging.chat_not_found", "request.malformed"),
                    notes = "$SCOPE Members only, oldest first. Declined, cancelled and run-out invites aren't listed.",
                )

                delete("{userId}") {
                    val caller = call.userId()
                    val result =
                        parseChatId(call.parameters["chatId"]).flatMap { chatId ->
                            parseUserId(call.parameters["userId"]).flatMap { invites.cancel(caller, chatId, it) }
                        }
                    call.respondDefault(result.toDefaultResponse { InviteCancelledResponse(cancelled = true) })
                }.docs<NoBody, InviteCancelledResponse>(
                    "M27 · Cancel someone's open invite",
                    TAG,
                    listOf(
                        "messaging.chat_not_found",
                        "messaging.invite_not_found",
                        "messaging.not_admin",
                        "request.malformed",
                    ),
                    notes =
                        "$SCOPE Admins, or whoever sent the invite. The person gets `invite.removed` and it leaves " +
                            "their inbox.",
                )
            }
        }
    }
}

private fun ApplicationCall.inviteId(): Resource<UUID> =
    Uuid7.parse(parameters["inviteId"].orEmpty())?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed("inviteId", "is not an id"))
