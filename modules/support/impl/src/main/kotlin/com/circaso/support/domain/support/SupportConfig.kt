package com.circaso.support.domain.support

import java.time.Duration

/**
 * Support's settings. [ticketsPerDay] (`SUPPORT_TICKETS_PER_DAY`, decision 2026-11-08: 5) caps new tickets per user
 * per day, from the app and Asha together: enough for a real problem or two, too few to flood the queue. A user with
 * more to say replies on a ticket they have. Lists follow the guide's paging (50 by default, at most 200).
 */
data class SupportConfig(
    val ticketsPerDay: Long = 5,
    val ticketWindow: Duration = Duration.ofDays(1),
    val pageDefault: Int = 50,
    val pageMax: Int = 200,
)
