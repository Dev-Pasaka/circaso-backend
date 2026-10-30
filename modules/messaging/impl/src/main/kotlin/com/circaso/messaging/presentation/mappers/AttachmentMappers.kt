package com.circaso.messaging.presentation.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentDownload
import com.circaso.messaging.domain.models.AttachmentKind
import com.circaso.messaging.domain.models.AttachmentUpload
import com.circaso.messaging.domain.models.PrepareAttachmentCommand
import com.circaso.messaging.presentation.dto.AttachmentCompletedResponse
import com.circaso.messaging.presentation.dto.AttachmentDownloadResponse
import com.circaso.messaging.presentation.dto.AttachmentUploadResponse
import com.circaso.messaging.presentation.dto.PrepareAttachmentRequest
import com.circaso.messaging.presentation.dto.UploadHeadersDto
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import java.util.UUID

// ---- request → command: the kind's spelling is a shape check; the size limit is the use case's ----

fun parseAttachmentId(raw: String?): Resource<UUID> =
    raw?.let(Uuid7::parse)?.let { Resource.success(it) }
        ?: Resource.error(SharedError.Malformed("attachmentId", "is not valid"))

fun PrepareAttachmentRequest.toCommand(caller: UserId, chatId: ChatId): Resource<PrepareAttachmentCommand> {
    val parsed =
        AttachmentKind.entries.firstOrNull { it.name.lowercase() == kind }
            ?: return Resource.error(SharedError.Malformed("kind", "must be image, voice, video or file"))
    return Resource.success(PrepareAttachmentCommand(caller, chatId, sizeBytes, parsed))
}

// ---- model → response ----

fun AttachmentUpload.toResponse() =
    AttachmentUploadResponse(
        attachmentId = attachmentId.toString(),
        uploadUrl = uploadUrl,
        uploadHeaders = UploadHeadersDto(contentType),
        expiresAt = expiresAt,
    )

fun Attachment.toCompletedResponse() = AttachmentCompletedResponse(attachmentId.toString(), sizeBytes)

fun AttachmentDownload.toResponse() = AttachmentDownloadResponse(downloadUrl, expiresAt, sizeBytes)
