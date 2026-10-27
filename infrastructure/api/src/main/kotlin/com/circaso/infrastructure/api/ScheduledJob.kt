package com.circaso.infrastructure.api

import java.time.Duration

/**
 * A job a module hands the [Scheduler] by binding it in Koin; `startInfrastructure` registers every one with
 * [Scheduler.every]. [name] is `<module>.<job>` and the lock's name; [job] returns how many items it processed.
 */
data class ScheduledJob(val name: String, val interval: Duration, val job: suspend () -> Int)
