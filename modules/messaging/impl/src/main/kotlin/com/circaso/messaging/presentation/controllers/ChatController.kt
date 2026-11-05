package com.circaso.messaging.presentation.controllers

import com.circaso.messaging.presentation.dto.AddMembersRequest
import com.circaso.messaging.presentation.dto.ChatDto
import com.circaso.messaging.presentation.dto.ChatsResponse
import com.circaso.messaging.presentation.dto.CreateGroupRequest
import com.circaso.messaging.presentation.dto.MemberRemovedResponse
import com.circaso.messaging.presentation.dto.OpenDirectChatRequest
import com.circaso.messaging.presentation.dto.SetRoleRequest
import com.circaso.messaging.presentation.dto.UpdateGroupRequest
import com.circaso.messaging.presentation.mappers.parseChatId
import com.circaso.messaging.presentation.mappers.parseRole
import com.circaso.messaging.presentation.mappers.parseUserId
import com.circaso.messaging.presentation.mappers.parseUserIds
import com.circaso.messaging.presentation.mappers.toCommand
import com.circaso.messaging.presentation.mappers.toDto
import com.circaso.messaging.presentation.services.ChatService
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.Uuid7
import com.circaso.shared.flatMap
import com.circaso.shared.map
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val TAG = "Chats and members"
private const val SCOPE = "Scope: `app`."
private const val SEALED =
    "`sealedMeta` is the group's name and avatar sealed on the phone under the group metadata key, base64, at most " +
        "4 KB once decoded; the server never reads it."
private const val NOT_ADDED = "Anyone you can't reach is left out and listed in `notAdded`, with no reason."

/** Chats and membership, M4 to M11. */
fun Route.chatController(chats: ChatService) {
    route("/chats") {
        authenticate(Scope.APP) {
            get {
                val result = chats.list(call.userId())
                call.respondDefault(result.toDefaultResponse { list -> ChatsResponse(list.map { it.toDto() }) })
            }.docs<NoBody, ChatsResponse>(
                "M6 · Every chat you are in, with members' public profiles and the last message id",
                TAG,
                notes = "$SCOPE The app sorts the list and counts unread itself.",
            )

            post("direct") {
                val caller = call.userId()
                val request = call.receive<OpenDirectChatRequest>()
                val via = request.viaSpaceId?.let { Uuid7.parse(it.trim()) }
                val result =
                    if (request.viaSpaceId != null && via == null) {
                        Resource.error(SharedError.Malformed("viaSpaceId", "is not an id"))
                    } else {
                        parseUserId(request.userId).flatMap { chats.openDirect(caller, it, via) }
                    }
                call.respondDefault(result.toDefaultResponse { it.toDto() })
            }.docs<OpenDirectChatRequest, ChatDto>(
                "M4 · Open the direct chat with a person, or get the one you already have",
                TAG,
                listOf("users.not_reachable", "request.malformed"),
                notes =
                    "$SCOPE You must be able to reach the person. `created` is true on the first open; two " +
                        "people opening it at the same moment get the same chat. With `viaSpaceId` (Spaces phase " +
                        "3): the private chat with that space's owner, separate from any other chat between you, " +
                        "allowed by the space's rule instead of reach (blocks still apply); it carries `viaSpaceId`.",
            )

            post("groups") {
                val caller = call.userId()
                val key = call.request.headers["Idempotency-Key"]
                val result = call.receive<CreateGroupRequest>().toCommand(caller, key).flatMap { chats.createGroup(it) }
                call.respondDefault(result.toDefaultResponse { it.toDto() })
            }.docs<CreateGroupRequest, ChatDto>(
                "M5 · Create a group with its sealed name and avatar and its first members",
                TAG,
                listOf("messaging.group_invalid", "messaging.group_full", "request.malformed"),
                notes =
                    "$SCOPE $SEALED You become the owner. $NOT_ADDED At most 1,000 people, you included. Every " +
                        "member gets one `member.added` frame listing everyone added in `userIds`. The group's id " +
                        "comes from the `Idempotency-Key`: if a create fails part-way, retry with the same key and " +
                        "it carries on with the same group, unless the group changed in the meantime: then it is " +
                        "returned as it is.",
            )

            route("/{chatId}") {
                chatRoutes(chats)
                route("/members") {
                    memberRoutes(chats)
                }
            }
        }
    }
}

