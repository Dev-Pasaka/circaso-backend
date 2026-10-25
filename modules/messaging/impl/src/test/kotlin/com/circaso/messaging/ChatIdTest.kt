package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.shared.CircasoJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `ChatId` travels in DTOs and events as its plain UUID string, like the other ids. */
class ChatIdTest {
    @Test
    fun `a chat id is written as its UUID and read back`() {
        val id = ChatId.new()

        val json = CircasoJson.encodeToString(ChatId.serializer(), id)

        assertEquals("\"$id\"", json)
        assertEquals(id, CircasoJson.decodeFromString(ChatId.serializer(), json))
    }

    @Test
    fun `text that is not a UUID is refused`() {
        assertFailsWith<SerializationException> {
            CircasoJson.decodeFromString(ChatId.serializer(), "\"not-a-chat\"")
        }
    }
}
