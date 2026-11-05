package com.circaso.spaces.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.spaces.domain.models.NotificationLevel
import com.circaso.spaces.presentation.dto.CreateSpaceRequest
import com.circaso.spaces.presentation.dto.DeletedResponse
import com.circaso.spaces.presentation.dto.HandleAvailabilityResponse
import com.circaso.spaces.presentation.dto.LeftResponse
import com.circaso.spaces.presentation.dto.MeDto
import com.circaso.spaces.presentation.dto.MembershipPaymentResponse
import com.circaso.spaces.presentation.dto.MySpacesResponse
import com.circaso.spaces.presentation.dto.NotificationsRequest
import com.circaso.spaces.presentation.dto.SearchResponse
import com.circaso.spaces.presentation.dto.SpaceDto
import com.circaso.spaces.presentation.dto.SuggestionsResponse
import com.circaso.spaces.presentation.dto.UpdateSpaceRequest
import com.circaso.spaces.presentation.dto.UpdateSpaceResponse
import com.circaso.spaces.presentation.mappers.parseEnum
import com.circaso.spaces.presentation.mappers.parsePage
import com.circaso.spaces.presentation.mappers.parseSpaceId
import com.circaso.spaces.presentation.mappers.parseTopic
import com.circaso.spaces.presentation.mappers.toCommand
import com.circaso.spaces.presentation.mappers.toDto
import com.circaso.spaces.presentation.mappers.toResponse
import com.circaso.spaces.presentation.mappers.toSuggestions
import com.circaso.spaces.presentation.services.MembershipService
import com.circaso.spaces.presentation.services.SpaceService
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

internal const val TAG = "Spaces"
internal const val SCOPE = "Scope: `app`."
internal const val IDEMPOTENT = "Send an `Idempotency-Key`."
internal const val MALFORMED = "request.malformed"
internal const val NOT_FOUND = "spaces.space_not_found"
internal const val UNAVAILABLE = "spaces.space_unavailable"

/** The request's `Idempotency-Key`; the gateway refuses a state change without one before it gets here. */
internal fun ApplicationCall.idempotencyKey(): String = request.headers["Idempotency-Key"].orEmpty()

/**
 * Spaces themselves (Spaces API, SP1-SP9) and joining them (SP10-SP13). Space chats are not end-to-end encrypted;
 * frames go out on module `spaces`.
 */
fun Route.spaceController(
    spaces: SpaceService,
    membership: MembershipService,
    pageDefault: Int,
    pageMax: Int,
    suggestedDefault: Int,
) {
    route("/spaces") {
        authenticate(Scope.APP) {
            listRoutes(spaces, pageDefault, pageMax, suggestedDefault)
            route("/{spaceId}") {
                spaceRoutes(spaces)
                joinRoutes(membership)
            }
        }
    }
}

