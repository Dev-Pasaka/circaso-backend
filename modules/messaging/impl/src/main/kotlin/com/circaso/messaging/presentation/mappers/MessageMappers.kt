package com.circaso.messaging.presentation.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.InboxPage
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.models.RecipientCopy
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.models.SentMessage
import com.circaso.messaging.presentation.dto.InboxMessageDto
import com.circaso.messaging.presentation.dto.InboxResponse
import com.circaso.messaging.presentation.dto.MessagingSettingsResponse
import com.circaso.messaging.presentation.dto.SendChatMessageRequest
import com.circaso.messaging.presentation.dto.SentMessageResponse
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import java.util.Base64
import java.util.UUID

// ---- request → command: shape checks answer `request.malformed`; sizes and members are the use case's ----

private fun malformed(field: String, why: String = "is not valid") = Resource.error(SharedError.Malformed(field, why))

private fun isBase64(text: String): Boolean =
    text.isNotEmpty() && runCatching { Base64.getDecoder().decode(text) }.isSuccess

fun parseMessageId(raw: String?, field: String = "messageId"): Resource<UUID> =
    raw?.let(Uuid7::parse)?.let { Resource.success(it) } ?: malformed(field)

/** At least one id and at most [max], each a UUID; repeats are folded into one. */
fun parseMessageIds(raw: List<String>, max: Int): Resource<List<UUID>> {
    if (raw.isEmpty() || raw.size > max) return malformed("messageIds", "1 to $max ids")
    val ids = raw.map { Uuid7.parse(it) ?: return malformed("messageIds", "each must be a message id") }
    return Resource.success(ids.distinct())
}

/** M13's query: `after` a message id or absent; `limit` 1 to [pageMax], a full page when absent. */
fun parseInboxQuery(
    after: String?,
    limit: String?,
    pageMax: Int,
): Resource<Pair<UUID?, Int>> {
    val afterId = after?.let { Uuid7.parse(it) ?: return malformed("after") }
    val size = limit?.let { it.toIntOrNull() ?: return malformed("limit") } ?: pageMax
    if (size !in 1..pageMax) return malformed("limit", "1 to $pageMax")
    return Resource.success(afterId to size)
}

fun SendChatMessageRequest.toCommand(sender: UserId, chatId: ChatId): Resource<SendMessageCommand> {
    val clientId = Uuid7.parse(clientMessageId) ?: return malformed("clientMessageId")
    val kind = MessageKind.entries.firstOrNull { it.name == kind } ?: return malformed("kind", KINDS)
    if (ciphertext != null && !isBase64(ciphertext)) return malformed("ciphertext", BASE64)
    val copies =
        recipients.map { copy ->
            val userId = UserId.parse(copy.userId) ?: return malformed("recipients", "each needs a user id")
            if (!isBase64(copy.ciphertext)) return malformed("recipients", BASE64)
            RecipientCopy(userId, copy.ciphertext)
        }
    val media = mediaIds.map { Uuid7.parse(it) ?: return malformed("mediaIds", "each must be a media id") }
    val command =
        SendMessageCommand(
            sender = sender,
            chatId = chatId,
            clientMessageId = clientId,
            kind = kind,
            ciphertext = ciphertext,
            recipients = copies,
            mediaIds = media.distinct(),
            pushable = pushable,
            keyRequest = keyRequest,
        )
    return Resource.success(command)
}

private const val KINDS = "must be MESSAGE, SENDER_KEY or CONTROL"
private const val BASE64 = "must be base64"

// ---- model → response ----

fun SentMessage.toResponse() = SentMessageResponse(messageId.toString(), sentAt)

fun InboxPage.toResponse() = InboxResponse(messages.map { it.toDto() }, next?.toString())

private fun InboxMessage.toDto() =
    InboxMessageDto(
        messageId = messageId.toString(),
        chatId = chatId.toString(),
        senderId = senderId.toString(),
        kind = kind.name,
        ciphertext = ciphertext.orEmpty(),
        mediaIds = mediaIds.map { it.toString() },
        sentAt = sentAt,
    )

fun MessagingSettings.toResponse() = MessagingSettingsResponse(readReceipts, typing)
