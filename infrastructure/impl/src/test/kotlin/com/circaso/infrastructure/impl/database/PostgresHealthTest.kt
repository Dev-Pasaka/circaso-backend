package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import com.circaso.infrastructure.impl.settings
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Readiness over R2DBC, gated on the background start (pools open, migrations current), which retries until it passes. */
class PostgresHealthTest {
    private val opened = mutableListOf<PostgresPools>()
    private val scope = CoroutineScope(SupervisorJob())

    private fun pools(extra: Map<String, String> = emptyMap()) =
        PostgresPools(TestPostgres.settings(extra)).also { opened += it }

    private fun startup() = PostgresStartup(firstWait = Duration.ofMillis(100), longestWait = Duration.ofMillis(400))

    @AfterTest
    fun close() {
        scope.cancel()
        opened.forEach { it.close() }
    }

    @Test
    fun `ready once the pools are open and every module is migrated`() =
        runBlocking<Unit> {
            val pools = pools()
            val startup = startup().apply { begin(scope) { pools.open() } }

            assertTrue(PostgresHealth(pools, startup).check() is Resource.Success)
        }

    @Test
    fun `not ready while a module's migrations are missing`() =
        runBlocking<Unit> {
            val pools = pools()
            val unmigrated = DatabaseModuleSpec("pgnever", emptyList(), "classpath:db/pgtx")
            val startup =
                startup().apply {
                    begin(scope) {
                        pools.open()
                        MigrationHistory(pools).requireCurrent(listOf(unmigrated))
                    }
                }

            assertTrue(PostgresHealth(pools, startup).check() is Resource.Error)
        }

    @Test
    fun `not ready before the start has begun`() =
        runBlocking<Unit> {
            assertTrue(PostgresHealth(pools(), PostgresStartup()).check() is Resource.Error)
        }

    @Test
    fun `a start against a database that isn't there yet keeps trying, and is ready once it answers`() =
        runBlocking<Unit> {
            val port = ServerSocket(0).use { it.localPort } // free now; nothing listens on it yet
            val target = TestPostgres.env()["DATABASE_URL"]!!.substringAfter("//").substringBefore("/")
            val pools = pools(mapOf("DATABASE_URL" to "r2dbc:postgresql://127.0.0.1:$port/circaso"))
            val startup = startup().apply { begin(scope) { pools.open() } }
            val health = PostgresHealth(pools, startup)

            assertTrue(health.check() is Resource.Error, "nothing is listening yet")
            Forwarder(port, target).use {
                val deadline = System.currentTimeMillis() + 15_000
                while (health.check() !is Resource.Success) {
                    check(System.currentTimeMillis() < deadline) { "never became ready" }
                }
            }
        }

    @Test
    fun `scripts applied but not in this build don't stop the start, so a rollback still serves`() =
        runBlocking<Unit> {
            val ahead = DatabaseModuleSpec("pgahead", emptyList(), "classpath:db/pgahead_next")
            PostgresMigrator(TestPostgres.settings()).migrate(listOf(ahead))
            val previous = ahead.copy(migrationsLocation = "classpath:db/pgahead") // the image before V2
            val history = MigrationHistory(pools())

            history.requireCurrent(listOf(previous))
            val findings = history.compare(previous)

            assertTrue(findings.problems.isEmpty(), "${findings.problems}")
            assertTrue(findings.newer == listOf("pgahead V2"), "${findings.newer}")
        }
}

/** A TCP forwarder from [port] to [target] (`host:port`): the database "comes up" when this opens. */
private class Forwarder(port: Int, target: String) : AutoCloseable {
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", port)) }
    private val host = target.substringBefore(':')
    private val targetPort = target.substringAfter(':').toInt()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                val upstream = Socket(host, targetPort)
                pipe(client, upstream)
                pipe(upstream, client)
            }
        }
    }

    private fun pipe(from: Socket, to: Socket) =
        thread(isDaemon = true) {
            runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
            runCatching { to.shutdownOutput() }
        }

    override fun close() = server.close()
}
