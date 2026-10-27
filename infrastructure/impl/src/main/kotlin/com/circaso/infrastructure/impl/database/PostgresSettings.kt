package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.Secrets
import java.time.Duration

/**
 * One r2dbc-pool's shape (architecture.md → Exposed and PostgreSQL → Pools). [statementTimeout] is sent as the
 * connection's `statement_timeout`, the same value as the login role's; a transaction slower than [slowAfter] is logged
 * at WARN.
 */
data class PoolSettings(
    /** `circaso-app`, `circaso-jobs`: the pool's name and its connections' `application_name`. */
    val name: String,
    val user: String,
    val password: String,
    val maxSize: Int,
    val initialSize: Int,
    val statementTimeout: Duration,
    val slowAfter: Duration,
    /** How long a transaction waits for a free connection before it fails as `service.unavailable`. */
    val maxAcquireTime: Duration,
) {
    // The password is a secret: never in a log line, even if something prints this object.
    override fun toString(): String = "PoolSettings(name=$name, user=$user, maxSize=$maxSize)"
}

/** The pool values both pools share; each can be overridden by its `DATABASE_POOL_*` setting. */
data class PoolTimings(
    val maxIdleTime: Duration = Duration.ofMinutes(10),
    /** Below any proxy or firewall idle cut. */
    val maxLifeTime: Duration = Duration.ofMinutes(30),
)

/** The `Migrate` step's direct JDBC connection, used only by Flyway and the schema and role setup. */
data class MigrationSettings(val url: String, val user: String, val password: String) {
    override fun toString(): String = "MigrationSettings(url=$url, user=$user)"
}

/**
 * How to reach PostgreSQL, read once from [Config] and [Secrets]. Settings follow the dotted names: `database.url` is
 * `DATABASE_URL`, the secret `database.password` is `DATABASE_PASSWORD`.
 *
 * The app talks R2DBC only (`r2dbc:postgresql://…`); the `Migrate` step's Flyway is JDBC-only, so its URL is a
 * `jdbc:postgresql://…` one. Locally everything defaults to the compose Postgres and the roles its init script makes.
 */
class PostgresSettings(config: Config, secrets: Secrets) {
    private val database = config.section("database")
    private val pool = database.section("pool")

    val environment: Environment = config.environment

    /** The app and jobs pools' address. In production behind a transaction pooler, this is the pooled endpoint. */
    val url: String = database.string("url", "r2dbc:postgresql://localhost:5432/circaso")

    /**
     * `verify-full` outside local, against [sslRootCert] (the provider's CA) for a managed or remote host; `disable` only
     * on the sandbox's internal Docker network and locally, where Postgres speaks plain TCP (decision 2026-10-27, PG1
     * review).
     */
    val sslMode: String = database.string("ssl-mode", if (environment.isLocal) "disable" else "verify-full")

    /** The CA file `verify-full` checks the server against; unset means the JVM's trust store. */
    val sslRootCert: String? = database.string("ssl-root-cert")

    val timings: PoolTimings =
        PoolTimings(
            maxIdleTime = pool.millis("max-idle-ms", PoolTimings().maxIdleTime),
            maxLifeTime = pool.millis("max-life-ms", PoolTimings().maxLifeTime),
        )

    /** Requests and socket frames: the hot path. */
    val app: PoolSettings =
        PoolSettings(
            name = "circaso-app",
            user = database.string("user", "circaso_app"),
            password = secrets.get("database.password").orEmpty(),
            maxSize = pool.int("app-size", 10),
            initialSize = 2,
            statementTimeout = Duration.ofSeconds(5),
            slowAfter = Duration.ofMillis(100),
            // A request fails fast (503) rather than piling up.
            maxAcquireTime = pool.millis("acquire-timeout-ms", Duration.ofSeconds(3)),
        )

    /** The outbox relay, event handlers, scheduled jobs: never takes the connections requests need. */
    val jobs: PoolSettings =
        PoolSettings(
            name = "circaso-jobs",
            user = database.string("jobs-user", "circaso_jobs"),
            password = secrets.get("database.jobs-password").orEmpty(),
            maxSize = pool.int("jobs-size", 3),
            initialSize = 1,
            statementTimeout = Duration.ofSeconds(60),
            slowAfter = Duration.ofSeconds(5),
            // Background work can wait out a burst, but less than the consumers' 30 s claimIdle, so a message is never
            // reclaimed by another node while its handler is still waiting for a connection.
            maxAcquireTime = pool.millis("jobs-acquire-timeout-ms", Duration.ofSeconds(20)),
        )

    /** Always a direct connection: Flyway takes a session-level lock, which a transaction pooler would break. */
    val migration: MigrationSettings =
        MigrationSettings(
            url = database.string("migration-url", "jdbc:postgresql://localhost:5432/circaso"),
            user = database.string("migration-user", "circaso_migrator"),
            password = secrets.get("database.migration-password").orEmpty(),
        )
}

private fun Config.millis(key: String, default: Duration): Duration =
    Duration.ofMillis(int(key, default.toMillis().toInt()).toLong())
