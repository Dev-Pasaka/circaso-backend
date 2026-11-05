package com.circaso.infrastructure.impl.database

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import com.circaso.infrastructure.impl.settings
import com.circaso.infrastructure.testing.TestPostgres
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pools make their connections off the request path: full from the start, kept full in the background when a
 * connection retires, and the `db.tx` line says whether a slow transaction waited for the pool or for the database.
 */
class PoolMaintenanceTest {
    private val opened = mutableListOf<PostgresPools>()

    private fun pools(
        extra: Map<String, String> = emptyMap(),
        slowAfter: Duration? = null,
        name: String? = null,
    ): PostgresPools {
        val settings = TestPostgres.settings(extra)
        val app = settings.app.let { app -> app.copy(slowAfter = slowAfter ?: app.slowAfter, name = name ?: app.name) }
        return PostgresPools(settings, appSettings = app).also { opened += it }
    }

    @AfterTest
    fun close() = opened.forEach { it.close() }

    /** The backend pids of one pool's connections (its `application_name`), as the server sees them, borrowing none. */
    private fun connectionsOf(pool: String): Set<Int> =
        TestPostgres.superuser { connection ->
            val sql = "SELECT pid FROM pg_stat_activity WHERE application_name = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, pool)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getInt(1) else null }.toSet()
                }
            }
        }

    @Test
    fun `open fills both pools to their size before the first transaction`() =
        runBlocking<Unit> {
            val pools = pools()

            pools.open()

            assertEquals(10, pools.app.metrics?.allocatedSize(), "the app pool is full: 10 by default")
            assertEquals(10, pools.app.metrics?.idleSize())
            assertEquals(3, pools.jobs.metrics?.allocatedSize(), "the jobs pool too: 3")
        }

    @Test
    fun `a retired connection is replaced in the background, before any transaction asks for one`() =
        runBlocking<Unit> {
            val pools =
                pools(
                    mapOf(
                        "DATABASE_POOL_APP_SIZE" to "2",
                        "DATABASE_POOL_MAX_LIFE_MS" to "1000",
                        "DATABASE_POOL_MAINTENANCE_MS" to "100",
                    ),
                    name = "circaso-app-retiring",
                )
            pools.open()
            val first = connectionsOf("circaso-app-retiring")
            assertEquals(2, first.size, "$first")

            // Nothing borrows a connection from here on: whatever changes, the pool did on its own.
            val deadline = System.currentTimeMillis() + 10_000
            var now = first
            while ((now intersect first).isNotEmpty() || now.size != 2 || pools.app.metrics?.idleSize() != 2) {
                check(System.currentTimeMillis() < deadline) { "never replaced: was $first, now $now" }
                delay(50)
                now = connectionsOf("circaso-app-retiring")
            }

            assertEquals(2, pools.app.metrics?.allocatedSize(), "back to its minimum, all of them new")
        }

    @Test
    fun `each connection retires at its own time, within a fifth of the lifetime`() {
        val retirement = Retirement(PoolTimings(maxLifeTime = Duration.ofMinutes(30)))
        val lives = (1..200).map { retirement.lifetimeMillis(Any()) }
        val life = Duration.ofMinutes(30).toMillis()

        assertTrue(lives.all { it in (life * 0.8).toLong()..life }, "${lives.min()}..${lives.max()}")
        assertTrue(lives.max() - lives.min() > life / 10, "spread out, not one cliff")
    }

    @Test
    fun `a slow transaction says whether it waited for the pool or for the database`() =
        runBlocking<Unit> {
            val pgtx = DatabaseModuleSpec("pgtx", emptyList(), "classpath:db/pgtx")
            PostgresMigrator(TestPostgres.settings()).migrate(listOf(pgtx))
            val pools = pools(mapOf("DATABASE_POOL_APP_SIZE" to "1"), slowAfter = Duration.ofMillis(50))
            val database = ModuleDatabase("pgtx", pools)
            pools.open()
            val logs = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Transactions") as Logger
            logger.addAppender(logs)
            logger.level = Level.DEBUG
            try {
                coroutineScope {
                    val holder = async { database.tx("holds") { exec("SELECT pg_sleep(0.4)") } }
                    while (pools.app.metrics?.acquiredSize() != 1) delay(5)
                    database.tx("waits") { exec("SELECT 1") } // the one connection is busy for about 0.4 s
                    holder.await()
                }
            } finally {
                logger.detachAppender(logs)
                logger.level = null
            }

            fun line(operation: String): Pair<String, Map<String, String>> {
                val event =
                    logs.list.single { event ->
                        event.argumentArray.orEmpty().any { it.toString() == "operation=$operation" }
                    }
                val fields =
                    event.argumentArray.orEmpty().associate {
                        it.toString().substringBefore('=') to it.toString().substringAfter('=')
                    }
                return event.formattedMessage to fields
            }
            val (held, holdFields) = line("holds")
            val (waited, waitFields) = line("waits")

            assertEquals("slow transaction: in the database", held)
            assertEquals("slow transaction: waiting for a pooled connection", waited)
            listOf("kind", "operation", "schema", "pool", "statements", "durationMs", "waitMs", "execMs", "outcome")
                .forEach { assertTrue(it in waitFields, "$it in $waitFields") }
            assertTrue(waitFields.getValue("waitMs").toLong() >= 200, "$waitFields")
            assertTrue(holdFields.getValue("execMs").toLong() >= 350, "$holdFields")
            assertEquals("1", waitFields["statements"], "the role switch is part of the begin, not a statement")
        }
}
