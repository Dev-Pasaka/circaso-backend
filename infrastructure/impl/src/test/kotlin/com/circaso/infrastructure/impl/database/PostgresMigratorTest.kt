package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrationOutcome
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import com.circaso.infrastructure.impl.settings
import com.circaso.infrastructure.testing.TestPostgres
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private object ThingsTable : Table("pgmigrate_a.things") {
    val id = javaUUID("id")
    val label = text("label")
    override val primaryKey = PrimaryKey(id)
}

private object OthersTable : Table("pgmigrate_b.others") {
    val id = javaUUID("id")
    override val primaryKey = PrimaryKey(id)
}

/** The `Migrate` step on a database that has never seen these modules: schemas, roles, grants, ownership, history. */
class PostgresMigratorTest {
    private val settings = TestPostgres.settings()
    private val alpha = DatabaseModuleSpec("pgmigrate_a", listOf(ThingsTable), "classpath:db/pgmigrate_a")
    private val beta = DatabaseModuleSpec("pgmigrate_b", listOf(OthersTable), "classpath:db/pgmigrate_b")

    /** As if the database had never been migrated: the two schemas, everything in them, and their roles gone. */
    private fun forgetModules() =
        TestPostgres.superuser { db ->
            db.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS pgmigrate_a, pgmigrate_b CASCADE")
                statement.execute("DROP ROLE IF EXISTS pgmigrate_a")
                statement.execute("DROP ROLE IF EXISTS pgmigrate_b")
            }
        }

    @Test
    fun `migrate creates each schema and role, wires the grants and runs the scripts as the module's role`() {
        forgetModules()

        val first = PostgresMigrator(settings).migrate(listOf(alpha, beta))
        val second = PostgresMigrator(settings).migrate(listOf(alpha, beta))

        assertEquals(3, first.applied, "V1 and V2 of alpha, V1 of beta")
        assertEquals(listOf("pgmigrate_a", "pgmigrate_b"), first.createdSchemas)
        assertEquals(listOf("pgmigrate_a", "pgmigrate_b"), first.createdRoles)
        assertEquals(PostgresMigrationOutcome.NOTHING, second, "a second run changes nothing")

        TestPostgres.superuser { db ->
            assertEquals("circaso_migrator", db.single(schemaOwner("pgmigrate_a")))
            assertEquals("false", db.single("SELECT rolcanlogin::text FROM pg_roles WHERE rolname = 'pgmigrate_a'"))
            assertEquals(
                "pgmigrate_a",
                db.single(tableOwner("pgmigrate_a", "things")),
                "the module's role owns its tables",
            )
            assertEquals(
                "pgmigrate_a",
                db.single(tableOwner("pgmigrate_a", "flyway_schema_history")),
                "and its history",
            )
            assertEquals("2", db.single("SELECT max(version) FROM pgmigrate_a.flyway_schema_history WHERE success"))
            assertEquals("1", db.single("SELECT max(version) FROM pgmigrate_b.flyway_schema_history WHERE success"))
            assertEquals("true", db.single(schemaPrivilege("pgmigrate_a", "pgmigrate_a", "USAGE, CREATE")))
            assertEquals("false", db.single(schemaPrivilege("pgmigrate_a", "pgmigrate_b", "USAGE")), "nowhere else")
            for (login in listOf("circaso_app", "circaso_jobs")) {
                for (role in listOf("pgmigrate_a", "pgmigrate_b")) {
                    assertEquals(
                        "true",
                        db.single("SELECT pg_has_role('$login', '$role', 'SET')::text"),
                        "$login, $role",
                    )
                }
            }
        }
    }

    @Test
    fun `migrate creates today's and tomorrow's partition of each partitioned table, owned by the module`() {
        PostgresMigrator(settings).migrate(listOf(alpha))

        val today = LocalDate.now(ZoneOffset.UTC)
        TestPostgres.superuser { db ->
            val partitions =
                db.list(
                    "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid " +
                        "WHERE i.inhparent = 'pgmigrate_a.outbox'::regclass",
                )
            assertTrue(Partitions.partitionName("outbox", today) in partitions, "$partitions")
            assertTrue(Partitions.partitionName("outbox", today.plusDays(1)) in partitions, "$partitions")
            assertEquals("pgmigrate_a", db.single(tableOwner("pgmigrate_a", Partitions.partitionName("outbox", today))))
        }
    }

    @Test
    fun `serve's check, over R2DBC, passes on a current history and names what is missing otherwise`() =
        runBlocking<Unit> {
            PostgresMigrator(settings).migrate(listOf(alpha))
            PostgresPools(settings).use { pools ->
                val history = MigrationHistory(pools)

                history.requireCurrent(listOf(alpha))
                val behind = alpha.copy(migrationsLocation = "classpath:db/pgmigrate_a_next") // this build ships a V3
                val problem = assertFailsWith<IllegalStateException> { history.requireCurrent(listOf(behind)) }

                assertTrue("pgmigrate_a V3: not applied" in problem.message.orEmpty(), problem.message)
                assertTrue("Migrate" in problem.message.orEmpty(), problem.message)
            }
        }

    @Test
    fun `migrate refuses to run before the login roles exist`() {
        val missing = TestPostgres.settings(mapOf("DATABASE_JOBS_USER" to "circaso_nobody"))

        val failure = assertFailsWith<IllegalStateException> { PostgresMigrator(missing).migrate(listOf(alpha)) }

        assertTrue("circaso_nobody" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `with no module spec, migrate connects to nothing`() {
        val unreachable = TestPostgres.settings(mapOf("DATABASE_MIGRATION_URL" to "jdbc:postgresql://127.0.0.1:1/none"))

        assertEquals(PostgresMigrationOutcome.NOTHING, PostgresMigrator(unreachable).migrate(emptyList()))
    }

    @Test
    fun `a spec names only tables of its own schema, and a schema name that is safe in SQL`() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseModuleSpec("pgmigrate_b", listOf(ThingsTable), "classpath:db/pgmigrate_b")
        }
        assertFailsWith<IllegalArgumentException> { DatabaseModuleSpec("Bad-Name", emptyList(), "classpath:db/x") }
    }

    private fun schemaOwner(schema: String) =
        "SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = '$schema'"

    private fun tableOwner(schema: String, table: String) =
        "SELECT tableowner FROM pg_tables WHERE schemaname = '$schema' AND tablename = '$table'"

    private fun schemaPrivilege(
        role: String,
        schema: String,
        privilege: String,
    ) = "SELECT has_schema_privilege('$role', '$schema', '$privilege')::text"
}

internal fun Connection.single(sql: String): String = list(sql).single()

internal fun Connection.list(sql: String): List<String> =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
        }
    }
