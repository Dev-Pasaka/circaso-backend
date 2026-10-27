package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.DatabaseException
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import com.circaso.infrastructure.impl.settings
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.DomainException
import com.circaso.shared.SharedError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private object ItemsTable : Table("pgtx.items") {
    val id = javaUUID("id")
    val name = text("name")
    override val primaryKey = PrimaryKey(id)
}

/** `Database.tx` over R2DBC: commit and rollback, the module role, joining, timeouts, and a saturated pool. */
class TransactionsTest {
    private val spec = DatabaseModuleSpec("pgtx", listOf(ItemsTable), "classpath:db/pgtx")
    private val opened = mutableListOf<PostgresPools>()

    private fun pools(extra: Map<String, String> = emptyMap()) =
        PostgresPools(TestPostgres.settings(extra)).also { opened += it }

    private fun database(pools: PostgresPools = pools(), schema: String = "pgtx") = ModuleDatabase(schema, pools)

    @BeforeTest
    fun migrate() {
        PostgresMigrator(TestPostgres.settings()).migrate(listOf(spec))
        TestPostgres.truncate("pgtx")
    }

    @AfterTest
    fun close() = opened.forEach { it.close() }

    private suspend fun R2dbcTransaction.add(name: String): UUID =
        UUID.randomUUID().also { id ->
            ItemsTable.insert {
                it[ItemsTable.id] = id
                it[ItemsTable.name] = name
            }
        }

    private suspend fun count(db: ModuleDatabase): Long = db.tx("test") { ItemsTable.selectAll().count() }

    private suspend fun R2dbcTransaction.current(setting: String): String =
        query("SELECT $setting") { it.text(0) }.single()

    @Test
    fun `a block that returns commits, through the Exposed DSL on a schema-qualified table`() =
        runBlocking<Unit> {
            val db = database()

            val id = db.tx("items.add") { add("first") }

            assertEquals(
                "first",
                db.tx("test") { ItemsTable.selectAll().where { ItemsTable.id eq id }.single()[ItemsTable.name] },
            )
        }

    @Test
    fun `a block that throws rolls back everything it wrote`() =
        runBlocking<Unit> {
            val db = database()

            assertFailsWith<IllegalStateException> {
                db.tx("test") {
                    add("lost")
                    error("the use case changed its mind")
                }
            }

            assertEquals(0L, count(db))
        }

    @Test
    fun `a call inside an open transaction joins it, so one failure rolls back both`() =
        runBlocking<Unit> {
            val db = database()

            assertFailsWith<IllegalStateException> {
                db.tx("use-case") {
                    add("outer")
                    db.tx("repository") { add("inner") } // a repository called within the use case's Transactor.run
                    error("fails after both writes")
                }
            }

            assertEquals(0L, count(db))
        }

    @Test
    fun `a transaction runs as the module's role, and the connection goes back to the pool as the login`() =
        runBlocking<Unit> {
            // One connection, so the borrow after the transaction gets the same one back.
            val pools = pools(mapOf("DATABASE_POOL_APP_SIZE" to "1"))
            val db = database(pools)

            assertEquals("pgtx", db.tx("test") { current("current_user") })
            val connection =
                pools.app.connections
                    .create()
                    .awaitFirst()
            val after =
                try {
                    connection
                        .createStatement("SELECT current_user")
                        .execute()
                        .awaitFirst()
                        .map { row, _ -> row.get(0, String::class.java) }
                        .awaitFirst()
                } finally {
                    connection.close().awaitFirstOrNull()
                }

            assertEquals("circaso_app", after, "SET LOCAL ROLE ended with the transaction")
        }

    @Test
    fun `the module's role can't read another module's schema`() =
        runBlocking<Unit> {
            val pgpart = DatabaseModuleSpec("pgpart", emptyList(), "classpath:db/pgpart")
            PostgresMigrator(TestPostgres.settings()).migrate(listOf(pgpart))
            val other = database(schema = "pgpart")

            val refused = assertFailsWith<DatabaseException> { other.tx("test") { ItemsTable.selectAll().count() } }

            assertEquals("42501", refused.sqlState, "insufficient_privilege")
        }

    @Test
    fun `a transaction of one module can't open inside another's`() =
        runBlocking<Unit> {
            val pools = pools()

            assertFailsWith<IllegalStateException> {
                database(pools).tx("test") { database(pools, "pgpart").tx("test") { 1 } }
            }
        }

    @Test
    fun `a statement over the pool's statement timeout is cancelled by the server`() =
        runBlocking<Unit> {
            val settings = TestPostgres.settings()
            val quick = settings.app.copy(statementTimeout = Duration.ofSeconds(1))
            val db = database(PostgresPools(settings, appSettings = quick).also { opened += it })
            val started = System.nanoTime()

            val cancelled = assertFailsWith<DatabaseException> { db.tx("test") { exec("SELECT pg_sleep(5)") } }

            assertEquals("57014", cancelled.sqlState, "query_canceled by the statement timeout")

            assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000, "cancelled after about a second")
        }

    @Test
    fun `background work takes the jobs pool and requests the app pool`() =
        runBlocking<Unit> {
            val db = database()

            assertEquals("circaso-app", db.tx("test") { current("current_setting('application_name')") })
            withContext(OnJobsPool) {
                assertEquals("circaso-jobs", db.tx("test") { current("current_setting('application_name')") })
                assertEquals("circaso_jobs", db.tx("test") { current("session_user") })
            }
        }

    @Test
    fun `with a pool of four, the fifth and sixth transactions wait for a connection and then succeed`() =
        runBlocking<Unit> {
            val db = database(pools(mapOf("DATABASE_POOL_APP_SIZE" to "4")))
            val running = AtomicInteger()
            val most = AtomicInteger()

            val results =
                coroutineScope {
                    (1..6)
                        .map { n ->
                            async {
                                db.tx("test") {
                                    most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                                    exec("SELECT pg_sleep(0.4)")
                                    running.decrementAndGet()
                                    n
                                }
                            }
                        }.awaitAll()
                }

            assertEquals((1..6).toList(), results)
            assertEquals(4, most.get(), "never more than the pool's size at once")
        }

    @Test
    fun `when no connection comes free in time the request fails as service unavailable`() =
        runBlocking<Unit> {
            val db =
                database(pools(mapOf("DATABASE_POOL_APP_SIZE" to "4", "DATABASE_POOL_ACQUIRE_TIMEOUT_MS" to "300")))

            val outcome =
                coroutineScope {
                    val holders = (1..4).map { async { db.tx("test") { exec("SELECT pg_sleep(1.5)") } } }
                    delay(200) // all four connections are taken
                    val waiting = runCatching { db.tx("test") { 1 } }
                    holders.awaitAll()
                    waiting
                }

            val error = outcome.exceptionOrNull()
            assertTrue(error is DomainException, "$error")
            assertEquals(SharedError.ServiceUnavailable, error.error)
            assertEquals(1, db.tx("test") { 1 }, "the pool serves again once a connection is back")
        }

    @Test
    fun `the app pool speaks R2DBC, with its name and the role's statement timeout on each connection`() =
        runBlocking<Unit> {
            val pools = pools()
            val db = database(pools)

            assertEquals("5s", db.tx("test") { current("current_setting('statement_timeout')") })
            assertTrue(
                pools.app.connections.metadata.name
                    .contains("PostgreSQL"),
                pools.app.connections.metadata.name,
            )
            assertEquals(io.r2dbc.pool.ConnectionPool::class, pools.app.connections::class)
        }
}
