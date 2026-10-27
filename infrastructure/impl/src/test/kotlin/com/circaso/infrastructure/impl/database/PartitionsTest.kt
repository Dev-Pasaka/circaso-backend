package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import com.circaso.infrastructure.impl.settings
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The purge-and-partition helper the modules' `<module>.purge-expired` jobs call. */
class PartitionsTest {
    private val spec = DatabaseModuleSpec("pgpart", emptyList(), "classpath:db/pgpart")
    private val pools = PostgresPools(TestPostgres.settings())

    private fun database(at: Instant) = ModuleDatabase("pgpart", pools, Clock.fixed(at, ZoneOffset.UTC))

    private fun day(date: String): Instant = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC)

    @BeforeTest
    fun migrate() {
        PostgresMigrator(TestPostgres.settings()).migrate(listOf(spec))
        TestPostgres.superuser { db ->
            // Start every test with no partitions at all, whatever Migrate made for today.
            db
                .list(
                    "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid " +
                        "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'pgpart' AND c.relkind = 'r'",
                ).forEach { db.createStatement().use { s -> s.execute("DROP TABLE pgpart.\"$it\"") } }
        }
        TestPostgres.truncate("pgpart")
    }

    @AfterTest
    fun close() = pools.close()

    private fun partitionsOf(table: String): List<String> =
        TestPostgres.superuser { db ->
            db.list(
                "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid " +
                    "WHERE i.inhparent = 'pgpart.$table'::regclass ORDER BY 1",
            )
        }

    @Test
    fun `today's and tomorrow's partitions are created once, owned by the module`() =
        runBlocking<Unit> {
            val db = database(day("2026-10-27").plusSeconds(3_600))

            val first = db.maintainDailyPartitions("events", Duration.ofDays(7))
            val second = db.maintainDailyPartitions("events", Duration.ofDays(7))

            assertEquals(listOf("events_p20261027", "events_p20261028"), first.created)
            assertEquals(0, second.count, "nothing left to do")
            assertEquals(listOf("events_p20261027", "events_p20261028"), partitionsOf("events"))
            TestPostgres.superuser { connection ->
                val owner = connection.single("SELECT tableowner FROM pg_tables WHERE tablename = 'events_p20261027'")
                assertEquals("pgpart", owner)
            }
        }

    @Test
    fun `a time-partitioned row lands in its day, and partitions past the retention are dropped`() =
        runBlocking<Unit> {
            database(day("2026-10-01")).maintainDailyPartitions("events", Duration.ofDays(7))
            database(day("2026-10-05")).maintainDailyPartitions("events", Duration.ofDays(7))
            TestPostgres.superuser { db ->
                db.createStatement().use {
                    it.execute("INSERT INTO pgpart.events VALUES ('${UUID.randomUUID()}', '2026-10-01T12:00:00Z')")
                }
                assertEquals(
                    "pgpart.events_p20261001",
                    db.single("SELECT tableoid::regclass::text FROM pgpart.events"),
                )
            }

            // On the 9th, the 1st ended 7 days and a bit ago; the 2nd ended exactly 7 days ago; the 5th is kept.
            val changes = database(day("2026-10-10")).maintainDailyPartitions("events", Duration.ofDays(7))

            assertEquals(listOf("events_p20261001", "events_p20261002"), changes.dropped.sorted())
            assertEquals(listOf("events_p20261010", "events_p20261011"), changes.created)
            assertEquals(
                listOf("events_p20261005", "events_p20261006", "events_p20261010", "events_p20261011"),
                partitionsOf("events"),
            )
        }

    @Test
    fun `a UUIDv7 partition holds exactly the ids made that day, bounded at midnight UTC`() =
        runBlocking<Unit> {
            val midnight = day("2026-10-27")
            database(midnight).maintainDailyPartitions("messages", Duration.ofDays(30))
            val first = Uuid7.generate(midnight) // the first id of the day
            val last = Uuid7.generate(midnight.plus(Duration.ofDays(1)).minusMillis(1)) // and the last one
            val tomorrow = Uuid7.generate(midnight.plus(Duration.ofDays(1)))

            TestPostgres.superuser { db ->
                db.createStatement().use {
                    it.execute("INSERT INTO pgpart.messages VALUES ('$first'), ('$last'), ('$tomorrow')")
                }
                assertEquals(
                    listOf("pgpart.messages_p20261027", "pgpart.messages_p20261027", "pgpart.messages_p20261028"),
                    db.list("SELECT tableoid::regclass::text FROM pgpart.messages ORDER BY message_id"),
                )
            }
            assertEquals("01a17aa8-1c00-7000-8000-000000000000", Partitions.lowestUuid7(midnight))
        }

    private fun session(expiresAt: String) = "INSERT INTO pgpart.sessions VALUES ('${UUID.randomUUID()}', $expiresAt)"

    @Test
    fun `the purge deletes expired rows in chunks and leaves the rest`() =
        runBlocking<Unit> {
            TestPostgres.superuser { db ->
                db.createStatement().use { statement ->
                    repeat(5) { statement.execute(session("now() - interval '1 hour'")) }
                    statement.execute(session("now() + interval '1 day'"))
                }
            }

            val purged = database(Instant.now()).purgeExpired("sessions", chunk = 2)

            assertEquals(5, purged)
            TestPostgres.superuser { db -> assertEquals("1", db.single("SELECT count(*) FROM pgpart.sessions")) }
            assertEquals(0, database(Instant.now()).purgeExpired("sessions", chunk = 2), "nothing left to purge")
        }

    @Test
    fun `the helpers run on the jobs pool, as the module's role`() =
        runBlocking<Unit> {
            database(Instant.now()).purgeExpired("sessions")

            assertTrue((pools.jobs.metrics?.allocatedSize() ?: 0) >= 1, "the jobs pool was used")
        }
}
