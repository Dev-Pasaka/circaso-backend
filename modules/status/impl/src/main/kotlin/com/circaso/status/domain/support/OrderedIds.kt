package com.circaso.status.domain.support

import com.circaso.shared.Uuid7
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Moment ids that sort in the order they were made: ST4 lists by moment id ("oldest first within a poster"), and
 * UUIDv7 ids made in the same millisecond come out in random order. Each id here takes a timestamp at least one
 * millisecond after the last one this node made. The same class as messaging's: modules share no code but `shared`.
 */
class OrderedIds(private val clock: Clock) {
    private val last = AtomicLong(0)

    fun next(): UUID {
        val millis = last.updateAndGet { maxOf(clock.millis(), it + 1) }
        return Uuid7.generate(Instant.ofEpochMilli(millis))
    }
}
