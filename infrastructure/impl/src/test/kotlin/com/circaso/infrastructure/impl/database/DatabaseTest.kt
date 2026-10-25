package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.HealthCheck
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.impl.TestScylla
import com.circaso.observability.api.NoopTelemetry
import com.circaso.shared.Resource
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import io.kandra.migrate.KandraMigration
import io.kandra.runtime.repository.KandraSuspendRepository
import io.kandra.test.KandraTestUtils
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Two modules, two keyspaces, entities with module-style names (they end in Row and are unique).
@ScyllaTable(name = "things_by_id", keyspace = "circaso_test_alpha")
data class AlphaThingRow(
    @PartitionKey val id: UUID,
    val label: String,
)

@ScyllaTable(name = "things_by_id", keyspace = "circaso_test_beta")
data class BetaThingRow(
    @PartitionKey val id: UUID,
    val label: String,
)

@ScyllaTable(name = "orphans_by_id", keyspace = "circaso_test_gamma")
data class GammaOrphanRow(
    @PartitionKey val id: UUID,
    val label: String,
)

@ScyllaTable(name = "nowhere")
data class NoKeyspaceRow(
    @PartitionKey val id: UUID,
)

object AlphaV1 : KandraMigration(version = 1, name = "create things") {
    override fun up(session: CqlSession) {
        session.execute("CREATE TABLE IF NOT EXISTS circaso_test_alpha.things_by_id (id uuid PRIMARY KEY, label text)")
    }
}

object BetaV1 : KandraMigration(version = 1, name = "create things") {
    override fun up(session: CqlSession) {
        session.execute("CREATE TABLE IF NOT EXISTS circaso_test_beta.things_by_id (id uuid PRIMARY KEY, label text)")
    }
}

object BetaV2 : KandraMigration(version = 2, name = "add note") {
    override fun up(session: CqlSession) {
        session.execute("ALTER TABLE circaso_test_beta.things_by_id ADD note text")
    }
}

class DatabaseTest {
    private val alpha = KandraModuleSpec("circaso_test_alpha", listOf(AlphaThingRow::class), listOf(AlphaV1))
    private val beta = KandraModuleSpec("circaso_test_beta", listOf(BetaThingRow::class), listOf(BetaV1, BetaV2))

    @BeforeTest
    fun freshKeyspaces() {
        KandraTestUtils.reset() // the entity registry is process-wide
        // the container is shared by every test, so each starts from keyspaces that don't exist
        withSession { session ->
            listOf("alpha", "beta", "gamma").forEach { session.execute("DROP KEYSPACE IF EXISTS circaso_test_$it") }
        }
    }

    @AfterTest
    fun resetKandra() = KandraTestUtils.reset()

    private fun <T> withSession(block: (CqlSession) -> T): T = TestScylla.settings().openSession().use(block)

    private fun tableExists(
        session: CqlSession,
        keyspace: String,
        table: String,
    ) = session
        .execute(
            "SELECT table_name FROM system_schema.tables WHERE keyspace_name = '$keyspace' AND table_name = '$table'",
        ).one() != null

    // ---- migrate ------------------------------------------------------------------------------------------

    @Test
    fun `migrate creates each module's keyspace and tables, with a history per keyspace`() {
        val migrator = DatabaseMigrator(TestScylla.settings())

        val applied = migrator.migrate(listOf(alpha, beta))

        assertEquals(3, applied)
        withSession { session ->
            assertTrue(tableExists(session, "circaso_test_alpha", "things_by_id"))
            assertTrue(tableExists(session, "circaso_test_beta", "things_by_id"))
            val alphaHistory =
                session.execute("SELECT version FROM circaso_test_alpha.kandra_migrations").all().map {
                    it.getInt("version")
                }
            val betaHistory =
                session.execute("SELECT version FROM circaso_test_beta.kandra_migrations").all().map {
                    it.getInt("version")
                }
            assertEquals(listOf(1), alphaHistory.sorted())
            assertEquals(listOf(1, 2), betaHistory.sorted(), "beta numbers its migrations independently of alpha")
        }
    }

    @Test
    fun `running migrate again applies nothing`() {
        val migrator = DatabaseMigrator(TestScylla.settings())
        migrator.migrate(listOf(alpha, beta))

        assertEquals(0, migrator.migrate(listOf(alpha, beta)))
    }

    private fun replicationOf(session: CqlSession, keyspace: String): Map<String, String>? =
        session
            .execute("SELECT replication FROM system_schema.keyspaces WHERE keyspace_name = '$keyspace'")
            .one()
            ?.getMap("replication", String::class.java, String::class.java)

