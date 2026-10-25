package com.circaso.gateway

import com.circaso.gateway.realtime.FrameRateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameRateLimiterTest {
    private var now = 0L
    private val limiter = FrameRateLimiter(nowMillis = { now })

    private fun acceptedOf(frames: Int) = (1..frames).count { limiter.tryAcquire() }

    @Test
    fun `100 frames in a second pass and the 101st is refused`() {
        assertEquals(100, acceptedOf(100))
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `the window slides, so a burst across a second boundary is still capped at 100`() {
        now = 900
        assertEquals(100, acceptedOf(100))
        now = 1_100
        assertEquals(0, acceptedOf(10), "the burst at 900 ms is less than a second old")
        now = 1_900
        assertEquals(100, acceptedOf(150), "a full second after the burst, 100 more")
    }

    @Test
    fun `refused frames do not use up the allowance`() {
        assertEquals(100, acceptedOf(100))
        assertEquals(0, acceptedOf(500))
        now = 1_000
        assertTrue(limiter.tryAcquire())
    }
}
