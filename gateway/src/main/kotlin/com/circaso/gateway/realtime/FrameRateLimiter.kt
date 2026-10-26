package com.circaso.gateway.realtime

/**
 * How many frames one socket may send in a sliding window (decision 2026-10-25: 100 per second). It remembers when
 * each of the last [limit] accepted frames arrived, so a frame is refused exactly when [limit] frames were accepted
 * in the [windowMillis] before it, with no burst of twice the limit across a second boundary. It lives in memory
 * because the limit is per socket, and one socket is read by one coroutine.
 */
internal class FrameRateLimiter(
    private val limit: Int = FRAMES_PER_SECOND,
    private val windowMillis: Long = 1_000,
    /** Milliseconds from a monotonic source, so a wall-clock change never blocks a socket. */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val accepted = LongArray(limit)
    private var next = 0
    private var count = 0

    /** True when the frame arriving now is within the limit, and records it; false when it must be dropped. */
    fun tryAcquire(): Boolean {
        val now = nowMillis()
        // The slot to overwrite holds the oldest of the last [limit] accepted frames.
        if (count == limit && now - accepted[next] < windowMillis) return false
        accepted[next] = now
        next = (next + 1) % limit
        if (count < limit) count++
        return true
    }

    companion object {
        const val FRAMES_PER_SECOND = 100
    }
}
