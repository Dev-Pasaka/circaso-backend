package com.circaso.ramp.domain.support

import java.time.Duration

/** How a portal sign-in is timed (decision 2026-11-22). */
object MerchantSignIn {
    const val CODE_MINUTES = 10

    /** How long before the same email may ask for another code; MA1 answers it as `retryAfterSeconds`. */
    const val RESEND_SECONDS = 60L

    val SESSION_LIFETIME: Duration = Duration.ofHours(12)

    /** A session used within this long of its end is given a whole lifetime again. */
    val EXTEND_WITHIN: Duration = Duration.ofHours(1)
}
