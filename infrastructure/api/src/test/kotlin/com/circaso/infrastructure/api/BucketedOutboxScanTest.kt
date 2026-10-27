package com.circaso.infrastructure.api

import com.circaso.shared.DomainEvent
import com.circaso.shared.EventEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class Noted(val n: Int) : DomainEvent {
    override val type: String get() = "scan.Noted"
}

/** A clock the test moves by hand. */
private class HandClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

/** The Kandra outboxes' answer to the bucket-less relay (temporary, until PG6). */
class BucketedOutboxScanTest {
    private val start = Instant.parse("2026-10-27T12:00:00Z")
    private val clock = HandClock(start)

    /** Unpublished rows by bucket, and every bucket the scan read, in order. */
    private val rows = mutableMapOf<String, MutableList<EventEnvelope>>()
    private val read = mutableListOf<String>()

    private fun scan(maxBuckets: Int = 120) =
        BucketedOutboxScan(clock, maxBuckets = maxBuckets) { bucket ->
            read += bucket
            rows[bucket].orEmpty().toList()
        }

    private fun write(n: Int, at: Instant): EventEnvelope =
        EventEnvelope.of(Noted(n), Noted.serializer(), occurredAt = at).also {
            rows.getOrPut(it.bucket) { mutableListOf() } += it
        }

    private fun stamp(envelope: EventEnvelope) {
        rows[envelope.bucket]?.remove(envelope)
    }

    @Test
    fun `the first call starts an hour back and stops at the last bucket that ended over 10 seconds ago`() =
        runBlocking<Unit> {
            scan().unpublished(500)

            assertEquals(EventEnvelope.bucketOf(start.minus(Duration.ofHours(1))), read.first())
            assertEquals("2026-10-27T11:58", read.last(), "12:00 hasn't ended; 11:59 ended only now")
        }

    @Test
    fun `the start moves past buckets whose rows were stamped, and resumes at the first bucket with rows`() =
        runBlocking<Unit> {
            val scan = scan()
            val stuck = write(1, start.minus(Duration.ofMinutes(30)))

            assertEquals(listOf(stuck.eventId), scan.unpublished(500).map { it.eventId })
            read.clear()
            clock.now = start.plusSeconds(120)
            scan.unpublished(500)
            assertEquals(stuck.bucket, read.first(), "it resumes where the unpublished row was")

            stamp(stuck)
            scan.unpublished(500)
            read.clear()
            clock.now = start.plusSeconds(240)
            scan.unpublished(500)
            assertTrue(read.first() > stuck.bucket, "past the stamped bucket: ${read.first()}")
        }

    @Test
    fun `a row whose stamp keeps failing doesn't hide newer rows two hours later`() =
        runBlocking<Unit> {
            val scan = scan()
            val stuck = write(1, start.minus(Duration.ofMinutes(5)))
            scan.unpublished(500) // finds it; its stamp never lands

            clock.now = start.plus(Duration.ofHours(2))
            val newer = write(2, clock.now.minus(Duration.ofMinutes(5)))

            val found = scan.unpublished(500).map { it.eventId }
            assertTrue(newer.eventId in found, "the newer row is still rescued")
            assertTrue(stuck.eventId !in found, "the stuck one is past the hour's lookback")
        }

    @Test
    fun `a call returns at most the limit, and the rest come on the next call`() =
        runBlocking<Unit> {
            val scan = scan()
            val at = start.minus(Duration.ofMinutes(10))
            val all = (1..5).map { write(it, at) }

            val first = scan.unpublished(3)
            first.forEach(::stamp)
            val second = scan.unpublished(3)

            assertEquals(3, first.size)
            assertEquals(all.map { it.eventId }.toSet(), (first + second).map { it.eventId }.toSet())
        }

    @Test
    fun `a call reads at most maxBuckets buckets`() =
        runBlocking<Unit> {
            scan(maxBuckets = 7).unpublished(500)

            assertEquals(7, read.size)
        }
}
