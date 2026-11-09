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
    /**
     * Presence: a person is online while their phone's last `active` is younger than this. The phone repeats it every
     * 30 seconds in the foreground, so a killed app goes offline by itself.
     */
    val presenceTtl: Duration = Duration.ofSeconds(75),
    /** How long one `presence.watch` subscribes; the phone repeats it every 45 seconds while the chat is open. */
    val presenceWatch: Duration = Duration.ofSeconds(60),
    /** Presence is shared only in chats of this many people or fewer, like typing. */
    val maxPresenceMembers: Int = 50,
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
    /**
     * Decision 2026-11-07 (was 32 MiB, 2026-10-30): an attachment is at most 100 MiB of ciphertext, so people can share
     * their videos and documents as they are, untrimmed. Set by `MESSAGING_MAX_ATTACHMENT_BYTES`. Group photos keep
     * their own [maxGroupPhotoBytes]; moments keep the status module's limit.
     */
    val maxAttachmentBytes: Long = MAX_ATTACHMENT_BYTES,
    /**
     * How long AT1's upload URL works. Storage checks it when the PUT starts, and the phone retries a dropped upload
     * with the same URL, so a slow 100 MiB upload and its retries need longer than a download link.
     */
    val attachmentUploadUrlTtl: Duration = Duration.ofHours(1),
    /** How long AT3's download URL works (the phone asks for a fresh one for each download). */
    val attachmentUrlTtl: Duration = Duration.ofMinutes(15),
    /** Decision 2026-10-30: an attachment is deleted 30 days after AT1, downloaded or not. */
    val attachmentRetention: Duration = Duration.ofDays(30),
    /** An attachment never completed (AT2) is deleted this long after AT1. */
    val pendingAttachmentTtl: Duration = Duration.ofDays(1),
    /** `messaging.purge-attachments`: rows read per round. */
    val attachmentPurgeBatch: Int = 500,
    /**
     * Decision 2026-11-05: a group photo is at most this much ciphertext. The app sends a 1,024 px JPEG (a few hundred
     * KB), so this leaves room without letting a group keep a video's worth forever.
     */
    val maxGroupPhotoBytes: Long = 2L * 1_024 * 1_024,
    /**
     * Decision 2026-11-05: a replaced or cleared group photo is deleted this long after, so a member who started
     * fetching it a moment earlier still gets it.
     */
    val groupPhotoGrace: Duration = Duration.ofHours(24),
    /** Decision 2026-11-09: an invite to a group not answered in this long runs out. */
    val inviteTtl: Duration = Duration.ofDays(30),
    /**
     * The inviter's sealed metadata key for one person, base64-decoded: a Signal message around a 32-byte key and its
     * version, a few hundred bytes. The cap keeps a row small without depending on the wire format.
     */
    val maxInviteKeyBytes: Int = 8 * 1_024,
)

/** `MESSAGING_MAX_ATTACHMENT_BYTES` when unset: 100 MiB of ciphertext (decision 2026-11-07). */
const val MAX_ATTACHMENT_BYTES: Long = 100L * 1_024 * 1_024
