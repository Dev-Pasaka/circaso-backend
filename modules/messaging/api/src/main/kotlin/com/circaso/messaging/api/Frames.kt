@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.circaso.messaging.api

import com.circaso.shared.InstantSerializer
import com.circaso.shared.UserId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.util.UUID

// The `data` of every frame messaging sends to phones (module `messaging`; Messaging → Socket frames → Frame data),
// one class per frame type (decision 2026-10-27). They are public so the gateway decodes the ones it pushes.

/** `MESSAGE`, `SENDER_KEY` or `CONTROL`: only a `MESSAGE` is shown, or wakes anyone. */
enum class MessageKind { MESSAGE, SENDER_KEY, CONTROL }

enum class ReceiptStatus { DELIVERED, READ }

/** A frame messaging sends. [type] names it in the envelope and is not part of its `data`. */
sealed interface MessagingFrame {
    val type: String
}

/**
 * A message for the recipient. [ciphertext] rides along up to `MESSAGING_FRAME_CIPHERTEXT_MAX_CHARS` base64
 * characters (16 K) and is null beyond, when the phone fetches it from the inbox (M13). The gateway pushes offline
 * recipients only when [pushable]. [keyRequest]: a `SENDER_KEY` copy in which a phone without a group's metadata key
 * asks the members for it (2026-11-09); the gateway wakes an offline member silently (`chat.meta_key_requested`).
 */
@Serializable
data class MessageNewFrame(
    val messageId: UUID,
    val chatId: ChatId,
    val senderId: UserId,
    val kind: MessageKind,
    val pushable: Boolean = false,
    val sentAt: Instant,
    val mediaIds: List<UUID> = emptyList(),
    val ciphertext: String? = null,
    val keyRequest: Boolean = false,
) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "message.new"
    }
}

@Serializable
data class ReceiptItem(
    val messageId: UUID,
    val userId: UserId,
    val status: ReceiptStatus,
    val at: Instant,
)

/** To a message's sender only; a group's arrive batched every 5 seconds. */
@Serializable
data class ReceiptFrame(val chatId: ChatId, val receipts: List<ReceiptItem>) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "receipt"
    }
}

@Serializable
data class TypingFrame(val chatId: ChatId, val userId: UserId, val on: Boolean) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "typing"
    }
}

@Serializable
data class MessageDeletedFrame(val chatId: ChatId, val messageId: UUID) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "message.deleted"
    }
}

/** To the sender, when [recipientId]'s phone was replaced before the message reached it. */
@Serializable
data class MessageUndeliverableFrame(val chatId: ChatId, val messageId: UUID, val recipientId: UserId) :
    MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "message.undeliverable"
    }
}

@Serializable
data class KeysChangedFrame(val userId: UserId) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "keys.changed"
    }
}

/**
 * [userIds] is everyone one action added, sorted; only they are pushed (decision 2026-10-26). [joined] (2026-11-09,
 * group invites): the one person in [userIds] accepted an invite from [byUserId], so phones say "X joined" rather
 * than "Y added X", and nobody is pushed, since the person who joined is the one who acted.
 */
@Serializable
data class MemberAddedFrame(
    val chatId: ChatId,
    val userIds: List<UserId>,
    val byUserId: UserId,
    val joined: Boolean = false,
) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "member.added"
    }
}

@Serializable
data class MemberRemovedFrame(val chatId: ChatId, val userId: UserId, val byUserId: UserId) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "member.removed"
    }
}

/**
 * The group changed: its sealed name, photo, settings or roles. [avatarMediaId] is the group's photo as it now is
 * (null when it has none), so a phone that missed the change can fetch it (decision 2026-11-05).
 */
@Serializable
data class ChatUpdatedFrame(
    val chatId: ChatId,
    val metaVersion: Int,
    val version: Long,
    val avatarMediaId: UUID? = null,
) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "chat.updated"
    }
}

/**
 * Whether [userId] is in the app right now, to the people watching them (`messaging.presence.watch`), never
 * replayed. [online] is true while their phone sent `presence` `active` within the last 75 seconds and hasn't said
 * `away` since. [lastSeenAt] is the last moment they were active (while online, their latest heartbeat), kept 30
 * days; null when the server has never seen them, or not in that long.
 */
@Serializable
data class PresenceChangedFrame(val userId: UserId, val online: Boolean, val lastSeenAt: Instant?) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "presence.changed"
    }
}

/**
 * To the person invited (2026-11-09, group invites): [byUserId] invited them to [chatId]. The group's name is sealed,
 * so the phone loads its invites (`GET /chats/invites`) to name it; [byName] is the inviter's display name, for the
 * push's words when the phone is offline. Pushed.
 */
@Serializable
data class InviteReceivedFrame(
    val inviteId: UUID,
    val chatId: ChatId,
    val byUserId: UserId,
    val byName: String,
) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "invite.received"
    }
}

/** To the person invited: the invite went before they answered it (cancelled). Drop it from the inbox. */
@Serializable
data class InviteRemovedFrame(val inviteId: UUID, val chatId: ChatId) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "invite.removed"
    }
}

/**
 * To a group's admins and the inviter: the group's pending invites changed (one sent, accepted, declined or
 * cancelled). Reload `GET /chats/{chatId}/invites` if group info is open. Never pushed, and says nothing of why, so a
 * decline never becomes a notification.
 */
@Serializable
data class InvitesChangedFrame(val chatId: ChatId) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "invites.changed"
    }
}

/** A message id on the wire: its canonical text. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MessageUuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
