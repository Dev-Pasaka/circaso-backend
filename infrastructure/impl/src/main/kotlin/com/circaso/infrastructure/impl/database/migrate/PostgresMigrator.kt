package com.circaso.infrastructure.impl.database.migrate

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.database.Partitions
import com.circaso.infrastructure.impl.database.PostgresSettings
import com.circaso.infrastructure.impl.database.quoted
import com.circaso.infrastructure.impl.logging.kv
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.Properties

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Migrator")

/** What one `Migrate` run did on PostgreSQL. */
data class PostgresMigrationOutcome(
    val applied: Int,
    val createdSchemas: List<String>,
    val createdRoles: List<String>,
) {
    companion object {
        val NOTHING = PostgresMigrationOutcome(0, emptyList(), emptyList())
    }
}

/**
 * The `Migrate` main's PostgreSQL step, run once per deploy before the new containers start, as `circaso_migrator`
 * over the direct `DATABASE_MIGRATION_URL` (architecture.md → Who runs migrations). This is the one place JDBC is used:
 * Flyway has no R2DBC driver. The app itself never opens a JDBC connection.
 *
 * For each module spec it makes sure the schema and the module's role exist and are wired (below), then runs the
 * module's Flyway scripts as that role (`SET ROLE <module>`), so the module's role owns its tables and its own
 * `flyway_schema_history`. Last, it creates today's and tomorrow's partition of every partitioned table, so the first
 * write after a fresh migrate never waits for the partition job.
 *
 * The login roles (`circaso_migrator`, `circaso_app`, `circaso_jobs`) are not made here: they carry passwords and
 * limits, and are created once by the superuser (`deploy/postgres/initdb/20-login-roles.sh`).
 */
class PostgresMigrator(private val settings: PostgresSettings, private val clock: Clock = Clock.systemUTC()) {
    private val migration = settings.migration

    fun migrate(specs: List<DatabaseModuleSpec>): PostgresMigrationOutcome {
        if (specs.isEmpty()) return PostgresMigrationOutcome.NOTHING // nothing to migrate, so nothing to connect to
        return connect().use { connection -> migrate(connection, specs) }
    }

    private fun migrate(connection: Connection, specs: List<DatabaseModuleSpec>): PostgresMigrationOutcome {
        val schemas = mutableListOf<String>()
        val roles = mutableListOf<String>()
        requireLoginRoles(connection)
        for (spec in specs) {
            if (createSchema(connection, spec.schema)) schemas += spec.schema
            if (createRole(connection, spec.schema)) roles += spec.schema
            grant(connection, spec.schema)
        }
        var applied = 0
        for (spec in specs) {
            applied += runFlyway(spec)
            createPartitions(connection, spec.schema)
        }
        return PostgresMigrationOutcome(applied, schemas, roles)
    }

    /** One direct connection, autocommit on: each setup statement stands alone. */
    private fun connect(): Connection = DriverManager.getConnection(migration.url, properties())

    /** `logServerErrorDetail=false` keeps a violation's `Key (…)=(…)` detail, which may hold a value, out of logs. */
    private fun properties(): Properties =
        Properties().apply {
            setProperty("user", migration.user)
            setProperty("password", migration.password)
            setProperty("ApplicationName", "circaso-migrate")
            setProperty("sslmode", settings.sslMode)
            settings.sslRootCert?.let { setProperty("sslrootcert", it) }
            setProperty("tcpKeepAlive", "true")
            setProperty("logServerErrorDetail", "false")
        }

    private fun requireLoginRoles(connection: Connection) {
        val missing = listOf(settings.app.user, settings.jobs.user).filterNot { roleExists(connection, it) }
        check(missing.isEmpty()) {
            "Login roles $missing don't exist; create them once as the superuser with " +
                "deploy/postgres/initdb/20-login-roles.sh"
        }
    }

