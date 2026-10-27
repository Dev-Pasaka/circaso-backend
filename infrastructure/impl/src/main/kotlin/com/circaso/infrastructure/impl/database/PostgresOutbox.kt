package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.OutboxStore
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
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant

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

/** The relay's view of one module's PostgreSQL outbox, run as the module's role (on the jobs pool, from the relay). */
internal class PostgresOutboxStore(private val database: Database) : OutboxStore {
    private val outbox = OutboxTable(database.schema)

    override val producer: String = database.schema

    override suspend fun unpublished(limit: Int): List<EventEnvelope> =
        database.tx("outbox.unpublished") {
            outbox
                .selectAll()
                .where { outbox.publishedAt.isNull() and (outbox.occurredAt less TenSecondsAgo) }
                .orderBy(outbox.occurredAt to SortOrder.ASC)
                .limit(limit)
                .toList()
                .map { EventEnvelope.fromJson(it[outbox.payload]) }
        }

    override suspend fun markPublished(envelope: EventEnvelope, at: Instant) {
        database.tx("outbox.stamp") {
            outbox.update({
                (outbox.eventId eq envelope.eventId.value) and (outbox.occurredAt eq envelope.occurredAt)
            }) { it[publishedAt] = at }
        }
    }
}

/** One [OutboxStore] per module spec with `outbox = true`; the event runtime adds them to the modules' own. */
class PostgresOutboxes(private val databases: Databases, private val specs: () -> Collection<DatabaseModuleSpec>) {
    val stores: List<OutboxStore> by lazy {
        specs().filter { it.outbox }.map { PostgresOutboxStore(databases.scope(it.schema)) }
    }
}
