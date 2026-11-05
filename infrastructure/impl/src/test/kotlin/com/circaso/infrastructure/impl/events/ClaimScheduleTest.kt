package com.circaso.infrastructure.impl.events

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M7 (audit 2026-11-05): `XAUTOCLAIM` every 30 s per consumer, not on every read. */
class ClaimScheduleTest {
    private val second = 1_000_000_000L
    private val schedule = ClaimSchedule(Duration.ofSeconds(30), batchSize = 10)

    @Test
    fun `a consumer looks at its start, then not again until the interval has passed`() {
        assertTrue(schedule.due(0), "at start: a node that died may have left messages")
        schedule.ran(0, claimed = 0)

        assertFalse(schedule.due(2 * second), "the next read, 2 s later, doesn't look")
        assertFalse(schedule.due(29 * second))
        assertTrue(schedule.due(30 * second))
    }

    @Test
    fun `a look that filled a whole batch looks again at once, one that didn't waits`() {
        schedule.ran(0, claimed = 10)
        assertTrue(schedule.due(1), "more may be waiting")

        schedule.ran(1, claimed = 3)
        assertFalse(schedule.due(2 * second))
    }

    @Test
    fun `a clock reading past the long range still works out`() {
        val near = Long.MAX_VALUE - second
        schedule.ran(near, claimed = 0)
        assertFalse(schedule.due(near + second))
        assertTrue(schedule.due(near + 31 * second), "nanoTime may wrap; the difference still counts")
    }
}
