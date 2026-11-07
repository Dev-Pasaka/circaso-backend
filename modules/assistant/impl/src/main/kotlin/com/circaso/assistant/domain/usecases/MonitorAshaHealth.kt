package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.AshaStatus
import com.circaso.assistant.domain.models.ProbeState
import com.circaso.assistant.domain.ports.AshaHealth
import com.circaso.assistant.domain.ports.HealthLog
import com.circaso.assistant.domain.ports.StatusFrames
import com.circaso.assistant.domain.support.HealthSchedule
import com.circaso.integrations.api.LanguageModel
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The health monitor's one tick (`assistant.model-health`, decision 2026-11-07), on one node at a time.
 *
 * - **Down:** probes the model once the next probe is due: 30 s after she went down, then 30 s, 1 min and 2 min apart,
 *   and every 2 min after that. The first probe that answers marks her up (as any real answer does).
 * - **Up:** everyone recorded meeting her down hears once that she is back: one `assistant` `status` frame each, which
 *   the gateway sends to the socket, or as the "Asha is back" push when the app isn't open. Each person gets at most
 *   one such notice per [noticeWindow], so a flapping outage can't send a stream of them; the list is emptied as it is
 *   read, so nobody is told twice for one outage.
 *
 * Returns how many probes and notices it made, for the job's log line.
 */
class MonitorAshaHealth(
    private val health: AshaHealth,
    private val model: LanguageModel,
    private val frames: StatusFrames,
    private val clock: Clock,
    private val log: HealthLog = HealthLog.None,
    private val noticeWindow: Duration = HealthSchedule.NOTICE_WINDOW,
) {
    suspend operator fun invoke(): Resource<Int> {
        var status = health.status()
        var done = 0
        if (!status.isAvailable) {
            if (!isProbeDue(status)) return Resource.success(0)
            done++
            status = probe(status)
        }
        if (status.isAvailable) done += announce(status)
        return Resource.success(done)
    }

    private suspend fun isProbeDue(status: AshaStatus): Boolean {
        val nextAt = health.probe()?.nextAt ?: (status.since ?: clock.instant()).plus(HealthSchedule.backoff(0))
        return !clock.instant().isBefore(nextAt)
    }

    private suspend fun probe(status: AshaStatus): AshaStatus {
        val now = clock.instant()
        if (model.probe() is Resource.Success) {
            if (health.markUp(now)) log.back("probe", status.since?.let { secondsSince(it, now) })
            return health.status()
        }
        val failures = (health.probe()?.failures ?: 0) + 1
        health.saveProbe(ProbeState(failures, now.plus(HealthSchedule.backoff(failures))))
        log.stillDown(failures, HealthSchedule.backoff(failures).seconds)
        return status
    }

    private suspend fun announce(status: AshaStatus): Int {
        val told = health.takeAffected().filter { health.claimNotice(it, noticeWindow) }
        if (told.isEmpty()) return 0
        frames.send(told, status)
        log.told(told.size)
        return told.size
    }

    private fun secondsSince(from: Instant, to: Instant) = Duration.between(from, to).seconds
}
