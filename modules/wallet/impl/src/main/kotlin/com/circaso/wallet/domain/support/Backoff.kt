package com.circaso.wallet.domain.support

/**
 * How a job that reads Circle backs off: after a run that couldn't reach Circle (down, its circuit open, or rate
 * limiting us past the shared client's retries), the next 1, 2, 4 … up to [maxSkips] runs are skipped, so the job
 * never hammers a vendor that is already refusing. One success resets it. Kept per node, in memory: a new leader
 * starts afresh, which costs at most one more refused call.
 */
class Backoff(private val maxSkips: Int = DEFAULT_MAX_SKIPS) {
    @Volatile
    private var failures = 0

    @Volatile
    private var skipsLeft = 0

    /** True when this run should be skipped; counts it. */
    fun skip(): Boolean {
        if (skipsLeft <= 0) return false
        skipsLeft--
        return true
    }

    /** Circle couldn't be read; returns how many runs are skipped now. */
    fun failed(): Int {
        failures++
        skipsLeft = minOf(1 shl minOf(failures - 1, MAX_SHIFT), maxSkips)
        return skipsLeft
    }

    fun succeeded() {
        failures = 0
        skipsLeft = 0
    }

    private companion object {
        const val DEFAULT_MAX_SKIPS = 16
        const val MAX_SHIFT = 10
    }
}
