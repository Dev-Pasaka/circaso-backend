package com.circaso.infrastructure.impl.events

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.OutboxStore
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
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** A row whose payload no longer decodes, as a bad deploy or a hand edit could leave one. */
    private fun writePoison(ago: Duration, content: String): UUID {
        val eventId = UUID.randomUUID()
        TestPostgres.superuser { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO pgrelay.outbox (event_id, occurred_at, type, payload) VALUES (?, ?, 'pgrelay.Noted', ?)",
                ).use { statement ->
                    statement.setObject(1, eventId)
                    statement.setTimestamp(2, Timestamp.from(Instant.now().minus(ago)))
                    statement.setString(3, """{"eventId":"$content""")
                    statement.executeUpdate()
                }
        }
        return eventId
    }

    @Test
    fun `a row that no longer decodes is skipped and logged once, and the rows behind it still flow`() =
        runBlocking<Unit> {
            val logs = ListAppender<ILoggingEvent>().apply { start() }
            val outboxLog = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Outbox") as Logger
            outboxLog.addAppender(logs)
            try {
                val poison = writePoison(Duration.ofMinutes(5), "marker_poison_0712345678")
                val behind = write(1, Duration.ofMinutes(4))
                val relay = OutboxRelay(bus, { listOf(store) })

                val first = relay.sweep()
                val later = write(2, Duration.ofMinutes(1))
                val second = relay.sweep()
                val third = relay.sweep()

                assertEquals(listOf(1, 1, 0), listOf(first, second, third))
                assertNotNull(publishedAt(behind))
                assertNotNull(publishedAt(later))
                val onStream = redis.commands.xrange("events:pgrelay", Range.create("-", "+")).toList()
                assertEquals(
                    listOf(behind.eventId, later.eventId),
                    onStream.map { EventEnvelope.fromJson(it.body.values.single()).eventId },
                )
                val errors = logs.list.filter { it.level == Level.ERROR }
                assertEquals(1, errors.size, "logged once, however many sweeps see it")
                val line = errors.single().let { it.formattedMessage + it.argumentArray.orEmpty().joinToString() }
                assertTrue(poison.toString() in line && "pgrelay" in line, line)
                assertFalse("marker_poison" in line || "0712345678" in line, "never the row's content: $line")
                assertNull(errors.single().throwableProxy, "nor an exception that could quote it")
                assertEquals(
                    "",
                    TestPostgres.superuser {
                        it.single(
                            "SELECT coalesce(published_at::text, '') FROM pgrelay.outbox WHERE event_id = '$poison'",
                        )
                    },
                    "the poison row is left unstamped, for an operator to look at",
                )
            } finally {
                outboxLog.detachAppender(logs)
            }
        }

    @Test
    fun `a module whose outbox fails does not hold back the others`() =
        runBlocking<Unit> {
            val stuck = write(1, Duration.ofMinutes(2))
            val broken =
                object : OutboxStore {
                    override val producer = "broken"

                    override suspend fun unpublished(limit: Int): List<EventEnvelope> = error("the database is gone")

                    override suspend fun markPublished(envelope: EventEnvelope, at: Instant) = Unit
                }

            assertEquals(1, OutboxRelay(bus, { listOf(broken, store) }).sweep())
            assertNotNull(publishedAt(stuck))
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
