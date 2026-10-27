package com.circaso.infrastructure.impl

import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.impl.database.PostgresSettings
import com.circaso.infrastructure.impl.database.ScyllaSettings
import com.circaso.infrastructure.impl.redis.RedisConnection
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.scylladb.ScyllaDBContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** One Valkey container for the whole test JVM; tests keep apart by using their own keys and streams. */
object TestRedis {
    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine"))
            .withExposedPorts(6379)
            .also { it.start() }
    }

    val url: String get() = "redis://${container.host}:${container.getMappedPort(6379)}"

    fun connect(): RedisConnection = RedisConnection(url)
}

/** One ScyllaDB container for the whole test JVM; tests keep apart by using their own keyspaces. */
object TestScylla {
    private val container: ScyllaDBContainer by lazy {
        ScyllaDBContainer(DockerImageName.parse("scylladb/scylla:2026.1")).also { it.start() }
    }

    private val contactPoint get() = container.contactPoint.let { "${it.hostString}:${it.port}" }

    /** Settings for the container, in the given environment. TLS is off: the container speaks plain CQL. */
    fun settings(environment: String = "local", extra: Map<String, String> = emptyMap()): ScyllaSettings =
        ScyllaSettings(
            EnvConfig(
                mapOf(
                    "CIRCASO_ENV" to environment,
                    "SCYLLA_CONTACT_POINTS" to contactPoint,
                    "SCYLLA_DATACENTER" to "datacenter1",
                    "SCYLLA_TLS" to "false",
                ) +
                    // the container is a throw-away node: in the local environment its tables are made on start
                    (if (environment == "local") mapOf("SCYLLA_CREATE_SCHEMA" to "true") else emptyMap()) +
                    extra,
            ),
            EnvSecrets(emptyMap()),
        )
}

/**
 * One `postgres:18` container for the whole test JVM, started with the server's own init scripts
 * (`deploy/postgres/initdb`), so the login roles the tests use are made exactly as on the sandbox. Tests keep apart by
 * using their own schemas, and [truncate] a schema's tables between tests.
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

    fun settings(extra: Map<String, String> = emptyMap()): PostgresSettings =
        env(extra).let { PostgresSettings(EnvConfig(it), EnvSecrets(it)) }

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
