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

/**
 * Work the **app's** processes do once as they start (2026-11-24), after the database and Redis are up and before the
 * scheduler starts its [ScheduledJob]s: `startInfrastructure` runs every one in turn in the background, each within a
 * short time budget, then starts the scheduler; one that fails or runs out of time is logged and the rest go on. The ramp restores its merchants' trading
 * sessions with one. The ops process never runs these.
 */
data class StartupJob(val name: String, val job: suspend () -> Unit)
