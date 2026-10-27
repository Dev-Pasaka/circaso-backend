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
 * recipients only when [pushable].
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

/** [userIds] is everyone one action added, sorted; only they are pushed (decision 2026-10-26). */
@Serializable
data class MemberAddedFrame(val chatId: ChatId, val userIds: List<UserId>, val byUserId: UserId) : MessagingFrame {
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

@Serializable
data class ChatUpdatedFrame(val chatId: ChatId, val metaVersion: Int, val version: Long) : MessagingFrame {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "chat.updated"
    }
}

/** A message id on the wire: its canonical text. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MessageUuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
