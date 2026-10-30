package com.circaso.messaging.domain.support

import java.time.Duration

/** Messaging's settings. The limits are the spec's (Messaging → Endpoints, Data → Redis) and decisions'. */
data class MessagingConfig(
    /** How far a key upload's `X-Device-Timestamp` may be from the server clock: identity's window for its proofs. */
    val proofWindow: Duration = Duration.ofMinutes(5),
    val maxOneTimePreKeys: Int = 100,
    val bundlesPerHour: Int = 60,
    /** Decision 2026-10-25: the creator and everyone added count. */
    val maxGroupMembers: Int = 1_000,
    /** A group's sealed name and avatar, decoded (Messaging → Error codes, `messaging.group_invalid`). */
    val maxSealedMetaBytes: Int = 4 * 1_024,
    /** Decision 2026-10-25: 64 KB per message, decoded; media goes through M15. */
    val maxCiphertextBytes: Int = 64 * 1_024,
    /** How long a `clientMessageId` answers a retry with the first send's id (`client_messages.expires_at`). */
    val clientMessageTtl: Duration = Duration.ofDays(1),
    /** Decision 2026-10-25: typing is relayed only in chats of this many people or fewer. */
    val maxTypingMembers: Int = 50,
    /** `msg:typing:{chatId}:{userId}`: a repeated "typing" within this long is dropped. */
    val typingDedupe: Duration = Duration.ofSeconds(6),
    /** Decision 2026-10-25: group receipts go to the sender batched this often. */
    val receiptBatch: Duration = Duration.ofSeconds(5),
    /** Decision 2026-10-25: delete for everyone is open this long after sending. */
    val deleteWindow: Duration = Duration.ofHours(48),
    /** M13: messages per inbox page, and message ids per acknowledgement. */
    val inboxPageMax: Int = 100,
    /**
     * A `message.new` frame carries the ciphertext when it is at most this many base64 characters; a longer one is
     * fetched from the inbox (Messaging → Frame data; decision 2026-10-27). Set by
     * `MESSAGING_FRAME_CIPHERTEXT_MAX_CHARS`.
     */
    val frameCiphertextMaxChars: Int = 16 * 1_024,
    /** Decision 2026-10-30: an attachment is at most 32 MiB of ciphertext. */
    val maxAttachmentBytes: Long = 32L * 1_024 * 1_024,
    /** How long AT1's upload URL and AT3's download URL work. */
    val attachmentUrlTtl: Duration = Duration.ofMinutes(15),
    /** Decision 2026-10-30: an attachment is deleted 30 days after AT1, downloaded or not. */
    val attachmentRetention: Duration = Duration.ofDays(30),
    /** An attachment never completed (AT2) is deleted this long after AT1. */
    val pendingAttachmentTtl: Duration = Duration.ofDays(1),
    /** `messaging.purge-attachments`: rows read per round. */
    val attachmentPurgeBatch: Int = 500,
)