    /** `CREATE SCHEMA` as `circaso_migrator`, which so owns every module schema. */
    private fun createSchema(connection: Connection, schema: String): Boolean {
        if (exists(connection, "SELECT 1 FROM pg_namespace WHERE nspname = ?", schema)) return false
        connection.execute("CREATE SCHEMA ${quoted(schema)}")
        log.info("schema created", kv("kind", "db.migrate"), kv("schema", schema))
        return true
    }

    /** The module's role: no login of its own, only ever switched to. */
    private fun createRole(connection: Connection, role: String): Boolean {
        if (roleExists(connection, role)) return false
        connection.execute("CREATE ROLE ${quoted(role)} NOLOGIN")
        log.info("role created", kv("kind", "db.migrate"), kv("role", role))
        return true
    }

    /**
     * Wires a module's role. PostgreSQL 16+ gives the role's creator only the ADMIN option, not the right to switch to
     * it, so the migrator grants itself SET first; the role may use and create in its own schema only; and the app
     * and jobs logins are granted it, so their transactions can `SET LOCAL ROLE` to it. Each grant is idempotent.
     */
    private fun grant(connection: Connection, schema: String) {
        val role = quoted(schema)
        connection.execute("GRANT $role TO ${quoted(migration.user)} WITH SET TRUE")
        connection.execute("GRANT USAGE, CREATE ON SCHEMA ${quoted(schema)} TO $role")
        connection.execute("GRANT $role TO ${quoted(settings.app.user)}, ${quoted(settings.jobs.user)}")
    }

    /**
     * The module's scripts, as its role, with its own history table in its schema. Returns how many ran. Flyway holds
     * more than one connection at a time (its lock and history beside the script being run), so it opens and closes its
     * own, with the same address, login and settings.
     */
    private fun runFlyway(spec: DatabaseModuleSpec): Int {
        val result =
            Flyway
                .configure()
                .dataSource(migration.url, migration.user, migration.password)
                .jdbcProperties(properties().stringPropertyNames().associateWith { properties().getProperty(it) })
                .schemas(spec.schema)
                .defaultSchema(spec.schema)
                .createSchemas(false)
                .locations(spec.migrationsLocation)
                .initSql("SET ROLE ${quoted(spec.schema)}")
                .load()
                .migrate()
        log.info(
            "migrated",
            kv("kind", "db.migrate"),
            kv("schema", spec.schema),
            kv("applied", result.migrationsExecuted),
            kv("version", result.targetSchemaVersion),
        )
        return result.migrationsExecuted
    }

    /** Today's and tomorrow's partition of each partitioned table in [schema], created as the module's role. */
    private fun createPartitions(connection: Connection, schema: String) {
        connection.autoCommit = false
        try {
            connection.execute("SET LOCAL ROLE ${quoted(schema)}")
            val tables =
                connection.rows(Partitions.PARTITIONED_TABLES, schema).mapNotNull { (table, type) ->
                    Partitions.keyType(type)?.let { table to it }
                }
            for ((table, key) in tables) {
                val existing = connection.rows(Partitions.PARTITIONS_OF, schema, table).map { it.first() }
                Partitions.missingAhead(table, existing, clock.instant()).forEach { (date, _) ->
                    connection.execute(Partitions.createPartition(schema, table, key, date))
                }
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun roleExists(connection: Connection, role: String): Boolean =
        exists(connection, "SELECT 1 FROM pg_roles WHERE rolname = ?", role)

    private fun exists(
        connection: Connection,
        sql: String,
        value: String,
    ): Boolean = connection.rows(sql, value).isNotEmpty()

    /** Every row of [sql] with [values] bound in order, each column read as text. */
    private fun Connection.rows(sql: String, vararg values: String): List<List<String>> =
        prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows ->
                val columns = rows.metaData.columnCount
                generateSequence { if (rows.next()) (1..columns).map { rows.getString(it) } else null }.toList()
            }
        }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
