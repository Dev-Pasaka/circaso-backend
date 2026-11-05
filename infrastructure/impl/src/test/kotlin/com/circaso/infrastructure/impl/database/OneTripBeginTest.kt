package com.circaso.infrastructure.impl.database

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import com.circaso.infrastructure.impl.settings
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventEnvelope
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class Touched(val n: Int) : DomainEvent {
    override val type: String get() = "pgrelay.touched"
}

/**
 * The round trips of one `Database.tx`: the begin, the role and the durability go in one simple query, the borrow
 * sends nothing, and the role and durability still hold exactly as before, and end with the transaction.
 */
class OneTripBeginTest {
    private val spec = DatabaseModuleSpec("pgtx", emptyList(), "classpath:db/pgtx")
    private val relay = DatabaseModuleSpec("pgrelay", emptyList(), "classpath:db/pgrelay", outbox = true)

    // One connection, so every transaction of a test runs on the same one and sees what the last one left behind.
    private val pools = PostgresPools(TestPostgres.settings(mapOf("DATABASE_POOL_APP_SIZE" to "1")))
    private val database = ModuleDatabase("pgtx", pools)
    private val queries = ListAppender<ILoggingEvent>().apply { start() }
    private val queryLog = LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as Logger

    @BeforeTest
    fun setUp() {
        PostgresMigrator(TestPostgres.settings()).migrate(listOf(spec, relay))
        TestPostgres.truncate("pgrelay")
        runBlocking { pools.open() } // warmed, so what is counted is the transaction alone
        queryLog.addAppender(queries)
        queryLog.level = Level.DEBUG
    }

    @AfterTest
    fun close() {
        queryLog.detachAppender(queries)
        queryLog.level = null
        pools.close()
    }

    /** What the driver sent to the server, one entry per round trip, since [block] started. */
    private suspend fun sent(block: suspend () -> Unit): List<String> {
        queries.list.clear()
        block()
        return queries.list.map { it.formattedMessage.substringAfter("Executing query: ") }
    }

    private suspend fun R2dbcTransaction.setting(name: String): String =
        query("SELECT current_setting('$name')") { it.text(0) }.single()

    /** Borrows the pool's one connection outside any transaction and asks who it is (fails in a failed block). */
    private suspend fun loginOfTheIdleConnection(): String {
        val connection =
            pools.app.connections
                .create()
                .awaitFirst()
        return try {
            connection
                .createStatement("SELECT current_user::text")
                .execute()
                .awaitFirst()
                .map { row, _ -> row.get(0, String::class.java)!! }
                .awaitFirst()
        } finally {
            connection.close().awaitFirstOrNull()
        }
    }

    @Test
    fun `a one-statement transaction is three round trips - the begin with its role, the statement, the commit`() =
        runBlocking<Unit> {
            val sent = sent { database.tx("test") { query("SELECT current_user::text") { it.text(0) } } }

            assertEquals(
                listOf(
                    "BEGIN ISOLATION LEVEL READ COMMITTED, READ WRITE; SET LOCAL ROLE \"pgtx\"",
                    "SELECT current_user::text",
                    "COMMIT",
                ),
                sent,
                "no validation query on the borrow, no separate SET LOCAL ROLE",
            )
        }

    @Test
    fun `the role still holds for the whole transaction and ends with it`() =
        runBlocking<Unit> {
            assertEquals("pgtx", database.tx("test") { query("SELECT current_user::text") { it.text(0) }.single() })
            assertEquals("read committed", database.tx("test") { setting("transaction_isolation") })

            assertEquals("circaso_app", loginOfTheIdleConnection(), "the connection went back to the pool as the login")
        }

    @Test
    fun `a relaxed transaction commits without waiting for the flush, in the same round trip, and only itself`() =
        runBlocking<Unit> {
            var inside = ""
            val sent =
                sent { database.tx("presence.touch", durable = false) { inside = setting("synchronous_commit") } }

            assertEquals("off", inside)
            assertEquals(
                "BEGIN ISOLATION LEVEL READ COMMITTED, READ WRITE; SET LOCAL ROLE \"pgtx\"; " +
                    "SET LOCAL synchronous_commit = off",
                sent.first(),
            )
            assertEquals(3, sent.size, sent.toString())
            assertEquals("on", database.tx("test") { setting("synchronous_commit") }, "the next transaction is durable")
        }

    @Test
    fun `a durable call that joins a relaxed transaction makes all of it durable, and a relaxed one stays durable`() =
        runBlocking<Unit> {
            val seen =
                database.tx("outer", durable = false) {
                    val before = setting("synchronous_commit")
                    val joined = database.tx("inner") { setting("synchronous_commit") }
                    listOf(before, joined, setting("synchronous_commit"))
                }
            assertEquals(listOf("off", "on", "on"), seen)

            val relaxedInDurable =
                database.tx(
                    "outer",
                ) { database.tx("inner", durable = false) { setting("synchronous_commit") } }
            assertEquals("on", relaxedInDurable)
        }

    @Test
    fun `an outbox write is never relaxed`() =
        runBlocking<Unit> {
            val module = ModuleDatabase("pgrelay", pools)
            val envelope = EventEnvelope.of(Touched(1), Touched.serializer())

            val during =
                module.tx("test.write", durable = false) {
                    module.writeOutbox(envelope)
                    setting("synchronous_commit")
                }

            assertEquals("on", during)
        }

    @Test
    fun `a role that can't be taken fails the transaction and leaves the connection clean`() =
        runBlocking<Unit> {
            // A valid schema name with no role behind it: the SET in the begin fails, after the BEGIN went through.
            val refused =
                assertFailsWith<DatabaseException> {
                    ModuleDatabase("pgnorole", pools).tx("test") { exec("SELECT 1") }
                }

            assertEquals("22023", refused.sqlState, "invalid_parameter_value: the role does not exist")
            // The pool has one connection: both of these get the same one back.
            assertEquals("circaso_app", loginOfTheIdleConnection(), "rolled back, so no failed block is left open")
            assertEquals("pgtx", database.tx("test") { query("SELECT current_user::text") { it.text(0) }.single() })
        }

    @Test
    fun `the begin is built from the definition, with the role quoted and checked`() {
        assertEquals(
            "BEGIN ISOLATION LEVEL SERIALIZABLE, READ ONLY; SET LOCAL ROLE \"wallet\"",
            ModuleTransaction("wallet", true, IsolationLevel.SERIALIZABLE, true).beginSql(),
        )
        assertEquals(
            "BEGIN; SET LOCAL ROLE \"presence\"; SET LOCAL synchronous_commit = off",
            ModuleTransaction("presence", false, null, null).beginSql(),
        )
        assertFailsWith<IllegalArgumentException> { ModuleTransaction("x\"; DROP", true, null, null) }
        assertTrue(ModuleTransaction("pgtx", true, null, null).waitNanos < 0, "no borrow recorded yet")
    }
}
