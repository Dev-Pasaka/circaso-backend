package com.circaso.infrastructure.impl.events

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.TestRedis
import com.circaso.infrastructure.impl.database.PostgresDatabases
import com.circaso.infrastructure.impl.database.PostgresOutboxes
import com.circaso.infrastructure.impl.database.PostgresPools
import com.circaso.infrastructure.impl.database.list
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import com.circaso.infrastructure.impl.database.single
import com.circaso.infrastructure.impl.settings
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventEnvelope
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.Range
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Serializable
private data class Noted(val n: Int) : DomainEvent {
    override val type: String get() = "pgrelay.Noted"
}

/**
 * The shared PostgreSQL outbox (decision 2026-10-27, PG1 review): a module writes with `Database.writeOutbox` inside its
 * transaction, and the relay reads it through the store infrastructure registers for a spec with `outbox = true`:
 * `published_at IS NULL AND occurred_at < now() - 10 s`, served by the partial index.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class PostgresOutboxRelayTest {
    private val spec = DatabaseModuleSpec("pgrelay", emptyList(), "classpath:db/pgrelay", outbox = true)
    private val pools = PostgresPools(TestPostgres.settings())
    private val databases = PostgresDatabases(pools)
    private val module = databases.scope("pgrelay")
    private val store = PostgresOutboxes(databases) { listOf(spec) }.stores.single()
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

    private suspend fun write(n: Int, ago: Duration): EventEnvelope {
        val envelope = EventEnvelope.of(Noted(n), Noted.serializer(), occurredAt = Instant.now().minus(ago))
        module.tx("test.write") { module.writeOutbox(envelope) }
        return envelope
    }

    private fun publishedAt(envelope: EventEnvelope): Instant? =
        TestPostgres.superuser { connection ->
            connection
                .prepareStatement("SELECT published_at FROM pgrelay.outbox WHERE event_id = ?::uuid")
                .use { statement ->
                    statement.setString(1, envelope.eventId.value.toString())
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getTimestamp(1)?.toInstant()
                    }
                }
        }

    @Test
    fun `the relay publishes the rows a crashed node left, oldest first, once, and leaves the fresh ones`() =
        runBlocking<Unit> {
            val older = write(1, Duration.ofMinutes(3))
            val stuck = write(2, Duration.ofMinutes(2))
            val sent = write(3, Duration.ofMinutes(2))
            store.markPublished(sent, Instant.now().minusSeconds(100))
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
        }

    @Test
    fun `the store belongs to the module, named after its schema, and only for a spec that asks for one`() {
        assertEquals("pgrelay", store.producer)
        val quiet = DatabaseModuleSpec("pgquiet", emptyList(), "classpath:db/pgquiet")
        assertEquals(emptyList(), PostgresOutboxes(databases) { listOf(quiet) }.stores)
    }

    @Test
    fun `writing the outbox needs the transaction that makes the change`() =
        runBlocking<Unit> {
            val envelope = EventEnvelope.of(Noted(1), Noted.serializer())

            assertFailsWith<IllegalStateException> { module.writeOutbox(envelope) }
        }

    @Test
    fun `the shared DDL has the shape the store reads and writes, with its partial index`() {
        // The module's V1 script carries the snippet from architecture.md → Durable events; this pins both together.
        val columns =
            TestPostgres.superuser {
                it.list(
                    "SELECT column_name || ' ' || data_type || ' ' || is_nullable FROM information_schema.columns " +
                        "WHERE table_schema = 'pgrelay' AND table_name = 'outbox' ORDER BY ordinal_position",
                )
            }
        assertEquals(
            listOf(
                "event_id uuid NO",
                "occurred_at timestamp with time zone NO",
                "type text NO",
                "payload text NO",
                "published_at timestamp with time zone YES",
            ),
            columns,
        )
        TestPostgres.superuser {
            assertEquals(
                "p",
                it.single("SELECT relkind::text FROM pg_class WHERE oid = 'pgrelay.outbox'::regclass"),
                "partitioned",
            )
            assertEquals(
                "CREATE INDEX outbox_unpublished_idx ON ONLY pgrelay.outbox USING btree (occurred_at) " +
                    "WHERE (published_at IS NULL)",
                it.single(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = 'pgrelay' AND indexname LIKE '%unpublished%'",
                ),
            )
        }
    }

    @Test
    fun `the unpublished query is served by the partial index`() =
        runBlocking<Unit> {
            write(1, Duration.ofMinutes(1))
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
