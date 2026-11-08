package com.circaso.messaging.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** AT1. `kind` is `image`, `voice`, `video` or `file`; `sizeBytes` is the ciphertext's exact size, 1 to 100 MiB. */
@Serializable
data class PrepareAttachmentRequest(val sizeBytes: Long, val kind: String)

/** The headers the PUT must carry exactly, or storage refuses the signature. */
@Serializable
data class UploadHeadersDto(
    @SerialName("Content-Type") val contentType: String,
)

@Serializable
data class AttachmentUploadResponse(
    val attachmentId: String,
    val uploadUrl: String,
    val uploadHeaders: UploadHeadersDto,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

@Serializable
data class AttachmentCompletedResponse(val attachmentId: String, val sizeBytes: Long)

@Serializable
data class AttachmentDownloadResponse(
    val downloadUrl: String,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
    val sizeBytes: Long,
)
