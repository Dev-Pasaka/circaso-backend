package com.circaso.assistant.domain.support

import com.circaso.shared.Uuid7
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Message ids that sort in the order they were made. UUIDv7 ids made in the same millisecond come out in random
 * order, and a chat shows messages by id, so a question and its answer (or two quick turns) could swap. Each id here
 * takes a timestamp at least one millisecond after the last, so the chat order is the order things happened.
 */
class OrderedIds(private val clock: Clock) {
    private val last = AtomicLong(0)

    fun next(): UUID {
        val millis = last.updateAndGet { maxOf(clock.millis(), it + 1) }
        return Uuid7.generate(Instant.ofEpochMilli(millis))
    }
}
