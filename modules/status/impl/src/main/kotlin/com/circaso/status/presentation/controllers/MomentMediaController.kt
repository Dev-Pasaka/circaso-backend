package com.circaso.status.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.status.presentation.dto.MediaCompletedResponse
import com.circaso.status.presentation.dto.MediaDownloadResponse
import com.circaso.status.presentation.dto.MediaUploadResponse
import com.circaso.status.presentation.dto.PrepareMediaRequest
import com.circaso.status.presentation.mappers.parseMediaId
import com.circaso.status.presentation.mappers.toCommand
import com.circaso.status.presentation.mappers.toCompletedResponse
import com.circaso.status.presentation.mappers.toResponse
import com.circaso.status.presentation.services.MomentMediaService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Moments"
private const val SCOPE = "Scope: `app`."
private const val IDEMPOTENT = "Send an `Idempotency-Key`."
private const val NOT_FOUND = "status.media_not_found"
private const val INVALID = "status.media_invalid"
private const val MALFORMED = "request.malformed"

/**
 * A moment's photo, video or voice note, the same way as chat attachments (AT1-AT3): ST1 a pre-signed upload, ST2
 * complete it, ST8 a pre-signed download. The phone encrypts the file with a fresh AES-256-GCM key; the key, digest
 * and real type travel inside the moment's encrypted body, so the server only ever holds ciphertext and its size.
 */
fun Route.momentMediaController(media: MomentMediaService) {
    route("/moments/media") {
        authenticate(Scope.APP) {
            prepareRoute(media)
            route("/{mediaId}") {
                mediaRoutes(media)
            }
        }
    }
}

private fun Route.prepareRoute(media: MomentMediaService) {
    post {
        val caller = call.userId()
        val request = call.receive<PrepareMediaRequest>()
        val result = request.toCommand(caller).flatMap { media.prepare(it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<PrepareMediaRequest, MediaUploadResponse>(
        "ST1 · Start an encrypted moment upload",
        TAG,
        listOf(INVALID, MALFORMED),
        notes =
            "$SCOPE $IDEMPOTENT `kind` is `image`, `video` or `voice`; `sizeBytes` is the exact size of the " +
                "ciphertext, 1 byte to 32 MiB (`status.media_invalid` otherwise). PUT the ciphertext to `uploadUrl` " +
                "with exactly `uploadHeaders` before `expiresAt` (15 minutes), then ST2, then post it with ST3 " +
                "within an hour, or it is deleted.",
    )
}

private fun Route.mediaRoutes(media: MomentMediaService) {
    post("complete") {
        val caller = call.userId()
        val result = parseMediaId(call.parameters["mediaId"]).flatMap { media.complete(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toCompletedResponse() })
    }.docs<NoBody, MediaCompletedResponse>(
        "ST2 · Complete a moment upload",
        TAG,
        listOf(NOT_FOUND, INVALID, MALFORMED),
        notes =
            "$SCOPE $IDEMPOTENT The poster only. The object must be in storage at exactly ST1's `sizeBytes` " +
                "(`status.media_invalid` otherwise: PUT again while the link works, or start over). Completing twice " +
                "answers the same.",
    )

    get {
        val caller = call.userId()
        val result = parseMediaId(call.parameters["mediaId"]).flatMap { media.download(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, MediaDownloadResponse>(
        "ST8 · Download link for a moment's file",
        TAG,
        listOf(NOT_FOUND, MALFORMED),
        notes =
            "$SCOPE The poster, or anyone in the audience of the live moment carrying the file. `downloadUrl` works " +
                "for 15 minutes; ask again rather than storing it. Anything else, a pending or expired file " +
                "included, answers `status.media_not_found`.",
    )
}
