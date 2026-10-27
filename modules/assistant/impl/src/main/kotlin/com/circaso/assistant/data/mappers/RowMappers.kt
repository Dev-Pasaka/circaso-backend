package com.circaso.assistant.data.mappers

import com.circaso.assistant.domain.models.ReadNote
import com.circaso.shared.CircasoJson
import kotlinx.serialization.Serializable

/** `asha_messages.read_note`: the chat ids and the number of messages Asha read, never the messages. */
@Serializable
private data class ReadNoteJson(
    val chatIds: List<String>,
    val messageCount: Int,
    val chatNames: List<String> = emptyList(),
)

fun ReadNote.toColumn(): String =
    CircasoJson.encodeToString(ReadNoteJson.serializer(), ReadNoteJson(chatIds, messageCount, chatNames))

/** A note that no longer decodes is dropped rather than failing the whole page. */
fun readNoteOf(column: String?): ReadNote? =
    column
        ?.let { runCatching { CircasoJson.decodeFromString(ReadNoteJson.serializer(), it) }.getOrNull() }
        ?.let { ReadNote(it.chatIds, it.messageCount, it.chatNames) }
