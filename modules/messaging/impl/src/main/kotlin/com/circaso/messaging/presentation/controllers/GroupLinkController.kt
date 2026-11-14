package com.circaso.messaging.presentation.controllers

import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.presentation.dto.ChatDto
import com.circaso.messaging.presentation.dto.GroupLinkDto
import com.circaso.messaging.presentation.dto.GroupLinkOffResponse
import com.circaso.messaging.presentation.dto.GroupLinkPreviewResponse
import com.circaso.messaging.presentation.dto.GroupLinkResponse
import com.circaso.messaging.presentation.dto.PublishGroupLinkRequest
import com.circaso.messaging.presentation.mappers.parseChatId
import com.circaso.messaging.presentation.mappers.parseLinkToken
import com.circaso.messaging.presentation.mappers.toCommand
import com.circaso.messaging.presentation.mappers.toDto
import com.circaso.messaging.presentation.mappers.toResponse
import com.circaso.messaging.presentation.services.GroupLinkService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Join links are how people come into a group, so they sit with the members in the docs. */
private const val TAG = "Chats and members"
private const val SCOPE = "Scope: `app`."
private const val IDEMPOTENT = "Send an `Idempotency-Key`."
private const val LINK_NOT_FOUND = "messaging.link_not_found"
private const val CHAT_NOT_FOUND = "messaging.chat_not_found"
private const val NOT_ADMIN = "messaging.not_admin"
private const val NOT_A_GROUP = "messaging.not_a_group"
private const val RATE_LIMITED = "messaging.rate_limited"

/**
 * Group join links, M28 to M32 (decision 2026-11-14): an admin makes a link, shares it anywhere, and whoever opens it
 * can join the group without being added. `/chats/links` is a fixed segment, so Ktor picks it before
 * `/chats/{chatId}`. The link's public page (`<SHARE_BASE_URL>/s/{token}`) is share's.
 */
fun Route.groupLinkController(links: GroupLinkService, config: MessagingConfig) {
    route("/chats") {
        authenticate(Scope.APP) {
            route("/links/{token}") { joinRoutes(links, config) }
            route("/{chatId}/link") { adminRoutes(links, config) }
        }
    }
}

private fun Route.joinRoutes(links: GroupLinkService, config: MessagingConfig) {
    get {
        val caller = call.userId()
        val result = parseLinkToken(call.parameters["token"]).flatMap { links.lookUp(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse(config) })
    }.docs<NoBody, GroupLinkPreviewResponse>(
        "M31 · A join link's preview, before joining",
        TAG,
        listOf(LINK_NOT_FOUND, RATE_LIMITED),
        notes =
            "$SCOPE For a link opened on a phone with Circaso (`<share host>/s/{token}` or `circaso://s/{token}`; " +
                "a join link's token is 22 characters, a share code 12). The name, description and photo an admin " +
                "published for the link, and the member count; never members or messages. `member` with `chatId` " +
                "when you are already in the group: open the chat. 60 an hour. Unknown, reset and turned-off links " +
                "all answer `messaging.link_not_found`.",
    )

    post("join") {
        val caller = call.userId()
        val result = parseLinkToken(call.parameters["token"]).flatMap { links.join(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NoBody, ChatDto>(
        "M32 · Join a group with its link",
        TAG,
        listOf(LINK_NOT_FOUND, "messaging.link_not_allowed", "messaging.group_full", RATE_LIMITED),
        notes =
            "$SCOPE $IDEMPOTENT You become a member at once (role `MEMBER`), and every member gets `member.added` " +
                "with `joined: true` and `viaLink: true`: phones say you joined with the group link and send you " +
                "their sender keys and the group's metadata key, as for any new member. Already in the group, or a " +
                "retry: the group again, nothing written. `messaging.link_not_allowed` when an admin removed you " +
                "from this group, a block stands between you and one of its admins, or yours is a support " +
                "account (it never says which; an admin can still add you). 20 an hour.",
    )
}

private fun Route.adminRoutes(links: GroupLinkService, config: MessagingConfig) {
    get {
        val caller = call.userId()
        val result = parseChatId(call.parameters["chatId"]).flatMap { links.get(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse(config) })
    }.docs<NoBody, GroupLinkResponse>(
        "M28 · A group's join link",
        TAG,
        listOf(CHAT_NOT_FOUND, NOT_ADMIN, NOT_A_GROUP, "request.malformed"),
        notes = "$SCOPE Admins only. `link` is null when the group has none (never made, or turned off).",
    )

    put {
        val caller = call.userId()
        val request = call.receive<PublishGroupLinkRequest>()
        val result =
            parseChatId(call.parameters["chatId"])
                .flatMap { request.toCommand(caller, it) }
                .flatMap { links.publish(it) }
        call.respondDefault(result.toDefaultResponse { it.toDto(config) })
    }.docs<PublishGroupLinkRequest, GroupLinkDto>(
        "M29 · Make, republish or reset a group's join link",
        TAG,
        listOf(CHAT_NOT_FOUND, NOT_ADMIN, NOT_A_GROUP, "messaging.link_invalid", "request.malformed"),
        notes =
            "$SCOPE $IDEMPOTENT Admins only. The server can't read the group's sealed name, so the admin's phone " +
                "publishes what the link's page shows, for this link only: `name` (up to 100 characters), " +
                "`description` (up to 500) and `photo` (base64 of a JPEG or PNG, at most 64 KB; the app sends " +
                "256 px). Anyone with the link sees them, with the member count; nothing else of the group is ever " +
                "published. With no link, this makes one. With one, it replaces the published copy and keeps the " +
                "link (send it again whenever the name, description or photo change). `reset: true` makes a new " +
                "link and the old one stops working at once. The group's admins get `link.changed`.",
    )

    delete {
        val caller = call.userId()
        val result = parseChatId(call.parameters["chatId"]).flatMap { links.turnOff(caller, it) }
        call.respondDefault(result.toDefaultResponse { GroupLinkOffResponse(off = true) })
    }.docs<NoBody, GroupLinkOffResponse>(
        "M30 · Turn off a group's join link",
        TAG,
        listOf(CHAT_NOT_FOUND, NOT_ADMIN, NOT_A_GROUP, "request.malformed"),
        notes =
            "$SCOPE $IDEMPOTENT Admins only. The link stops working at once and the published name, description " +
                "and photo are deleted. People who already joined stay. With no link, it answers the same.",
    )
}
