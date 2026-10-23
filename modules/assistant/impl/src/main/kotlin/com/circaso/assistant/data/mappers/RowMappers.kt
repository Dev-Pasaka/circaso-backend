package com.circaso.assistant.data.mappers

import com.circaso.assistant.data.entities.AshaMessageRow
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.ReadNote
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.ports.TextVault
import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

@Serializable
private data class ReadNoteJson(val chatIds: List<String>, val messageCount: Int, val chatNames: List<String> = emptyList())

fun AshaMessageRow.toModel(vault: TextVault) = ChatMessage(
    UserId(userId), messageId, Role.valueOf(role), vault.open(body), replyTo, answeredBy, askedFrom,
    readNote?.let { runCatching { CircasoJson.decodeFromString(ReadNoteJson.serializer(), it) }.getOrNull() }?.let { ReadNote(it.chatIds, it.messageCount, it.chatNames) },
    createdAt,
)

fun ChatMessage.toRow(vault: TextVault) = AshaMessageRow(
    userId.value, messageId, role.name, vault.seal(text), replyTo, answeredBy, askedFrom,
    readNote?.let { CircasoJson.encodeToString(ReadNoteJson.serializer(), ReadNoteJson(it.chatIds, it.messageCount, it.chatNames)) },
    createdAt,
)
