package com.circaso.infrastructure.testing

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.impl.database.ModuleDatabase
import com.circaso.infrastructure.impl.database.PostgresPools
import com.circaso.infrastructure.impl.database.PostgresSettings
import com.circaso.infrastructure.impl.database.migrate.PostgresMigrator
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The PostgreSQL for data-layer tests (working-rules → Postgres): one `postgres:18` container per test JVM, started
 * with the server's own init scripts (`deploy/postgres/initdb`), so the login roles are made exactly as on the sandbox.
 *
 * A module's test gets its `Database` with [database] (migrated by the module's own Flyway scripts, as `Migrate` does)
 * and empties its tables between tests with [truncate]. What it hands out is `infrastructure:api` only: modules never
 * see the pools or the implementation (decision 2026-10-27, PG1 review).
 */
object TestPostgres {
    const val MIGRATOR_PASSWORD = "migrator-test"
    const val APP_PASSWORD = "app-test"
    const val JOBS_PASSWORD = "jobs-test"

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            .withDatabaseName("circaso")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv(
                mapOf(
                    "CIRCASO_READONLY_PASSWORD" to "readonly-test",
                    "CIRCASO_MIGRATOR_PASSWORD" to MIGRATOR_PASSWORD,
                    "CIRCASO_APP_PASSWORD" to APP_PASSWORD,
                    "CIRCASO_JOBS_PASSWORD" to JOBS_PASSWORD,
                ),
            ).withCopyFileToContainer(
                MountableFile.forHostPath(repositoryFile("deploy/postgres/initdb").path, 0b111_101_101),
                "/docker-entrypoint-initdb.d/",
            ).also { it.start() }
    }

    private val address: String get() = "${container.host}:${container.getMappedPort(5432)}/circaso"
    private val url: String get() = "jdbc:postgresql://$address"

    private val settings by lazy { env().let { PostgresSettings(EnvConfig(it), EnvSecrets(it)) } }
    private val pools by lazy { PostgresPools(settings) }
    private val migrated = ConcurrentHashMap.newKeySet<String>()

    /** The environment `serve` and `Migrate` read, pointed at the container; [extra] overrides any of it. */
    fun env(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "CIRCASO_ENV" to "local",
            "DATABASE_URL" to "r2dbc:postgresql://$address",
            "DATABASE_MIGRATION_URL" to url,
            "DATABASE_PASSWORD" to APP_PASSWORD,
            "DATABASE_JOBS_PASSWORD" to JOBS_PASSWORD,
            "DATABASE_MIGRATION_PASSWORD" to MIGRATOR_PASSWORD,
        ) + extra

    /** Runs [spec]'s Flyway scripts as `Migrate` does (schema, role, grants, scripts as the module's role). */
    fun migrate(spec: DatabaseModuleSpec) {
        PostgresMigrator(settings).migrate(listOf(spec))
        migrated += spec.schema
    }

    /** The module's `Database`, over R2DBC on the shared test pools, migrated once per test JVM. */
    fun database(spec: DatabaseModuleSpec): Database {
        if (spec.schema !in migrated) migrate(spec)
        return ModuleDatabase(spec.schema, pools)
    }

    /** A plain connection as the superuser, for setting up and looking at what the code under test did. */
    fun <T> superuser(block: (Connection) -> T): T =
        DriverManager.getConnection(url, "postgres", "postgres").use { connection -> block(connection) }

    /** Empties every table of [schema], so each test starts from nothing without migrating again. */
    fun truncate(schema: String) =
        superuser { connection ->
            val tables =
                connection
                    .prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname = ?")
                    .use { statement ->
                        statement.setString(1, schema)
                        statement.executeQuery().use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                        }
                    }.filter { it != "flyway_schema_history" }
            if (tables.isNotEmpty()) {
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE ${tables.joinToString { "\"$schema\".\"$it\"" }} CASCADE")
                }
            }
        }

    /** A file of this repository, found by walking up from the test's working directory. */
    private fun repositoryFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull { it.exists() }
            ?: error("$path not found above ${File("").absolutePath}")
}
