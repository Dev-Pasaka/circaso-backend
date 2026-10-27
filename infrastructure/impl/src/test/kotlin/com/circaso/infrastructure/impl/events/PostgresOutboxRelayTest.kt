package com.circaso.infrastructure.impl.events

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.impl.TestPostgres
import com.circaso.infrastructure.impl.TestRedis
import com.circaso.infrastructure.impl.database.ModuleDatabase
import com.circaso.infrastructure.impl.database.PostgresPools
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventEnvelope
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.Range
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
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
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Serializable
private data class Noted(val n: Int) : DomainEvent {
    override val type: String get() = "pgrelay.Noted"
}

/** A module's outbox table, as each module declares its own in `data/tables`. */
private object RelayOutboxTable : Table("pgrelay.outbox") {
    val eventId = javaUUID("event_id")
    val occurredAt = timestamp("occurred_at")
    val type = text("type")
    val payload = text("payload")
    val publishedAt = timestamp("published_at").nullable()
    override val primaryKey = PrimaryKey(eventId, occurredAt)
}

/** The store a module's `data` layer writes over its outbox: the relay's query, then the stamp. */
private class RelayOutboxStore(private val db: Database) : OutboxStore {
    override val producer = "pgrelay"

    override suspend fun unpublished(limit: Int): List<EventEnvelope> =
        db.tx("outbox.unpublished") {
            RelayOutboxTable
                .selectAll()
                .where {
                    RelayOutboxTable.publishedAt.isNull() and
                        (RelayOutboxTable.occurredAt less TenSecondsAgo)
                }.orderBy(RelayOutboxTable.occurredAt to SortOrder.ASC)
                .limit(limit)
                .toList()
                .map { EventEnvelope.fromJson(it[RelayOutboxTable.payload]) }
        }

    override suspend fun markPublished(envelope: EventEnvelope, at: Instant) {
        db.tx("outbox.stamp") {
            RelayOutboxTable.update({
                (RelayOutboxTable.eventId eq envelope.eventId.value) and
                    (RelayOutboxTable.occurredAt eq envelope.occurredAt)
            }) { it[publishedAt] = at }
        }
    }
}

/** The relay against a real PostgreSQL outbox, read and stamped over R2DBC: `published_at IS NULL AND occurred_at < now() - 10 s`. */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class PostgresOutboxRelayTest {
    private val spec = DatabaseModuleSpec("pgrelay", listOf(RelayOutboxTable), "classpath:db/pgrelay")
    private val pools = PostgresPools(TestPostgres.settings())
    private val db = ModuleDatabase("pgrelay", pools)
    private val store = RelayOutboxStore(db)
    private val redis = TestRedis.connect()
    private val bus = RedisEventBus(redis, { listOf(store) })

    @BeforeTest
    fun migrate() {
        PostgresMigrator(TestPostgres.settings()).migrate(listOf(spec))
        TestPostgres.truncate("pgrelay")
        runBlocking { redis.commands.del("events:pgrelay") }
    }

    @AfterTest
    fun close() {
        bus.close()
        redis.close()
        pools.close()
    }

    private suspend fun write(
        n: Int,
        ago: Duration,
        publishedAt: Instant? = null,
    ): EventEnvelope {
        val envelope = EventEnvelope.of(Noted(n), Noted.serializer(), occurredAt = Instant.now().minus(ago))
        db.tx("outbox.write") {
            RelayOutboxTable.insert {
                it[eventId] = envelope.eventId.value
                it[occurredAt] = envelope.occurredAt
                it[type] = envelope.type
                it[payload] = envelope.toJson()
                it[RelayOutboxTable.publishedAt] = publishedAt
            }
        }
        return envelope
    }

    private suspend fun publishedAt(envelope: EventEnvelope): Instant? =
        db.tx {
            RelayOutboxTable
                .selectAll()
                .where { RelayOutboxTable.eventId eq envelope.eventId.value }
                .single()[RelayOutboxTable.publishedAt]
        }

    @Test
    fun `the relay publishes the rows a crashed node left, oldest first, once, and leaves the fresh ones`() =
        runBlocking<Unit> {
            val older = write(1, Duration.ofMinutes(3))
            val stuck = write(2, Duration.ofMinutes(2))
            val sent = write(3, Duration.ofMinutes(2), publishedAt = Instant.now().minusSeconds(100))
            val fresh = write(4, Duration.ofSeconds(1)) // the normal publish path still owns it
            val relay = OutboxRelay(bus, { listOf(store) })

            val first = relay.sweep()
            val second = relay.sweep()

            assertEquals(2, first)
            assertEquals(0, second, "a stamped row is no longer unpublished")
            val onStream = redis.commands.xrange("events:pgrelay", Range.create("-", "+")).toList()
            assertEquals(
                listOf(older.eventId, stuck.eventId),
                onStream.map { EventEnvelope.fromJson(it.body.values.single()).eventId },
            )
            assertNotNull(publishedAt(older))
            assertNotNull(publishedAt(stuck))
            assertNull(publishedAt(fresh))
            assertNotNull(publishedAt(sent))
        }

    @Test
    fun `the unpublished query is served by the partial index`() =
        runBlocking<Unit> {
            val plan =
                TestPostgres.superuser { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SET enable_seqscan = off") // a tiny table would be scanned otherwise
                        statement
                            .executeQuery(
                                "EXPLAIN SELECT * FROM pgrelay.outbox WHERE published_at IS NULL " +
                                    "AND occurred_at < now() - interval '10 seconds' ORDER BY occurred_at LIMIT 500",
                            ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                    }
                }

            // Each day's partition carries its own copy of the partial index, named after the column.
            val text = plan.joinToString("\n")
            assertEquals(true, "Index Scan using outbox_p" in text && "occurred_at_idx" in text, text)
            assertEquals(false, "Seq Scan" in text, text)
        }
}

/** `now() - interval '10 seconds'`, on the database's clock, as the relay's query has it. */
private object TenSecondsAgo : Expression<Instant>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("now() - interval '10 seconds'")
    }
}
