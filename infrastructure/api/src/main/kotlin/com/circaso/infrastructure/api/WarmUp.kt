package com.circaso.infrastructure.api

import kotlin.coroutines.CoroutineContext

/**
 * One pass over a module's hot paths, run at start-up before `/health/ready` says ready, so the first real request
 * after a deploy doesn't pay for class loading, the JIT, each pooled connection's catalog cache and the first presign.
 * A module binds its own in its Koin module (`single<WarmUp>(named("<module>.warm-up"))`); the app collects them all.
 *
 * [run] is called many times, concurrently (about one caller per CPU), within a time budget. It must be read-only and
 * harmless: reads for real or random ids, never a write to the database, and in Redis only scratch keys with a short
 * TTL. It may throw; the runner logs the first failure and stops calling it.
 *
 * A warm-up that is [once] runs a single time, before the passes, and never at the same time as another of its kind.
 * Only such a warm-up may call a vendor, and only with one call that changes nothing and needs no user (a `ping`),
 * to open the vendor's connection and warm its TLS and JSON paths.
 */
interface WarmUp {
    /** Short and stable, for example `messaging`; it names the warm-up in the start-up log. */
    val name: String

    /** True for a warm-up that runs a single time (see the interface). */
    val once: Boolean get() = false

    suspend fun run()
}

/**
 * In the coroutine context of every warm-up call. Its calls queue behind each other on a CPU that is busy compiling,
 * so infrastructure writes their "slow transaction" and "slow command" lines at DEBUG rather than WARN: during the
 * warm-up they say nothing about the database or Redis, and they would bury a real problem.
 */
object WarmingUp : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    object Key : CoroutineContext.Key<WarmingUp>
}