private fun Route.chatRoutes(chats: ChatService) {
    get {
        val caller = call.userId()
        val result = parseChatId(call.parameters["chatId"]).flatMap { chats.get(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NoBody, ChatDto>(
        "M7 · One chat with its members and their roles",
        TAG,
        listOf("messaging.chat_not_found"),
        notes = "$SCOPE A chat you're not in answers as if it didn't exist.",
    )

    patch {
        val caller = call.userId()
        val request = call.receive<UpdateGroupRequest>()
        val result = parseChatId(call.parameters["chatId"]).flatMap { chats.update(request.toCommand(caller, it)) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<UpdateGroupRequest, ChatDto>(
        "M8 · Change a group's sealed name and avatar, or who may add members",
        TAG,
        listOf(
            "messaging.chat_not_found",
            "messaging.not_admin",
            "messaging.group_invalid",
            "messaging.version_conflict",
            "request.malformed",
        ),
        notes =
            "$SCOPE Admins only. Send the `version` you last read; if the group changed since (members " +
                "included), the change is refused with `messaging.version_conflict`: load it again and retry. " +
                "$SEALED A new seal raises `metaVersion`. Members get a `chat.updated` frame.",
    )
}

private fun Route.memberRoutes(chats: ChatService) {
    post {
        val caller = call.userId()
        val request = call.receive<AddMembersRequest>()
        val result =
            parseChatId(call.parameters["chatId"]).flatMap { chatId ->
                parseUserIds(request.memberIds).flatMap { ids ->
                    chats.addMembers(
                        caller,
                        chatId,
                        ids,
                    )
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<AddMembersRequest, ChatDto>(
        "M9 · Add people to a group",
        TAG,
        listOf("messaging.chat_not_found", "messaging.not_admin", "messaging.group_full", "request.malformed"),
        notes =
            "$SCOPE Admins add people, or any member when `settings.membersCanAdd` is on. $NOT_ADDED People " +
                "already in the group are skipped. A group holds at most 1,000 people. Every member gets one " +
                "`member.added` frame listing everyone added in `userIds`.",
    )

    delete("{userId}") {
        val caller = call.userId()
        val result =
            call.chatAndUser().flatMap { (chatId, target) ->
                chats.removeMember(
                    caller,
                    chatId,
                    target,
                )
            }
        call.respondDefault(result.toDefaultResponse { MemberRemovedResponse(removed = true) })
    }.docs<NoBody, MemberRemovedResponse>(
        "M10 · Remove someone from a group, or leave it (your own user id)",
        TAG,
        listOf(
            "messaging.chat_not_found",
            "messaging.not_admin",
            "messaging.not_a_group",
            "request.malformed",
        ),
        notes =
            "$SCOPE Admins remove admins and members; nobody removes the owner. With your own user id you " +
                "leave. A direct chat can't be left (`messaging.not_a_group`). When the owner leaves, " +
                "ownership passes to the longest-serving admin, or else member, and members get `chat.updated`. " +
                "The last member leaving deletes the group. Everyone who was in " +
                "it, the person included, gets a `member.removed` frame and rotates their sender keys.",
    )

    put("{userId}/role") {
        val caller = call.userId()
        val role = parseRole(call.receive<SetRoleRequest>().role)
        val result =
            call.chatAndUser().flatMap { (chatId, target) ->
                role.flatMap {
                    chats.setRole(
                        caller,
                        chatId,
                        target,
                        it,
                    )
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<SetRoleRequest, ChatDto>(
        "M11 · Make someone an admin or a member, or hand over ownership",
        TAG,
        listOf("messaging.chat_not_found", "messaging.not_admin", "request.malformed"),
        notes =
            "$SCOPE Admins set `ADMIN` or `MEMBER` on anyone but the owner. Only the owner sets `OWNER`, and " +
                "then becomes an admin. A change moves the chat's `version` on and members get `chat.updated`.",
    )
}

private fun ApplicationCall.chatAndUser() =
    parseChatId(parameters["chatId"]).flatMap { chatId ->
        parseUserId(parameters["userId"]).map { target -> chatId to target }
    }
