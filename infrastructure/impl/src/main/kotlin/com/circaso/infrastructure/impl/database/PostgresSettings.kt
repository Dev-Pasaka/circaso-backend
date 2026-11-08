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
    /** Connections opened at start (`PostgresPools.open`), before the first request. */
    val initialSize: Int,
    val statementTimeout: Duration,
    val slowAfter: Duration,
    /** How long a transaction waits for a free connection before it fails as `service.unavailable`. */
    val maxAcquireTime: Duration,
    /**
     * The fewest connections the pool keeps open. One that retires (past its [PoolTimings.maxLifeTime], or a dead
     * socket) is replaced in the background, so a request never pays for making one: about 20 ms for the connection
     * and more for its cold catalog cache.
     */
    val minIdle: Int = initialSize,
) {
    init {
        require(maxSize > 0) { "$name: maxSize must be positive (was $maxSize)" }
        require(initialSize in 0..maxSize) { "$name: initialSize must be within 0..$maxSize (was $initialSize)" }
        require(minIdle in 0..maxSize) { "$name: minIdle must be within 0..$maxSize (was $minIdle)" }
    }

    // The password is a secret: never in a log line, even if something prints this object.
    override fun toString(): String = "PoolSettings(name=$name, user=$user, maxSize=$maxSize)"
}

/** The pool values both pools share; each can be overridden by its `DATABASE_POOL_*` setting. */
data class PoolTimings(
    /**
     * How long a connection may sit unused before it is closed; null (the default) never closes an idle one. The pools
     * reach Postgres over the internal Docker network, where nothing cuts an idle socket, and a pool that shrank while
     * quiet would make its next requests pay for new connections. Behind a pooler or firewall that cuts idle clients,
     * set `DATABASE_POOL_MAX_IDLE_MS` below its cut.
     */
    val maxIdleTime: Duration? = null,
    /**
     * A connection's longest life, so none outlives a server-side change or a half-open socket for long. Each
     * connection's own limit is up to [LIFETIME_SPREAD] of this shorter, so the connections opened together at start
     * don't all retire in the same sweep. Six hours (`DATABASE_POOL_MAX_LIFE_MS`): a new connection is a new backend
     * with a cold catalog cache, and at 30 min one request in each retirement window paid for it (audit 2026-11-05,
     * Q8). The pools reach Postgres over the internal Docker network, where nothing cuts a socket; a half-open one is
     * caught sooner by `tcpKeepAlive` and the borrow's socket check. Behind a pooler that recycles its own backends,
     * set it below that pooler's lifetime.
     */
    val maxLifeTime: Duration = Duration.ofHours(6),
    /**
     * How often each pool, in the background, closes the idle connections past their life and opens new ones up to its
     * `minIdle`.
     */
    val maintenanceInterval: Duration = Duration.ofSeconds(5),
) {
    init {
        require(maxLifeTime > Duration.ZERO) { "maxLifeTime must be positive (was $maxLifeTime)" }
        require(
            maintenanceInterval > Duration.ZERO,
        ) { "maintenanceInterval must be positive (was $maintenanceInterval)" }
    }

    companion object {
        /** The most a connection's own life falls short of [maxLifeTime], as a share of it. */
        const val LIFETIME_SPREAD = 0.2
    }
}

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
            // Unset, 0 or negative: an idle connection is never closed.
            maxIdleTime = pool.int("max-idle-ms", 0).takeIf { it > 0 }?.let { Duration.ofMillis(it.toLong()) },
            maxLifeTime = pool.millis("max-life-ms", PoolTimings().maxLifeTime),
            maintenanceInterval = pool.millis("maintenance-ms", PoolTimings().maintenanceInterval),
        )

    private val appSize = pool.int("app-size", 10)
    private val jobsSize = pool.int("jobs-size", 3)

    /** Requests and socket frames: the hot path. */
    val app: PoolSettings =
        PoolSettings(
            name = "circaso-app",
            user = database.string("user", "circaso_app"),
            password = secrets.get("database.password").orEmpty(),
            maxSize = appSize,
            // Full from the start and kept full, so a request always finds a warm connection.
            initialSize = pool.int("app-initial-size", appSize).coerceIn(0, appSize),
            minIdle = pool.int("app-min-idle", appSize).coerceIn(0, appSize),
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
            maxSize = jobsSize,
            initialSize = pool.int("jobs-initial-size", jobsSize).coerceIn(0, jobsSize),
            minIdle = pool.int("jobs-min-idle", jobsSize).coerceIn(0, jobsSize),
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
