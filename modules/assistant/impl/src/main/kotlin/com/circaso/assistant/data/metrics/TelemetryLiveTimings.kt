package com.circaso.assistant.data.metrics

import com.circaso.assistant.domain.models.LiveEvent
import com.circaso.assistant.domain.ports.LiveTimings
import com.circaso.observability.api.Metrics
import com.circaso.observability.api.Telemetry
import org.slf4j.LoggerFactory

/**
 * Each live turn's timing as one log line (the three durations and the turn number, nothing said) and, when the turn
 * was heard, its first-audio latency in the `circaso_assistant_live_first_audio_seconds` histogram, unlabelled.
 */
class TelemetryLiveTimings(private val telemetry: Telemetry) : LiveTimings {
    private val log = LoggerFactory.getLogger(TelemetryLiveTimings::class.java)

    override fun record(timing: LiveEvent.Timing) {
        log.info(
            "live voice turn timed: turn={} modelFirstTextMs={} firstSentenceMs={} firstAudioMs={}",
            timing.turn,
            timing.modelFirstTextMs,
            timing.firstSentenceMs,
            timing.firstAudioMs,
        )
        timing.firstAudioMs?.let { telemetry.record(Metrics.ASSISTANT_LIVE_FIRST_AUDIO, it / MILLIS_PER_SECOND) }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
