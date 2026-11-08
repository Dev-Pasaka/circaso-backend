package com.circaso.assistant.domain.support

import java.time.Duration

/** When the health monitor probes a model that is down, and how often one person may hear she is back. */
object HealthSchedule {
    /** At most one "Asha is back" per person in this window, however often she goes down and up. */
    val NOTICE_WINDOW: Duration = Duration.ofHours(6)

    private val FIRST_PROBE: Duration = Duration.ofSeconds(30)
    private val LONGEST_WAIT: Duration = Duration.ofMinutes(2)
    private const val MAX_SHIFT = 8

    /**
     * The wait before the next probe after [failures] failed ones (0: from when she went down): 30 s, 30 s, 1 min,
     * 2 min, and 2 min from then on.
     */
    fun backoff(failures: Int): Duration {
        if (failures <= 1) return FIRST_PROBE
        val doubled = FIRST_PROBE.multipliedBy(1L shl (failures - 1).coerceAtMost(MAX_SHIFT))
        return if (doubled > LONGEST_WAIT) LONGEST_WAIT else doubled
    }
}
