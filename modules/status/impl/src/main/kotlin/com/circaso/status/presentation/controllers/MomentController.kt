package com.circaso.status.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.status.presentation.dto.DeletedResponse
import com.circaso.status.presentation.dto.MomentsResponse
import com.circaso.status.presentation.dto.PostMomentRequest
import com.circaso.status.presentation.dto.PostedMomentResponse
import com.circaso.status.presentation.dto.ViewMomentRequest
import com.circaso.status.presentation.dto.ViewedResponse
import com.circaso.status.presentation.dto.ViewsResponse
import com.circaso.status.presentation.mappers.parseMomentId
import com.circaso.status.presentation.mappers.parseMomentsQuery
import com.circaso.status.presentation.mappers.parseViewRequest
import com.circaso.status.presentation.mappers.toCommand
import com.circaso.status.presentation.mappers.toResponse
import com.circaso.status.presentation.services.MomentService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Moments"
private const val SCOPE = "Scope: `app`."
private const val IDEMPOTENT = "Send an `Idempotency-Key`."
private const val NOT_FOUND = "status.moment_not_found"
private const val NOT_POSTER = "status.not_poster"
private const val MALFORMED = "request.malformed"

/**
 * Moments, WhatsApp-style statuses that live 24 hours, end to end encrypted on the poster's phone (Moments API):
 * ST3 post, ST4 list what the caller may see, ST5 record a view, ST6 who viewed, ST7 delete. Frames
 * `status/status.posted`, `status.deleted` and `status.viewed`; no pushes.
 */
fun Route.momentController(
    moments: MomentService,
    pageDefault: Int,
    pageMax: Int,
) {
    route("/moments") {
        authenticate(Scope.APP) {
            collectionRoutes(moments, pageDefault, pageMax)
            route("/{momentId}") {
                momentRoutes(moments)
            }
        }
    }
}

private fun Route.collectionRoutes(
    moments: MomentService,
    pageDefault: Int,
    pageMax: Int,
) {
    post {
        val caller = call.userId()
        val request = call.receive<PostMomentRequest>()
        val result = request.toCommand(caller).flatMap { moments.post(it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<PostMomentRequest, PostedMomentResponse>(
        "ST3 · Post a moment",
        TAG,
        listOf(
            "status.audience_invalid",
            "status.moment_too_large",
            "status.media_invalid",
            "status.media_not_found",
            "status.too_many_moments",
            MALFORMED,
        ),
        notes =
            "$SCOPE $IDEMPOTENT `clientMomentId` (a UUID made on the phone) makes a retry return the first answer. " +
                "`ciphertext` is the body encrypted once under your moments sender key (base64, at most 64 KiB " +
                "decoded). `audience` is 1 to 1,024 people, never yourself; anyone who can't reach you, or whom you " +
                "can't reach, is left out without a word. `keyCopies` only for people in `audience`. At most one " +
                "completed file in `mediaIds`. At most 50 moments in 24 hours (`status.too_many_moments`, with " +
                "`Retry-After`). The audience gets `status/status.posted`.",
    )

    get {
        val caller = call.userId()
        val result =
            parseMomentsQuery(
                call.request.queryParameters["after"],
                call.request.queryParameters["limit"],
                pageDefault,
                pageMax,
            ).flatMap { (after, limit) -> moments.list(caller, after, limit) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, MomentsResponse>(
        "ST4 · The moments you may see, and your own",
        TAG,
        listOf(MALFORMED),
        notes =
            "$SCOPE `?after=<momentId>&limit=$pageDefault` (at most $pageMax). Every live moment shown to you, plus " +
                "your own (`mine`, with `viewCount`), by moment id, so oldest first within a poster. `keyCopy` is " +
                "your copy of the poster's sender key when one came with that moment. `next` is the `after` of the " +
                "next page, null on the last.",
    )
}

private fun Route.momentRoutes(moments: MomentService) {
    post("view") {
        val caller = call.userId()
        val body = call.receiveText()
        val result =
            parseMomentId(call.parameters["momentId"]).flatMap { momentId ->
                parseViewRequest(body).flatMap { moments.view(caller, momentId, it.reacted) }
            }
        call.respondDefault(result.toDefaultResponse { ViewedResponse(viewed = true) })
    }.docs<ViewMomentRequest, ViewedResponse>(
        "ST5 · Record that you viewed a moment",
        TAG,
        listOf(NOT_FOUND, MALFORMED),
        notes =
            "$SCOPE $IDEMPOTENT The audience only. The first call records the view; later ones change `reacted` " +
                "when they carry it (the body may be `{}`). The poster gets `status/status.viewed` whenever " +
                "something changed.",
    )

    get("views") {
        val caller = call.userId()
        val result = parseMomentId(call.parameters["momentId"]).flatMap { moments.views(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, ViewsResponse>(
        "ST6 · Who viewed your moment",
        TAG,
        listOf(NOT_FOUND, NOT_POSTER, MALFORMED),
        notes = "$SCOPE The poster only, in the order people viewed it.",
    )

    delete {
        val caller = call.userId()
        val result = parseMomentId(call.parameters["momentId"]).flatMap { moments.delete(caller, it) }
        call.respondDefault(result.toDefaultResponse { DeletedResponse(deleted = true) })
    }.docs<NoBody, DeletedResponse>(
        "ST7 · Delete your moment",
        TAG,
        listOf(NOT_FOUND, NOT_POSTER, MALFORMED),
        notes =
            "$SCOPE $IDEMPOTENT The poster only. The moment, its views and its file go; the audience gets " +
                "`status/status.deleted`.",
    )
}
