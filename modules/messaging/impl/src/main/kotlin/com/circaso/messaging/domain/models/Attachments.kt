package com.circaso.messaging.domain.models

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * What an attachment is, as the phone says it. The server never opens the blob (it is ciphertext); the kind is kept
 * only so the retention and limits can differ per kind later.
 */
enum class AttachmentKind { IMAGE, VOICE, VIDEO, FILE }

/** `PENDING` from AT1 until AT2 finds the object in storage at the size promised, then `READY`. */
enum class AttachmentState { PENDING, READY }

/** One encrypted file in a chat (decision 2026-10-30): the AES key and digest travel only inside the E2EE message. */
data class Attachment(
    val attachmentId: UUID,
    val chatId: ChatId,
    val uploaderId: UserId,
    val kind: AttachmentKind,
    val sizeBytes: Long,
    val state: AttachmentState,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class PrepareAttachmentCommand(
    val caller: UserId,
    val chatId: ChatId,
    val sizeBytes: Long,
    val kind: AttachmentKind,
)

/** AT1's answer: where to PUT the ciphertext, with `Content-Type: [contentType]`, before [expiresAt]. */
data class AttachmentUpload(
    val attachmentId: UUID,
    val uploadUrl: String,
    val contentType: String,
    val expiresAt: Instant,
)

/** AT3's answer: a short-lived link to the ciphertext. Never stored. */
data class AttachmentDownload(val downloadUrl: String, val expiresAt: Instant, val sizeBytes: Long)

/** Every blob is ciphertext, so storage only ever sees this type; the real type is inside the message. */
const val ATTACHMENT_CONTENT_TYPE = "application/octet-stream"
