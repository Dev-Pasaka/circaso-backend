package com.circaso.ramp.api

import com.circaso.shared.UserId

/** What other modules may ask the ramp. Nobody does yet; the assistant reads it later. */
interface Ramp {
    /** Whether [userId] has an order that hasn't finished. */
    suspend fun hasOpenOrder(userId: UserId): Boolean
}
