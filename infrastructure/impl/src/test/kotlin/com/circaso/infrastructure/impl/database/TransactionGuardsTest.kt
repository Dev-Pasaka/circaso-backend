package com.circaso.infrastructure.impl.database

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.TestRedis
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import com.circaso.infrastructure.impl.events.RedisEventBus
import com.circaso.infrastructure.impl.http.VendorHttpClients
import com.circaso.infrastructure.impl.redis.LettuceRedisScopes
import com.circaso.infrastructure.impl.settings
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventEnvelope
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.r2dbc.insert
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object GuardedItemsTable : Table("pgtx.items") {
    val id = javaUUID("id")
    val name = text("name")
    override val primaryKey = PrimaryKey(id)
}

@Serializable
private data class Guarded(val n: Int) : DomainEvent {
    override val type: String get() = "pgtx.Guarded"
}

/** What `Database.tx` keeps out: bound values in errors and logs, and calls that leave the database. */
class TransactionGuardsTest {
    private val spec = DatabaseModuleSpec("pgtx", listOf(GuardedItemsTable), "classpath:db/pgtx")
    private val pools = PostgresPools(TestPostgres.settings())
    private val db = ModuleDatabase("pgtx", pools)
    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val circaso = LoggerFactory.getLogger("com.circaso") as Logger

    @BeforeTest
    fun migrate() {
        PostgresMigrator(TestPostgres.settings()).migrate(listOf(spec))
        TestPostgres.truncate("pgtx")
        root.addAppender(logs)
        circaso.level = Level.DEBUG // every line Circaso writes about the transaction, the per-transaction one included
    }

    @AfterTest
    fun close() {
        root.detachAppender(logs)
        circaso.level = null
        pools.close()
    }

    @Test
    fun `a failed statement names the operation, SQLSTATE and constraint, and never the value`() =
        runBlocking<Unit> {
            val marker = "MARKER-0712345678-" + "x".repeat(40)

            val failure =
                assertFailsWith<DatabaseException> {
                    db.tx("items.add") {
                        GuardedItemsTable.insert {
                            it[id] = UUID.randomUUID()
                            it[name] = marker
                        }
                    }
                }

            assertTrue(failure.isCheckViolation, failure.message)
            assertEquals("items_name_short", failure.constraint)
            assertEquals("items.add", failure.operation)
            assertNull(failure.cause, "no cause that could quote the statement or the row")
            assertFalse(marker in failure.toString(), failure.toString())
            val logged =
                logs.list.joinToString("\n") { event ->
                    event.formattedMessage + event.argumentArray.orEmpty().joinToString() +
                        (event.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
                }
            assertFalse("MARKER-0712345678" in logged, logged)
        }

    @Test
    fun `publishing an event inside a transaction is refused`() =
        runBlocking<Unit> {
            val redis = TestRedis.connect()
            try {
                val bus = RedisEventBus(redis)
                val envelope = EventEnvelope.of(Guarded(1), Guarded.serializer())

                val refused =
                    assertFailsWith<IllegalStateException> { db.tx("items.publish") { bus.publish(envelope) } }

                assertTrue("EventBus.publish" in refused.message.orEmpty(), refused.message)
                bus.publish(envelope) // after the commit it is fine
            } finally {
                redis.close()
            }
        }

    @Test
    fun `a Redis call inside a transaction is refused`() =
        runBlocking<Unit> {
            val redis = TestRedis.connect()
            try {
                val scope = LettuceRedisScopes(redis).scope("tst")

                assertFailsWith<IllegalStateException> { db.tx("items.cache") { scope.get("k") } }
                scope.get("k") // outside, as usual
            } finally {
                redis.close()
            }
        }

    @Test
    fun `an outbound HTTP call inside a transaction is refused`() =
        runBlocking<Unit> {
            val clients = VendorHttpClients(engine = { MockEngine { respondOk("{}") } })
            try {
                val vendor = clients.named("acme")

                val refused = assertFailsWith<IllegalStateException> { db.tx("items.call") { vendor.get("/x") } }

                assertTrue("HTTP to acme" in refused.message.orEmpty(), refused.message)
                vendor.get("/x") // outside, as usual
            } finally {
                clients.close()
            }
        }
}
