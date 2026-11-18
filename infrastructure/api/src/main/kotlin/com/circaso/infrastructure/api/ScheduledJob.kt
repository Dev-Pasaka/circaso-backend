package com.circaso.infrastructure.api

import java.time.Duration

/**
 * A job a module hands the [Scheduler] by binding it in Koin; `startInfrastructure` registers every one with
 * [Scheduler.every]. [name] is `<module>.<job>` and the lock's name; [job] returns how many items it processed.
 */
data class ScheduledJob(val name: String, val interval: Duration, val job: suspend () -> Int)

/**
 * A job only the **ops** process runs (decision 2026-11-18): work that needs something the app's processes must never
 * hold, such as the signing secret of Circaso's own fee wallets. A module binds it in Koin like a [ScheduledJob];
 * `startOpsInfrastructure` runs it once as the process starts and then every [interval], under the scheduler's lock
 * for [name], so two ops processes never run it at once. The app's processes never collect these.
 */
data class OpsJob(val name: String, val interval: Duration, val job: suspend () -> Int)
