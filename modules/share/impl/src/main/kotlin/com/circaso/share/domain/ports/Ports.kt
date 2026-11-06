package com.circaso.share.domain.ports

import java.time.Duration

/**
 * How many times one caller address may open pages in a window (decision 2026-11-06: views are rate limited, so codes
 * can't be walked). Lives in Redis (`shr:views:{address}`), never in the database.
 */
interface ViewThrottle {
    /** Counts one view from [address]; the seconds to wait when the window's [max] is used up, else null. */
    suspend fun take(
        address: String,
        max: Long,
        window: Duration,
    ): Long?
}
