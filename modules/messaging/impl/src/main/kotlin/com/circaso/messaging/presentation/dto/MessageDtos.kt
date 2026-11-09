package com.circaso.messaging.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class RecipientDto(val userId: String, val ciphertext: String)

/**
 * M12. `ciphertext` is a group's one ciphertext; `recipients` are per-recipient copies (direct, and `SENDER_KEY` to
 * some members of a group). All ciphertext is base64. `pushable` wakes offline recipients; left out, it is false.
 * `keyRequest` (a `SENDER_KEY` only): the copies ask for the group's metadata key, and offline recipients get a silent
 * `chat.meta_key_requested` push so their phone answers; left out, it is false.
 */
@Serializable
data class SendChatMessageRequest(
    val clientMessageId: String,
    /** `MESSAGE`, `SENDER_KEY` or `CONTROL`. */
    val kind: String,
    val ciphertext: String? = null,
    val recipients: List<RecipientDto> = emptyList(),
    val mediaIds: List<String> = emptyList(),
    val pushable: Boolean = false,
    val keyRequest: Boolean = false,
)

@Serializable
data class SentMessageResponse(
    val messageId: String,
    @Serializable(with = InstantSerializer::class) val sentAt: Instant,
)

@Serializable
data class InboxMessageDto(
    val messageId: String,
    val chatId: String,
    val senderId: String,
    /** `MESSAGE`, `SENDER_KEY` or `CONTROL`. */
    val kind: String,
    val ciphertext: String,
    val mediaIds: List<String>,
    @Serializable(with = InstantSerializer::class) val sentAt: Instant,
)

/** `next` is the `after` of the next page, null on the last. */
@Serializable
data class InboxResponse(val messages: List<InboxMessageDto>, val next: String? = null)

@Serializable
data class AckRequest(val messageIds: List<String>)

@Serializable
data class AckResponse(val acked: Int)

@Serializable
data class MessageDeletedResponse(val deleted: Boolean)

/** M22: a field left out stays as it is. */
@Serializable
data class MessagingSettingsRequest(val readReceipts: Boolean? = null, val typing: Boolean? = null)

@Serializable
data class MessagingSettingsResponse(val readReceipts: Boolean, val typing: Boolean)

/**
 * A frame from the phone (`{"module":"messaging","type":"ack","data":{…}}`), one class per `type`, which is the
 * class discriminator; the hub hands over `type` and `data` apart, and [PhoneFrame.serializerFor] picks the class.
 */
@Serializable
sealed interface PhoneFrame {
    /** `messaging.ack`: these messages are on the phone. */
    @Serializable
    @SerialName("ack")
    data class Ack(val messageIds: List<String>) : PhoneFrame

    /** `messaging.read`: the chat is read up to this message. */
    @Serializable
    @SerialName("read")
    data class Read(val chatId: String, val upToMessageId: String) : PhoneFrame

    /** `messaging.typing`: the user started or stopped typing. */
    @Serializable
    @SerialName("typing")
    data class Typing(val chatId: String, val on: Boolean) : PhoneFrame

    /** `messaging.presence`: `active` in the foreground (every 30 seconds), `away` 15 seconds after leaving it. */
    @Serializable
    @SerialName("presence")
    data class Presence(val state: String) : PhoneFrame

    /** `messaging.presence.watch`: hear the chat's other members' presence for the next 60 seconds. */
    @Serializable
    @SerialName("presence.watch")
    data class PresenceWatch(val chatId: String) : PhoneFrame

    companion object {
        /** The class of a frame of [type], or null for a type messaging does not take. */
        fun serializerFor(type: String): KSerializer<out PhoneFrame>? =
            when (type) {
                "ack" -> Ack.serializer()
                "read" -> Read.serializer()
                "typing" -> Typing.serializer()
                "presence" -> Presence.serializer()
                "presence.watch" -> PresenceWatch.serializer()
                else -> null
            }
    }
}
