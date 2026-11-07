package com.circaso.assistant.data.metrics

import com.circaso.assistant.domain.ports.HealthLog
import org.slf4j.LoggerFactory

/** Asha's health in the log (decision 2026-11-07): WARN while she is down, INFO when she is back. */
class LoggedHealthLog : HealthLog {
    private val log = LoggerFactory.getLogger("com.circaso.assistant.health")

    override fun down(reason: String) = log.warn("asha is down for maintenance: reason={}", reason)

    override fun back(by: String, downSeconds: Long?) = log.info("asha is back: by={} downSeconds={}", by, downSeconds)

    override fun stillDown(failures: Int, nextInSeconds: Long) =
        log.warn("asha is still down: probe {} failed, next in {}s", failures, nextInSeconds)

    override fun told(people: Int) = log.info("asha is back: told {} people", people)

    override fun storeFailed(error: String) = log.warn("asha health store failed: error={}", error)
}
