package com.circaso.assistant.domain.models

import java.time.Instant

/**
 * Whether Asha can answer (decision 2026-11-07): false while her model is down for maintenance. [since] is when that
 * last changed; null when she has never been down.
 */
data class AshaStatus(val isAvailable: Boolean, val since: Instant?)

/** The health monitor's probing while Asha is down: [failures] probes so far, and when the next one is due. */
data class ProbeState(val failures: Int, val nextAt: Instant)