    @Test
    fun `outside local migrate creates a missing keyspace with the configured replication, then migrates it`() {
        val applied = DatabaseMigrator(TestScylla.settings(environment = "staging")).migrate(listOf(beta))

        assertEquals(2, applied)
        withSession { session ->
            val replication = replicationOf(session, "circaso_test_beta")
            assertEquals("org.apache.cassandra.locator.NetworkTopologyStrategy", replication?.get("class"))
            assertEquals("1", replication?.get("datacenter1"))
            assertTrue(tableExists(session, "circaso_test_beta", "things_by_id"))
        }
    }

    @Test
    fun `migrate leaves a keyspace that already exists as it is`() {
        withSession { session ->
            session.execute(
                "CREATE KEYSPACE circaso_test_alpha WITH replication = " +
                    "{'class': 'SimpleStrategy', 'replication_factor': 1} " +
                    "AND durable_writes = false AND tablets = {'enabled': false}",
            )
        }

        val applied = DatabaseMigrator(TestScylla.settings(environment = "staging")).migrate(listOf(alpha))

        assertEquals(1, applied)
        withSession { session ->
            val replication = replicationOf(session, "circaso_test_alpha")
            assertEquals("org.apache.cassandra.locator.SimpleStrategy", replication?.get("class"))
            val durable =
                session
                    .execute(
                        "SELECT durable_writes FROM system_schema.keyspaces WHERE keyspace_name = 'circaso_test_alpha'",
                    ).one()
                    ?.getBoolean("durable_writes")
            assertEquals(false, durable, "the keyspace was not recreated or altered")
            assertTrue(tableExists(session, "circaso_test_alpha", "things_by_id"))
        }
    }

    // ---- install ------------------------------------------------------------------------------------------

    private fun Application.database(specs: List<KandraModuleSpec>, environment: String = "local") {
        install(Koin) { modules(module { }) }
        installDatabase(TestScylla.settings(environment), specs, NoopTelemetry)
    }

    @Test
    fun `local install creates the tables in each module's own keyspace and hands out repositories`() =
        testApplication {
            application { database(listOf(alpha, beta)) }
            startApplication()

            val alphaRepo = application.getKoin().get<KandraSuspendRepository<*>>(named("AlphaThingRowSuspendRepo"))
            val betaRepo = application.getKoin().get<KandraSuspendRepository<*>>(named("BetaThingRowSuspendRepo"))

            @Suppress("UNCHECKED_CAST")
            val alphaThings = alphaRepo as KandraSuspendRepository<AlphaThingRow>

            @Suppress("UNCHECKED_CAST")
            val betaThings = betaRepo as KandraSuspendRepository<BetaThingRow>
            val id = UUID.randomUUID()

            alphaThings.save(AlphaThingRow(id, "in alpha"))

            assertEquals("in alpha", alphaThings.findById(id)?.label)
            assertEquals(null, betaThings.findById(id), "the same id is not visible in the other module's keyspace")
            withSession {
                assertEquals(
                    "in alpha",
                    it
                        .execute(
                            "SELECT label FROM circaso_test_alpha.things_by_id WHERE id = $id",
                        ).one()
                        ?.getString("label"),
                )
            }
        }

    @Test
    fun `the database reports healthy and answers the Kandra health route`() =
        testApplication {
            application { database(listOf(alpha)) }
            startApplication()

            val health = application.getKoin().get<HealthCheck>(named("scylla"))

            assertTrue(health.check() is Resource.Success)
            assertEquals(HttpStatusCode.OK, client.get("/kandra/health").status)
        }

    @Test
    fun `outside local the install only validates, and passes once migrate has run`() {
        DatabaseMigrator(TestScylla.settings()).migrate(listOf(alpha))

        testApplication {
            application { database(listOf(alpha), environment = "staging") }
            startApplication()
            assertNotNull(application.getKoin().get<KandraSuspendRepository<*>>(named("AlphaThingRowSuspendRepo")))
        }
    }

    @Test
    fun `outside local the install refuses to start when a table was never migrated`() {
        // the keyspace exists (migrate made it) but gamma has no migration creating its table
        DatabaseMigrator(
            TestScylla.settings(),
        ).migrate(listOf(KandraModuleSpec("circaso_test_gamma", listOf(GammaOrphanRow::class))))
        val staging = KandraModuleSpec("circaso_test_gamma", listOf(GammaOrphanRow::class))

        assertFailsWith<KandraSchemaException> {
            testApplication {
                application { database(listOf(staging), environment = "staging") }
                startApplication()
            }
        }
    }

    @Test
    fun `an entity that names no keyspace stops the install, because there is no default`() {
        val spec = KandraModuleSpec("circaso_test_alpha", listOf(NoKeyspaceRow::class))

        assertFailsWith<KandraSchemaException> {
            testApplication {
                application { database(listOf(spec)) }
                startApplication()
            }
        }
    }
}
