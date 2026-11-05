package com.circaso.infrastructure.api

/**
 * One pass over a module's hot paths, run at start-up before `/health/ready` says ready, so the first real request
 * after a deploy doesn't pay for class loading, the JIT, each pooled connection's catalog cache and the first presign.
 * A module binds its own in its Koin module (`single<WarmUp>(named("<module>.warm-up"))`); the app collects them all.
 *
 * [run] is called many times, concurrently (about one caller per pooled app connection), within a time budget. It must
 * be read-only and harmless: reads for real or random ids, never a write to the database, never a vendor call, and in
 * Redis only scratch keys with a short TTL. It may throw; the runner logs the first failure and stops calling it.
 */
interface WarmUp {
    /** Short and stable, for example `messaging`; it names the warm-up in the start-up log. */
    val name: String

    suspend fun run()
}
