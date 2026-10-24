package com.circaso.shared

import kotlinx.serialization.SerializationException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdsTest {
    @Test
    fun `generated ids are UUIDv7 with the creation time in them`() {
        val at = Instant.parse("2026-10-22T10:15:30.123Z")
        val uuid = Uuid7.generate(at)

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
        assertEquals(at, Uuid7.timestampOf(uuid))
    }

    @Test
    fun `ids created later sort after ids created earlier`() {
        val earlier = Uuid7.generate(Instant.parse("2026-10-22T10:00:00Z")).toString()
        val later = Uuid7.generate(Instant.parse("2026-10-22T10:00:01Z")).toString()

        assertTrue(earlier < later)
    }

    @Test
    fun `a UUIDv4 has no timestamp`() {
        assertNull(Uuid7.timestampOf(UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")))
    }

    @Test
    fun `parse accepts only the canonical form`() {
        val id = UserId.new()

        assertEquals(id, UserId.parse(id.toString()))
        assertNull(UserId.parse("1-1-1-1-1"))
        assertNull(UserId.parse("not-a-uuid"))
        assertNull(UserId.parse(""))
    }

    @Test
    fun `an id serialises as its UUID string`() {
        val id = DeviceId.new()
        val json = CircasoJson.encodeToString(DeviceId.serializer(), id)

        assertEquals("\"$id\"", json)
        assertEquals(id, CircasoJson.decodeFromString(DeviceId.serializer(), json))
    }

    @Test
    fun `reading a malformed id fails`() {
        assertFailsWith<SerializationException> {
            CircasoJson.decodeFromString(UserId.serializer(), "\"nope\"")
        }
    }
}