private fun Route.listRoutes(
    spaces: SpaceService,
    pageDefault: Int,
    pageMax: Int,
    suggestedDefault: Int,
) {
    get("handles/{handle}/availability") {
        val result = spaces.checkHandle(call.parameters["handle"].orEmpty())
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, HandleAvailabilityResponse>(
        "SP1 · Is a space handle free",
        TAG,
        listOf("spaces.handle_invalid"),
        notes = "$SCOPE Without `@`, any case. `suggestions`: three free handles when it's taken or not valid (SN1b).",
    )

    post {
        val caller = call.userId()
        val country = call.request.headers["CF-IPCountry"]
        val result =
            call
                .receive<CreateSpaceRequest>()
                .toCommand(
                    caller,
                    country,
                    call.idempotencyKey(),
                ).flatMap { spaces.create(it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<CreateSpaceRequest, SpaceDto>(
        "SP2 · Create a space",
        TAG,
        listOf(
            "spaces.account_too_new",
            "spaces.too_many_spaces",
            "spaces.account_restricted",
            "spaces.handle_invalid",
            "spaces.handle_taken",
            "spaces.details_invalid",
            "spaces.price_invalid",
            "spaces.media_not_found",
            "spaces.team_invalid",
            MALFORMED,
        ),
        notes =
            "$SCOPE $IDEMPOTENT The space is `SETTING_UP` (seen by you and its team only) until its wallet is set up " +
                "with W18 and turns active; then it is `LIVE`. The same key returns the same space " +
                "(Try Again, SN7b). " +
                "Accounts younger than 7 days can't create one; at most 3 in 24 hours.",
    )

    get("mine") {
        val result =
            parsePage(
                call.request.queryParameters["cursor"],
                call.request.queryParameters["limit"],
                pageDefault,
                pageMax,
            ).flatMap { (offset, limit) -> spaces.mine(call.userId(), offset, limit) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, MySpacesResponse>(
        "SP7 · Your spaces",
        TAG,
        listOf(MALFORMED),
        notes = "$SCOPE Newest activity first; ended memberships and your own spaces being set up included (SD1).",
    )

    get("suggested") {
        val query = call.request.queryParameters
        val result =
            parseTopic(query["topic"]).flatMap { topic ->
                parsePage(query["cursor"], query["limit"], suggestedDefault, pageMax).flatMap { (offset, limit) ->
                    spaces.suggested(call.userId(), call.request.headers["CF-IPCountry"], topic, offset, limit)
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toSuggestions() })
    }.docs<NoBody, SuggestionsResponse>(
        "SP8 · Suggested spaces",
        TAG,
        listOf(MALFORMED),
        notes =
            "$SCOPE Spaces your contacts are in, then popular in your country, then popular. Never " +
                "invite-only ones.",
    )

    get("search") {
        val query = call.request.queryParameters
        val result =
            parseTopic(query["topic"]).flatMap { topic ->
                parsePage(query["cursor"], query["limit"], pageDefault, pageMax).flatMap { (offset, limit) ->
                    spaces.search(query["q"].orEmpty(), topic, offset, limit)
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, SearchResponse>(
        "SP9 · Search spaces",
        TAG,
        listOf("spaces.details_invalid", MALFORMED),
        notes =
            "$SCOPE `q` 1 to 60 characters: words match name, handle, about and topic. Starting with `@`, handles " +
                "only: `exact` on top, `spaces` the similar ones. Invite-only spaces never show.",
    )

    get("by-handle/{handle}") {
        val result = spaces.byHandle(call.userId(), call.parameters["handle"].orEmpty())
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NoBody, SpaceDto>(
        "SP4 · A space by its handle",
        TAG,
        listOf(NOT_FOUND, UNAVAILABLE),
        notes = "$SCOPE For links and QR codes (`circaso.app/s/{handle}`); the same answer as SP3.",
    )
}

private fun Route.spaceRoutes(spaces: SpaceService) {
    get {
        val result = parseSpaceId(call.parameters["spaceId"]).flatMap { spaces.get(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NoBody, SpaceDto>(
        "SP3 · A space",
        TAG,
        listOf(NOT_FOUND, UNAVAILABLE, MALFORMED),
        notes =
            "$SCOPE A live space for anyone (an invite-only one from outside: no `about`), one being set up for its " +
                "owner and team only. `wallet` for the owner and admins. `me` is where you stand.",
    )

    patch {
        val caller = call.userId()
        val request = call.receive<UpdateSpaceRequest>()
        val result =
            parseSpaceId(call.parameters["spaceId"]).flatMap { id ->
                request.toCommand(caller, id).flatMap { spaces.update(it) }
            }
        call.respondDefault(result.toDefaultResponse { UpdateSpaceResponse(it.view.toDto(), it.notified) })
    }.docs<UpdateSpaceRequest, UpdateSpaceResponse>(
        "SP5 · Change a space",
        TAG,
        listOf(
            "spaces.not_admin",
            "spaces.not_owner",
            "spaces.handle_invalid",
            "spaces.handle_taken",
            "spaces.details_invalid",
            "spaces.price_invalid",
            "spaces.media_not_found",
            NOT_FOUND,
            MALFORMED,
        ),
        notes =
            "$SCOPE $IDEMPOTENT Fields left out stay. Admins: name, about, topic, photoMediaId, coverMediaId " +
                "(`removePhoto` / `removeCover` take them away), postingMode. Owner only: handle, visibility, " +
                "price, privateMessages. A price change " +
                "applies from each member's next period and is told to every member now (`notified`).",
    )

    delete {
        val result = parseSpaceId(call.parameters["spaceId"]).flatMap { spaces.delete(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { DeletedResponse(true) })
    }.docs<NoBody, DeletedResponse>(
        "SP6 · Delete a space",
        TAG,
        listOf("spaces.not_owner", "spaces.wallet_not_empty", NOT_FOUND, MALFORMED),
        notes = "$SCOPE $IDEMPOTENT The owner, once the space wallet is empty.",
    )
}

private fun Route.joinRoutes(membership: MembershipService) {
    post("follow") {
        val result = parseSpaceId(call.parameters["spaceId"]).flatMap { membership.follow(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NoBody, MeDto>(
        "SP10 · Follow a public space",
        TAG,
        listOf(
            "spaces.banned",
            "spaces.payment_required",
            "spaces.invite_required",
            "spaces.account_restricted",
            NOT_FOUND,
        ),
        notes = "$SCOPE $IDEMPOTENT You're a member at once. Members-only: SP13; invite-only: SP14.",
    )

    delete("membership") {
        val result = parseSpaceId(call.parameters["spaceId"]).flatMap { membership.leave(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { LeftResponse(true) })
    }.docs<NoBody, LeftResponse>(
        "SP11 · Unfollow or leave a space",
        TAG,
        listOf("spaces.owner_cannot_leave", "spaces.not_member", NOT_FOUND),
        notes = "$SCOPE $IDEMPOTENT A paid period ends now and isn't refunded.",
    )

    put("notifications") {
        val request = call.receive<NotificationsRequest>()
        val result =
            parseSpaceId(call.parameters["spaceId"]).flatMap { id ->
                parseEnum(request.level, "level", NotificationLevel.entries.toTypedArray()).flatMap {
                    membership.notifications(call.userId(), id, it)
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NotificationsRequest, MeDto>(
        "SP12 · Which posts notify you",
        TAG,
        listOf("spaces.not_member", NOT_FOUND, MALFORMED),
        notes = "$SCOPE $IDEMPOTENT `ALL`, `MENTIONS` (replies to you and posts by the team) or `OFF`.",
    )

    post("membership/payment") {
        val result = parseSpaceId(call.parameters["spaceId"]).flatMap { membership.payment(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, MembershipPaymentResponse>(
        "SP13 · What joining or renewing a members-only space costs",
        TAG,
        listOf(
            "spaces.not_paid_space",
            "spaces.already_member",
            "spaces.banned",
            "spaces.wallet_not_ready",
            "spaces.account_restricted",
            NOT_FOUND,
        ),
        notes =
            "$SCOPE $IDEMPOTENT Held 15 minutes. Pay it with W4: `to: { spaceId }`, `spacePaymentId`, exactly " +
                "`amount`, from your personal wallet. You're a member once the transfer is confirmed " +
                "(`space.membership_updated`), never from the tap. The fee is on top (W17).",
    )
}
