package com.circaso.messaging.data.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.data.entities.ChatRow
import com.circaso.messaging.data.entities.GroupMessageRow
import com.circaso.messaging.data.entities.InboxRow
import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.MemberRow
import com.circaso.messaging.data.entities.MessageRecordRow
import com.circaso.messaging.data.entities.MessagingSettingsRow
import com.circaso.messaging.data.entities.NIL_UUID
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.data.entities.UserChatRow
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatSettings
import com.circaso.messaging.domain.models.GroupBody
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.messaging.domain.models.UserChat
import com.circaso.shared.CircasoJson
import com.circaso.shared.DeviceId
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable
import java.util.UUID

fun KeyBundleRow.toModel() =
    KeyBundle(
        userId = UserId(userId),
        deviceId = DeviceId(deviceId),
        identityKey = identityKey,
        signedPreKey = SignedPreKey(signedPreKeyId, signedPreKey, signedPreKeySignature),
        signedPreKeyAt = signedPreKeyAt,
    )

fun KeyBundle.toRow() =
    KeyBundleRow(
        userId = userId.value,
        deviceId = deviceId.value,
        identityKey = identityKey,
        signedPreKeyId = signedPreKey.keyId,
        signedPreKey = signedPreKey.publicKey,
        signedPreKeySignature = signedPreKey.signature,
        signedPreKeyAt = signedPreKeyAt,
    )

fun OneTimePreKeyRow.toModel() = OneTimePreKey(keyId, publicKey)

fun OneTimePreKey.toRow(userId: UserId) = OneTimePreKeyRow(userId.value, keyId, publicKey)

/** `chats.settings` as stored: JSON, so a setting can be added without a migration. */
@Serializable
private data class SettingsJson(val membersCanAdd: Boolean = false)

private fun ChatSettings.toJson() = CircasoJson.encodeToString(SettingsJson.serializer(), SettingsJson(membersCanAdd))

private fun settingsOf(raw: String) =
    runCatching { CircasoJson.decodeFromString(SettingsJson.serializer(), raw) }
        .getOrDefault(SettingsJson())
        .let { ChatSettings(it.membersCanAdd) }

private fun UUID.orNull(): UUID? = takeIf { it != NIL_UUID }

fun ChatRow.toModel() =
    Chat(
        chatId = ChatId(chatId),
        kind = ChatKind.valueOf(kind),
        sealedMeta = sealedMeta.ifEmpty { null },
        metaVersion = metaVersion,
        settings = settingsOf(settings),
        memberCount = memberCount,
        createdBy = UserId(createdBy),
        createdAt = createdAt,
        version = version,
        ownerId = ownerId.orNull()?.let(::UserId),
        avatarMediaId = avatarMediaId.orNull(),
    )

fun Chat.toRow() =
    ChatRow(
        chatId = chatId.value,
        kind = kind.name,
        sealedMeta = sealedMeta.orEmpty(),
        metaVersion = metaVersion,
        settings = settings.toJson(),
        memberCount = memberCount,
        createdBy = createdBy.value,
        createdAt = createdAt,
        version = version,
        ownerId = ownerId?.value ?: NIL_UUID,
        avatarMediaId = avatarMediaId ?: NIL_UUID,
    )

fun MemberRow.toModel() = Member(ChatId(chatId), UserId(userId), Role.valueOf(role), joinedAt)

fun Member.toRow() = MemberRow(chatId.value, userId.value, role.name, joinedAt)

fun UserChatRow.toModel() =
    UserChat(
        userId = UserId(userId),
        chatId = ChatId(chatId),
        kind = ChatKind.valueOf(kind),
        lastMessageId = lastMessageId.orNull(),
        readUpTo = readUpTo.orNull(),
        joinedAt = joinedAt,
    )

/** A new chat-list line: no message yet, so both markers hold the nil UUID. */
fun Member.toUserChatRow(kind: ChatKind) = UserChatRow(userId.value, chatId.value, kind.name, joinedAt = joinedAt)

/** A pointer's empty ciphertext reads back as null: its ciphertext is the group body's. */
fun InboxRow.toModel() =
    InboxMessage(
        userId = UserId(userId),
        messageId = messageId,
        chatId = ChatId(chatId),
        senderId = UserId(senderId),
        kind = MessageKind.valueOf(kind),
        ciphertext = ciphertext.ifEmpty { null },
        groupBody = groupBody,
        mediaIds = mediaIdsOf(mediaIds),
        sentAt = sentAt,
    )

fun InboxMessage.toRow() =
    InboxRow(
        userId = userId.value,
        messageId = messageId,
        chatId = chatId.value,
        senderId = senderId.value,
        kind = kind.name,
        ciphertext = ciphertext.orEmpty(),
        groupBody = groupBody,
        mediaIds = mediaIds.joinToString(","),
        sentAt = sentAt,
    )

private fun mediaIdsOf(raw: String): List<UUID> =
    raw
        .split(',')
        .filter { it.isNotBlank() }
        .map { UUID.fromString(it) }

fun MessageRecordRow.toModel() =
    MessageRecord(messageId, ChatId(chatId), UserId(senderId), MessageKind.valueOf(kind), sentAt)

fun MessageRecord.toRow() = MessageRecordRow(messageId, chatId.value, senderId.value, kind.name, sentAt)

fun GroupMessageRow.toModel() = GroupBody(ChatId(chatId), messageId, UserId(senderId), ciphertext, sentAt)

fun GroupBody.toRow() = GroupMessageRow(chatId.value, messageId, senderId.value, ciphertext, sentAt)

fun MessagingSettingsRow.toModel() = MessagingSettings(readReceipts, typing)

fun MessagingSettings.toRow(userId: UserId) = MessagingSettingsRow(userId.value, readReceipts, typing)
