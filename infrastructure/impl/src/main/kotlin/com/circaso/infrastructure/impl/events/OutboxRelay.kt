package com.circaso.infrastructure.impl.events

import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.api.Scheduler
import com.circaso.infrastructure.impl.database.OnJobsPool
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.shared.EventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.events.OutboxRelay")

/**
 * The rescue path: publishes every outbox row that has no `publishedAt`, for the case where a node crashed between
 * its commit and its `XADD`. It runs under the scheduler's Redis lock, so one node at a time asks each module's
 * [OutboxStore] for its unpublished rows older than 10 seconds, oldest first, in batches of [OutboxStore.BATCH].
 * A rescued event may be a duplicate of one that was published but not stamped; handlers skip those.
 *
 * There is no checkpoint: an unpublished row stays visible to the store's query until it is stamped.
 */
class OutboxRelay(
    private val bus: RedisEventBus,
    private val outboxStores: () -> Collection<OutboxStore>,
    /** Batches per store per sweep, so one sweep can't run for ever behind a stuck stamp. */
    private val maxBatches: Int = 20,
) {
    fun register(scheduler: Scheduler, interval: Duration = Duration.ofSeconds(5)) {
        scheduler.every("events.outbox-relay", interval) { sweep() }
    }

    /**
     * One pass over every module's outbox, on the jobs pool. Returns how many events it published. A module whose
     * outbox fails is logged and left for the next sweep; the others are still relayed.
     */
    suspend fun sweep(): Int = withContext(OnJobsPool) { outboxStores().sumOf { sweepOnItsOwn(it) } }

    private suspend fun sweepOnItsOwn(store: OutboxStore): Int =
        try {
            sweep(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The exception's class only: a driver's message can quote a row.
            log.error("outbox sweep failed", kv("producer", store.producer), kv("error", e.javaClass.simpleName))
            0
        }

    private suspend fun sweep(store: OutboxStore): Int {
        val published = mutableSetOf<EventId>()
        for (round in 1..maxBatches) {
            val page = store.page(OutboxStore.BATCH)
            val fresh = page.events.filter { it.eventId !in published }
            for (envelope in fresh) {
                if (!bus.appendToStream(envelope)) return done(store, published.size) // Redis is down: next sweep
                bus.stampOutbox(envelope)
                published += envelope.eventId
            }
            // A short read was the last one (counted in rows read, so a skipped poison row doesn't end the sweep); a
            // batch of rows already sent means their stamps failed.
            if (!page.full || fresh.isEmpty()) break
        }
        return done(store, published.size)
    }

    private fun done(store: OutboxStore, published: Int): Int {
        bus.noteRescued(store.producer, published.toLong())
        if (published > 0) {
            log.warn("rescued unpublished events", kv("producer", store.producer), kv("events", published))
        }
        return published
    }
}
