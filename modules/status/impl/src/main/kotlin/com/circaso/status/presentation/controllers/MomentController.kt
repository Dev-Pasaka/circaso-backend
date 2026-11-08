package com.circaso.status.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.status.presentation.dto.DeletedResponse
import com.circaso.status.presentation.dto.KeyCopiesRequest
import com.circaso.status.presentation.dto.KeyCopiesResponse
import com.circaso.status.presentation.dto.KeyRequestRequest
import com.circaso.status.presentation.dto.KeyRequestResponse
import com.circaso.status.presentation.dto.MomentsResponse
import com.circaso.status.presentation.dto.PostMomentRequest
import com.circaso.status.presentation.dto.PostedMomentResponse
import com.circaso.status.presentation.dto.ViewMomentRequest
import com.circaso.status.presentation.dto.ViewedResponse
import com.circaso.status.presentation.dto.ViewsResponse
import com.circaso.status.presentation.mappers.parseMomentId
import com.circaso.status.presentation.mappers.parseMomentsQuery
import com.circaso.status.presentation.mappers.parseViewRequest
import com.circaso.status.presentation.mappers.toAnswered
import com.circaso.status.presentation.mappers.toCommand
import com.circaso.status.presentation.mappers.toCopies
import com.circaso.status.presentation.mappers.toPosters
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
private const val KEYS_INVALID = "status.keys_invalid"

/**
 * Moments, WhatsApp-style statuses that live 24 hours, end to end encrypted on the poster's phone (Moments API):
 * ST3 post, ST4 list what the caller may see, ST5 record a view, ST6 who viewed, ST7 delete. Frames
 * `status/status.posted`, `status.deleted` and `status.viewed`; the gateway pushes a reaction (`moment.loved`). A
 * new phone asks for keys (`keys/request`, `status.key_requested`) and the poster answers (`key-copies`,
 * `status.keys_ready`).
 */
fun Route.momentController(
    moments: MomentService,
    pageDefault: Int,
    pageMax: Int,
) {
    route("/moments") {
        authenticate(Scope.APP) {
            collectionRoutes(moments, pageDefault, pageMax)
            keyRoutes(moments)
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
                "your own (`mine`, with `viewCount` and `reactionCount`), by moment id, so oldest first within a " +
                "poster. `keyCopy` is your copy of the poster's sender key when one came with that moment; " +
                "`reaction` is your own emoji on someone else's. `next` is the `after` of the next page, null on " +
                "the last.",
    )
}

private fun Route.keyRoutes(moments: MomentService) {
    post("keys/request") {
        val caller = call.userId()
        val request = call.receive<KeyRequestRequest>()
        val result = request.toPosters().flatMap { moments.requestKeys(caller, it) }
        call.respondDefault(result.toDefaultResponse { asked -> KeyRequestResponse(asked.map { it.toString() }) })
    }.docs<KeyRequestRequest, KeyRequestResponse>(
        "ST9 · Ask posters for keys to their moments",
        TAG,
        listOf(KEYS_INVALID, MALFORMED),
        notes =
            "$SCOPE $IDEMPOTENT For moments this phone can't open (a new phone has a new Signal identity, so the " +
                "copies sent before were for the old one). `posterIds` 1 to 100, distinct, never yourself. Each " +
                "poster with a live moment shown to you gets `status/status.key_requested` with your id, at most " +
                "once every 10 minutes per poster; anyone else is skipped without a word. `requested` names the " +
                "posters asked. A poster without a socket also gets a silent push (`kind` `moment.keys_requested`, " +
                "`userId` the requester), so their phone answers without being opened; replay brings it otherwise.",
    )

    post("key-copies") {
        val caller = call.userId()
        val request = call.receive<KeyCopiesRequest>()
        val result =
            request.toCopies().flatMap { copies ->
                request.toAnswered().flatMap { answered -> moments.shareKeyCopies(caller, copies, answered) }
            }
        call.respondDefault(result.toDefaultResponse { KeyCopiesResponse(copied = it) })
    }.docs<KeyCopiesRequest, KeyCopiesResponse>(
        "ST10 · Send fresh key copies for your moments",
        TAG,
        listOf(KEYS_INVALID, MALFORMED),
        notes =
            "$SCOPE $IDEMPOTENT Your answer to `status.key_requested`: for each of your live moments, the body " +
                "sealed again over your 1:1 session with the person (standard base64, at most 72 KiB decoded). Each " +
                "replaces that person's `keyCopy` on that moment in ST4. Up to 200 copies, one per (moment, person), " +
                "never yourself. Copies for a moment that isn't yours or isn't live, or for someone not in its " +
                "audience, are skipped. `answered` (up to 100 people, optional) names whose request this answers: " +
                "they are told too when no copy is for them (moments posted from your earlier phone, which this one " +
                "can't seal again), as long as one of your live moments is shown to them. At least one copy or one " +
                "person. Everyone who got a copy or is answered is sent `status/status.keys_ready`. `copied` counts " +
                "the copies taken.",
    )
}

private fun Route.momentRoutes(moments: MomentService) {
    post("view") {
        val caller = call.userId()
        val body = call.receiveText()
        val result =
            parseMomentId(call.parameters["momentId"]).flatMap { momentId ->
                parseViewRequest(body).flatMap { moments.view(caller, momentId, it) }
            }
        call.respondDefault(result.toDefaultResponse { ViewedResponse(viewed = true) })
    }.docs<ViewMomentRequest, ViewedResponse>(
        "ST5 · Record that you viewed a moment",
        TAG,
        listOf(NOT_FOUND, "status.reaction_invalid", MALFORMED),
        notes =
            "$SCOPE $IDEMPOTENT The audience only. The first call records the view; later ones change your " +
                "reaction when they carry it (the body may be `{}`). `reaction` is one emoji (1 to 16 UTF-8 bytes, " +
                "no letters, digits or spaces; else `status.reaction_invalid`) and sets it, `\"reaction\": null` " +
                "clears it, no `reaction` leaves it. Older apps: `reacted: true` alone is `\"❤️\"`, `false` clears. " +
                "The poster gets `status/status.viewed` whenever something changed, and a push when you add or " +
                "change a reaction while they're offline.",
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
