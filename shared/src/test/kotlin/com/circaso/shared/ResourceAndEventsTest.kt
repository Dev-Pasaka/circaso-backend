package com.circaso.shared

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceAndEventsTest {

    private object Taken : DomainError {
        override val code = "identity.phone_taken"
        override val message = "This number already has a Circaso account."
        override val kind = ErrorKind.CONFLICT
    }

    @Test
    fun `map and flatMap carry a success forward and an error through`() {
        val ok: Resource<Int> = Resource.success(2)
        val failed: Resource<Int> = Resource.error(Taken)

        assertEquals(4, ok.map { it * 2 }.getOrNull())
        assertEquals(6, ok.flatMap { Resource.success(it * 3) }.getOrNull())
        assertEquals(Taken, (failed.map { it * 2 } as Resource.Error).error)
        assertEquals(Taken, (failed.flatMap { Resource.success(it) } as Resource.Error).error)
        assertNull(failed.getOrNull())
    }

    @Test
    fun `flatMap lets the next step suspend`() = runTest {
        val result = Resource.success(1).flatMap { next(it) }

        assertEquals(2, result.getOrNull())
    }

    private suspend fun next(value: Int): Resource<Int> = Resource.success(value + 1)

    @Test
    fun `getOrThrow turns an error into a DomainException`() {
        val thrown = assertFailsWith<DomainException> { Resource.error(Taken).getOrThrow() }

        assertEquals(Taken, thrown.error)
    }

    @Test
    fun `a malformed request lists the fields that are wrong`() {
        val error = SharedError.Malformed(mapOf("displayName" to "required"))

        assertEquals("request.malformed", error.code)
        assertEquals(ErrorKind.INVALID, error.kind)
        assertEquals(mapOf("displayName" to "required"), error.details?.fields)
        assertNull(SharedError.Malformed().details)
    }

    @Serializable
    private data class Activated(val userId: UserId, val phoneHmac: String) : DomainEvent {
        override val type: String get() = "identity.AccountActivated"
    }

    @Test
    fun `an event is wrapped in the envelope and read back`() {
        val userId = UserId.new()
        val at = Instant.parse("2026-10-21T18:07:12Z")

        val envelope = EventEnvelope.of(
            Activated(userId, "ab12"), Activated.serializer(),
            occurredAt = at, traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        )
        val read = EventEnvelope.fromJson(envelope.toJson())

        assertEquals(envelope, read)
        assertEquals("identity", read.producer)
        assertEquals("events:identity", read.stream)
        assertEquals(1, read.version)
        assertEquals(Activated(userId, "ab12"), read.decode<Activated>())
    }

    @Test
    fun `an event is stored under its UTC minute bucket, and buckets step by a minute`() {
        val envelope = EventEnvelope.of(Activated(UserId.new(), "ab12"), Activated.serializer(), occurredAt = Instant.parse("2026-10-21T18:06:59.999Z"))

        assertEquals("2026-10-21T18:06", envelope.bucket)
        assertEquals(Instant.parse("2026-10-21T18:06:00Z"), EventEnvelope.bucketStart(envelope.bucket))
        assertEquals("2026-10-21T18:07", EventEnvelope.nextBucket(envelope.bucket))
        assertEquals("2026-10-22T00:00", EventEnvelope.nextBucket("2026-10-21T23:59"))
        assertFalse(envelope.toJson().contains("bucket"), "the bucket is derived, not part of the wire format")
    }

    @Test
    fun `the envelope has the documented field names and ISO time`() {
        val envelope = EventEnvelope.of(Activated(UserId.new(), "ab12"), Activated.serializer(), occurredAt = Instant.parse("2026-10-21T18:07:12Z"))
        val json = envelope.toJson()

        val fields = listOf(
            "\"eventId\"", "\"type\":\"identity.AccountActivated\"", "\"version\":1",
            "\"occurredAt\":\"2026-10-21T18:07:12Z\"", "\"producer\":\"identity\"", "\"data\"",
        )
        fields.forEach { assertTrue(json.contains(it), "missing $it in $json") }
    }
}
