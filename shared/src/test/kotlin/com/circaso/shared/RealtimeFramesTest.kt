package com.circaso.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RealtimeFramesTest {
    @Serializable
    private data class Joined(val userId: UserId, val displayName: String, val version: Long)

    @Serializable
    private data class Ack(val messageId: String, val upTo: Long = 0)

    @Serializable
    private data class Typing(val on: Boolean = true)

    @Test
    fun `an envelope carries its typed frame and reads back into it`() {
        val frame = Joined(UserId.new(), "Amina", 7)
        val envelope =
            RealtimeEnvelope.of(
                "users",
                "contact.joined",
                12,
                frame,
                Joined.serializer(),
                Instant.parse("2026-10-27T10:00:00Z"),
            )

        val read =
            CircasoJson.decodeFromString(
                RealtimeEnvelope.serializer(),
                CircasoJson.encodeToString(RealtimeEnvelope.serializer(), envelope),
            )

        assertEquals(envelope, read)
        assertEquals(frame, read.decodeData<Joined>())
    }

    @Test
    fun `numbers go out as numbers`() {
        val envelope =
            RealtimeEnvelope.of(
                "users",
                "profile.updated",
                1,
                Joined(UserId.new(), "A", 42),
                Joined.serializer(),
            )

        val json = CircasoJson.encodeToString(RealtimeEnvelope.serializer(), envelope)

        assertTrue("\"version\":42" in json, json)
    }

    @Test
    fun `resync carries an empty object`() {
        val json = CircasoJson.encodeToString(RealtimeEnvelope.serializer(), RealtimeEnvelope.resync("messaging", 3))

        assertTrue("\"type\":\"resync\"" in json, json)
        assertTrue("\"data\":{}" in json, json)
        assertTrue("\"seq\":3" in json, json)
    }

    @Test
    fun `a phone frame reads into the class its module names`() {
        val raw = """{"module":"messaging","type":"ack","data":{"messageId":"m1","upTo":5,"extra":true}}"""

        val frame = CircasoJson.decodeFromString(ClientFrame.serializer(), raw)

        assertEquals(Ack("m1", 5), frame.decodeData<Ack>())
    }

    @Test
    fun `a phone frame without data reads into a class whose fields have defaults, and a wrong one throws`() {
        val bare = CircasoJson.decodeFromString(ClientFrame.serializer(), """{"module":"m","type":"typing"}""")
        val wrong =
            CircasoJson.decodeFromString(
                ClientFrame.serializer(),
                """{"module":"m","type":"ack","data":{"upTo":"x"}}""",
            )

        assertEquals(Typing(), bare.decodeData<Typing>())
        assertFailsWith<SerializationException> { wrong.decodeData<Ack>() }
    }
}
