package com.circaso.messaging.presentation.controllers

import com.circaso.messaging.presentation.dto.AttachmentCompletedResponse
import com.circaso.messaging.presentation.dto.AttachmentDownloadResponse
import com.circaso.messaging.presentation.dto.AttachmentUploadResponse
import com.circaso.messaging.presentation.dto.PrepareAttachmentRequest
import com.circaso.messaging.presentation.mappers.parseAttachmentId
import com.circaso.messaging.presentation.mappers.parseChatId
import com.circaso.messaging.presentation.mappers.toCommand
import com.circaso.messaging.presentation.mappers.toCompletedResponse
import com.circaso.messaging.presentation.mappers.toResponse
import com.circaso.messaging.presentation.services.AttachmentService
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Chat media"
private const val SCOPE = "Scope: `app`."
private const val NOT_FOUND = "messaging.attachment_not_found"
private const val MALFORMED = "request.malformed"

/**
 * Encrypted chat attachments, Signal-style (decision 2026-10-30): AT1 a pre-signed upload, AT2 complete it, AT3 a
 * pre-signed download. The phone encrypts each file with its own AES-256-GCM key; the key and digest travel inside
 * the E2EE message (M12), so the server only ever holds ciphertext and its size.
 */
fun Route.attachmentController(attachments: AttachmentService) {
    route("/chats/{chatId}/attachments") {
        authenticate(Scope.APP) {
            prepareRoute(attachments)
        }
    }
    route("/attachments/{attachmentId}") {
        authenticate(Scope.APP) {
            attachmentRoutes(attachments)
        }
    }
}

private fun Route.prepareRoute(attachments: AttachmentService) {
    post {
        val caller = call.userId()
        val request = call.receive<PrepareAttachmentRequest>()
        val result =
            parseChatId(call.parameters["chatId"])
                .flatMap { request.toCommand(caller, it) }
                .flatMap { attachments.prepare(it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<PrepareAttachmentRequest, AttachmentUploadResponse>(
        "AT1 · Start an encrypted attachment upload",
        TAG,
        listOf("messaging.chat_not_found", "messaging.attachment_too_large", MALFORMED),
        notes =
            "$SCOPE Members of the chat only. `kind` is `image`, `voice`, `video` or `file`; `sizeBytes` is the " +
                "exact size of the ciphertext, 1 byte to 100 MiB. PUT the ciphertext to `uploadUrl` with exactly " +
                "`uploadHeaders` (one PUT, streamed from a file) starting before `expiresAt` (an hour), " +
                "then call AT2. The file is deleted 30 days " +
                "after this call, or after a day if never completed.",
    )
}

private fun Route.attachmentRoutes(attachments: AttachmentService) {
    post("complete") {
        val caller = call.userId()
        val result = parseAttachmentId(call.parameters["attachmentId"]).flatMap { attachments.complete(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toCompletedResponse() })
    }.docs<NoBody, AttachmentCompletedResponse>(
        "AT2 · Complete an attachment upload",
        TAG,
        listOf(NOT_FOUND, "messaging.attachment_incomplete", MALFORMED),
        notes =
            "$SCOPE The uploader only. The object must be in storage at exactly AT1's `sizeBytes` " +
                "(`messaging.attachment_incomplete` otherwise: upload again). Completing twice answers the same. " +
                "Then send the message that carries the attachment's id, key and digest (M12).",
    )

    get {
        val caller = call.userId()
        val result = parseAttachmentId(call.parameters["attachmentId"]).flatMap { attachments.download(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, AttachmentDownloadResponse>(
        "AT3 · Download link for an attachment",
        TAG,
        listOf(NOT_FOUND, MALFORMED),
        notes =
            "$SCOPE Anyone currently in the attachment's chat, once it is complete. `downloadUrl` works for " +
                "15 minutes; fetch a new one rather than storing it. A pending or expired attachment, or one in " +
                "a chat you are not in, answers `messaging.attachment_not_found`.",
    )
}
