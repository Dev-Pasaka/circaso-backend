package com.circaso.infrastructure.api

import java.time.Duration

/**
 * A module's scheduled job, bound in its Koin module like an `EventSubscription`: infrastructure registers every bound
 * one with [Scheduler.every] when it starts, so one node at a time runs it, on the jobs pool. [name] is
 * `<module>.<job>` (`identity.purge-expired`); [job] returns how many items it processed, for the log line.
 */
data class ScheduledJob(val name: String, val interval: Duration, val job: suspend () -> Int)

/**
 * Plain scheduled jobs (pending-account sweep, stuck-transaction checks, audits). Every node runs the
 * scheduler, but a job's Redis lock lets one node at a time run it, so two containers never double-run a job.
 */
interface Scheduler {
    /**
     * Runs [job] every [interval] on one node at a time. [name] is `<module>.<job>` and the lock's name.
     * The job returns how many items it processed, for the log line.
     */
    fun every(
        name: String,
        interval: Duration,
        job: suspend () -> Int,
    )

    /** Runs [block] only if no other node holds the lock [name]; null means another node does. */
    suspend fun <T> withLock(
        name: String,
        lease: Duration,
        block: suspend () -> T,
    ): T?
}
