package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.LiveEvent
import com.circaso.assistant.domain.ports.LiveTimings

/** A11, after each turn: how long it took to be heard, for the log and the latency histogram (decision 2026-10-30). */
class RecordLiveTiming(private val timings: LiveTimings) {
    suspend operator fun invoke(timing: LiveEvent.Timing) = timings.record(timing)
}
