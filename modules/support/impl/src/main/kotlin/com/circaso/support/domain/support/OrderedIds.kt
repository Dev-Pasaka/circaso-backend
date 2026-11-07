package com.circaso.support.domain.support

import com.circaso.shared.Uuid7
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Ticket and message ids that sort in the order they were made: lists page by id, and UUIDv7 ids made in the same
 * millisecond come out in random order. Each id here takes a timestamp at least one millisecond after the last one this
 * node made. The same class as status' and messaging's: modules share no code but `shared`.
 */
class OrderedIds(private val clock: Clock) {
    private var last = 0L

    @Synchronized
    fun next(): UUID {
        last = maxOf(clock.millis(), last + 1)
        return Uuid7.generate(Instant.ofEpochMilli(last))
    }
}

/** When an id from [OrderedIds] was made: a ticket's `created_at` is exactly this, so the two never disagree. */
fun UUID.madeAt(): Instant = Uuid7.timestampOf(this) ?: error("not a UUIDv7")
