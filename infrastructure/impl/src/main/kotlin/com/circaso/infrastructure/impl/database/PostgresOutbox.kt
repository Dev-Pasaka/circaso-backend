package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.OutboxPage
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.shared.EventEnvelope
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Outbox")

/**
 * Every module's outbox has the same shape, so infrastructure owns its code (decision 2026-10-27, PG1 review). The
 * module's V1 script creates the table with the DDL in architecture.md → Durable events (daily partitions on
 * `occurred_at`, the partial index `WHERE published_at IS NULL`), and `OutboxShapeTest` pins this object to it.
 */
internal class OutboxTable(schema: String) : Table("$schema.outbox") {
    val eventId = javaUUID("event_id")
    val occurredAt = timestamp("occurred_at")
    val type = text("type")
    val payload = text("payload")
    val publishedAt = timestamp("published_at").nullable()
    override val primaryKey = PrimaryKey(eventId, occurredAt)

    /** Inside the caller's transaction. */
    suspend fun write(envelope: EventEnvelope) {
        insert {
            it[eventId] = envelope.eventId.value
            it[occurredAt] = envelope.occurredAt
            it[type] = envelope.type
            it[payload] = envelope.toJson()
        }
    }
}

/** `now() - interval '10 seconds'` on the database's clock: the normal publish path owns anything newer. */
private object TenSecondsAgo : Expression<Instant>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("now() - interval '10 seconds'")
    }
}

/**
 * The relay's view of one module's PostgreSQL outbox, run as the module's role (on the jobs pool, from the relay).
 *
 * Each row is decoded on its own. A row whose payload no longer decodes (a poison row) would otherwise fail every
 * sweep and hold back every row behind it, so it is skipped: logged once at ERROR with its event id and producer
 * (never its content, which can hold anything the event carried) and left out of later reads. It keeps no stamp, so
 * it stays visible to an operator until its partition is dropped after 7 days. What is skipped is this node's, in
 * memory: a node that takes over the relay logs each row once more and skips it from then on.
 *
 * At most [maxPoisoned] rows are left out of the query (it binds them all). Past that, one ERROR line says so
 * (`outbox.poison-cap`); each further poison row is still logged once, but is read again by every sweep and takes a
 * place in its window.
 */
internal class PostgresOutboxStore(private val database: Database, private val maxPoisoned: Int = MAX_POISONED) :
    OutboxStore {
    private val outbox = OutboxTable(database.schema)

    /** Left out of every read: at most [maxPoisoned]. */
    private val skipped: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Every poison row this node has logged, so each is logged once. */
    private val logged: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val capReported = AtomicBoolean(false)

    override val producer: String = database.schema

    override suspend fun unpublished(limit: Int): List<EventEnvelope> = page(limit).events

    override suspend fun page(limit: Int): OutboxPage {
        val leftOut = skipped.toList()
        val rows =
            database.tx("outbox.unpublished") {
                outbox
                    .select(outbox.eventId, outbox.payload)
                    .where {
                        var condition = outbox.publishedAt.isNull() and (outbox.occurredAt less TenSecondsAgo)
                        if (leftOut.isNotEmpty()) condition = condition and (outbox.eventId notInList leftOut)
                        condition
                    }.orderBy(outbox.occurredAt to SortOrder.ASC)
                    .limit(limit)
                    .toList()
                    .map { it[outbox.eventId] to it[outbox.payload] }
            }
        val events = rows.mapNotNull { (eventId, payload) -> decode(eventId, payload) }
        return OutboxPage(events, full = rows.size >= limit)
    }

    private fun decode(eventId: UUID, payload: String): EventEnvelope? =
        try {
            EventEnvelope.fromJson(payload)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one; its message can quote the payload, so neither it nor the cause is logged.
            poisoned(eventId)
            null
        }

    private fun poisoned(eventId: UUID) {
        if (!logged.add(eventId)) return
        log.error(
            "undecodable outbox row skipped",
            kv("kind", "outbox.poison"),
            kv("producer", producer),
            kv("eventId", eventId),
        )
        if (skipped.size < maxPoisoned) {
            skipped += eventId
        } else if (capReported.compareAndSet(false, true)) {
            log.error(
                "too many undecodable outbox rows to leave out; the rest are read by every sweep",
                kv("kind", "outbox.poison-cap"),
                kv("producer", producer),
            )
        }
    }

    override suspend fun markPublished(envelope: EventEnvelope, at: Instant) {
        database.tx("outbox.stamp") {
            outbox.update({
                (outbox.eventId eq envelope.eventId.value) and (outbox.occurredAt eq envelope.occurredAt)
            }) { it[publishedAt] = at }
        }
    }

    private companion object {
        /** How many poison rows a node leaves out of its reads; the query binds them all, so the list stays short. */
        const val MAX_POISONED = 1_000
    }
}

/** One [OutboxStore] per module spec with `outbox = true`; the event runtime adds them to the modules' own. */
class PostgresOutboxes(private val databases: Databases, private val specs: () -> Collection<DatabaseModuleSpec>) {
    val stores: List<OutboxStore> by lazy {
        specs().filter { it.outbox }.map { PostgresOutboxStore(databases.scope(it.schema)) }
    }
}
