package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.impl.logging.kv
import com.circaso.observability.api.Metrics
import com.circaso.observability.api.NoopTelemetry
import com.circaso.observability.api.Telemetry
import com.circaso.shared.DomainException
import com.circaso.shared.SharedError
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.pool.PoolMetrics
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.APPLICATION_NAME
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.ERROR_RESPONSE_LOG_LEVEL
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.SSL_MODE
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.SSL_ROOT_CERT
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.TCP_KEEPALIVE
import io.r2dbc.postgresql.client.SSLMode
import io.r2dbc.postgresql.util.LogLevel
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.Option
import io.r2dbc.spi.TransactionDefinition
import io.r2dbc.spi.ValidationDepth
import io.r2dbc.spi.Wrapped
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.pool.PooledRefMetadata
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Pools")
private val sqlLog = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Sql")

/**
 * Marks a coroutine as background work (the outbox relay, event handlers, scheduled jobs): its transactions take the
 * jobs pool, so a slow purge or a burst of events never takes the connections requests need. Infrastructure sets it;
 * modules never choose a pool.
 */
internal object OnJobsPool : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<OnJobsPool>
}

/**
 * One r2dbc-pool `ConnectionPool` and the one Exposed `R2dbcDatabase` built over it (connected once: connecting again
 * leaks). Nothing here blocks a thread: a transaction waiting for a connection suspends in the pool's queue, and gives
 * up after the pool's acquire timeout.
 *
 * Every [maintenanceInterval] the pool tops itself up to its `minIdle` in the background, so a connection retired by
 * its lifetime (or lost with its socket) is replaced before a request needs it, not by the request.
 */
class Pool(val settings: PoolSettings, val connections: ConnectionPool, maintenanceInterval: Duration? = null) :
    AutoCloseable {
    val name: String get() = settings.name

    private val maintenance: Disposable? =
        maintenanceInterval?.takeIf { settings.minIdle > 0 }?.let { interval ->
            val failing = AtomicBoolean(false)
            Flux
                .interval(interval, interval)
                .onBackpressureDrop()
                .concatMap(
                    {
                        connections
                            .warmup()
                            .doOnNext { opened ->
                                if (failing.getAndSet(false)) log.info("pool refills again", *poolFields())
                                if (opened > 0) log.debug("pool refilled", *poolFields(), kv("opened", opened))
                            }.onErrorResume { e ->
                                // Once per outage, not every tick; the next transaction fails on its own anyway.
                                if (!failing.getAndSet(true)) {
                                    log.warn("pool can't refill", *poolFields(), kv("error", e.javaClass.simpleName))
                                }
                                Mono.empty()
                            }
                    },
                    1,
                ).subscribe()
        }

    private fun poolFields() = arrayOf(kv("kind", "db.pool"), kv("pool", name), kv("idle", metrics?.idleSize()))

    val exposed: R2dbcDatabase =
        R2dbcDatabase.connect(
            connectionFactory = TimeoutsFromTheConnection(connections),
            databaseConfig =
                R2dbcDatabaseConfig {
                    explicitDialect = PostgreSQLDialect()
                    defaultR2dbcIsolationLevel = IsolationLevel.READ_COMMITTED
                    // Nothing runs twice by surprise; a call site that is safe to repeat raises it and says why.
                    defaultMaxAttempts = 1
                    useNestedTransactions = false
                    sqlLogger = PlaceholderSqlLogger
                },
        )

    /** r2dbc-pool's counts, for the gauges. Null only if the pool was built without metrics. */
    val metrics: PoolMetrics? get() = connections.metrics.orElse(null)

    fun unavailable(cause: Throwable? = null): DomainException {
        log.warn(
            "no database connection came free in time",
            kv("kind", "db.pool"),
            kv("pool", name),
            kv("pending", metrics?.pendingAcquireSize()),
        )
        return DomainException(SharedError.ServiceUnavailable, cause)
    }

    override fun close() {
        maintenance?.dispose()
        connections.dispose()
    }

    companion object {
        /** Opens a pool on the R2DBC [url] with [settings] and the shared [timings]. */
        fun open(
            url: String,
            sslMode: String,
            sslRootCert: String?,
            settings: PoolSettings,
            timings: PoolTimings,
        ): Pool {
            val factory = OneTripBegin(ConnectionFactories.get(connectionOptions(url, sslMode, sslRootCert, settings)))
            // r2dbc-pool keeps max(initialSize, minIdle) connections at least, and opens that many at warm-up.
            val least = maxOf(settings.initialSize, settings.minIdle)
            val configuration =
                ConnectionPoolConfiguration
                    .builder(factory)
                    .name(settings.name)
                    .initialSize(settings.initialSize)
                    .minIdle(settings.minIdle)
                    .maxSize(settings.maxSize)
                    .maxAcquireTime(settings.maxAcquireTime)
                    .maxIdleTime(timings.maxIdleTime)
                    .maxLifeTime(timings.maxLifeTime)
                    // Retired connections are closed by a background sweep; the refill (Pool.maintenance) follows.
                    .backgroundEvictionInterval(timings.maintenanceInterval)
                    // A borrow checks the socket is open, locally: no `SELECT 1` round trip before every transaction.
                    // A connection that died quietly is caught by tcpKeepAlive and its lifetime, and one whose server
                    // went away fails its statement and is closed by the driver.
                    .validationDepth(ValidationDepth.LOCAL)
                    .customizer { pool ->
                        // Opens up to four at once, so a deploy fills the pool in a few connection times.
                        pool.sizeBetween(least, settings.maxSize, least.coerceIn(1, 4))
                        pool.evictionPredicate(Retirement(timings)::due)
                    }.build()
            return Pool(settings, ConnectionPool(configuration), timings.maintenanceInterval)
        }

        /**
         * The driver options every pool sets (architecture.md → Pools). An option already in the URL wins, so a pooled
         * production URL can carry `preparedStatementCacheQueries=0` for a transaction pooler that can't keep named
         * statements. `statement_timeout` is a startup option, the same value as the login role's.
         */
        fun connectionOptions(
            url: String,
            sslMode: String,
            sslRootCert: String?,
            settings: PoolSettings,
        ): ConnectionFactoryOptions {
            val fromUrl = ConnectionFactoryOptions.parse(url)
            val builder =
                fromUrl
                    .mutate()
                    .option(ConnectionFactoryOptions.USER, settings.user)
                    .option(ConnectionFactoryOptions.PASSWORD, settings.password)
                    .option(APPLICATION_NAME, settings.name)
                    .option(ConnectionFactoryOptions.STATEMENT_TIMEOUT, settings.statementTimeout)
            builder.unlessIn(fromUrl, SSL_MODE, SSLMode.fromValue(sslMode))
            sslRootCert?.let { builder.unlessIn(fromUrl, SSL_ROOT_CERT, it) }
            builder.unlessIn(fromUrl, TCP_KEEPALIVE, true)
            // The driver logs a server error with all its fields, the DETAIL that quotes the failing row included; a
            // failure reaches us as a DatabaseException instead (operation, SQLSTATE, constraint). Not overridable.
            builder.option(ERROR_RESPONSE_LOG_LEVEL, LogLevel.OFF)
            // A bounded cache of named statements; PgBouncer 1.21+ with max_prepared_statements keeps them working.
            builder.unlessIn(fromUrl, PREPARED_STATEMENT_CACHE_QUERIES, 256)
            return builder.build()
        }

        private fun <T : Any> ConnectionFactoryOptions.Builder.unlessIn(
            url: ConnectionFactoryOptions,
            option: Option<T>,
            value: T,
        ) {
            if (!url.hasOption(option)) option(option, value)
        }
    }
}

/**
 * The app and jobs pools of one container, opened on first use (`serve` opens them at start and fills them, so a
 * database that can't be reached stops the start). Their counts are reported as gauges through `Telemetry`. Closed
 * when the application stops.
 */
class PostgresPools(
    private val settings: PostgresSettings,
    private val telemetry: Telemetry = NoopTelemetry,
    appSettings: PoolSettings = settings.app,
    jobsSettings: PoolSettings = settings.jobs,
) : AutoCloseable {
    private val appPool = lazy { open(appSettings) }
    private val jobsPool = lazy { open(jobsSettings) }

    val app: Pool by appPool
    val jobs: Pool by jobsPool

    /** The jobs pool for background work ([OnJobsPool]), the app pool for everything else. */
    fun forWork(context: CoroutineContext): Pool = if (context[OnJobsPool.Key] != null) jobs else app

    /**
     * Opens both pools now and fills them to their initial size (by default full), so the first requests after a deploy
     * don't make connections; fails if the database can't be reached. From then on each pool keeps itself at its
     * `minIdle` in the background.
     */
    suspend fun open() {
        app.connections.warmup().awaitFirstOrNull()
        jobs.connections.warmup().awaitFirstOrNull()
    }

    private fun open(pool: PoolSettings): Pool =
        Pool.open(settings.url, settings.sslMode, settings.sslRootCert, pool, settings.timings).also {
            registerGauges(it)
            log.info(
                "pool open",
                kv("kind", "db.pool"),
                kv("pool", pool.name),
                kv("maxSize", pool.maxSize),
                kv("minIdle", pool.minIdle),
                kv("user", pool.user),
            )
        }

    private fun registerGauges(pool: Pool) {
        val states: List<Pair<String, (PoolMetrics) -> Int>> =
            listOf(
                "acquired" to PoolMetrics::acquiredSize,
                "idle" to PoolMetrics::idleSize,
                "allocated" to PoolMetrics::allocatedSize,
                "pending" to PoolMetrics::pendingAcquireSize,
                "max" to PoolMetrics::getMaxAllocatedSize,
            )
        states.forEach { (state, read) ->
            telemetry.gauge(Metrics.DB_POOL_CONNECTIONS, mapOf("pool" to pool.name, "state" to state)) {
                pool.metrics?.let(read)?.toDouble() ?: 0.0
            }
        }
    }

    override fun close() {
        // Only the pools that were opened: touching a lazy one here would open it just to close it.
        if (appPool.isInitialized()) app.close()
        if (jobsPool.isInitialized()) jobs.close()
    }
}

/**
 * Exposed sets the statement timeout before every statement, and r2dbc-postgresql does that with a session-level
 * `SET STATEMENT_TIMEOUT`: one more round trip per statement, and with Exposed's default of 0 it would switch the role's
 * timeout off. The pools set `statement_timeout` once per connection instead (a startup option), so this factory hands
 * out connections that ignore the per-statement call. Everything else goes straight through.
 */
private class TimeoutsFromTheConnection(private val pool: ConnectionPool) : ConnectionFactory {
    override fun create(): Publisher<out Connection> =
        Mono.defer {
            val asked = System.nanoTime()
            Mono.from(pool.create()).map { IgnoresStatementTimeout(it, waitNanos = System.nanoTime() - asked) }
        }

    override fun getMetadata(): ConnectionFactoryMetadata = pool.metadata
}

/**
 * A borrowed connection as Exposed sees it: the per-statement timeout call is dropped, and a [ModuleTransaction]
 * learns how long the borrow took ([waitNanos]) when it begins.
 */
private class IgnoresStatementTimeout(private val connection: Connection, private val waitNanos: Long) :
    Connection by connection,
    Wrapped<Connection> {
    override fun setStatementTimeout(timeout: Duration): Publisher<Void> = Mono.empty()

    override fun beginTransaction(definition: TransactionDefinition): Publisher<Void> {
        (definition as? ModuleTransaction)?.waitNanos = waitNanos
        return connection.beginTransaction(definition)
    }

    override fun unwrap(): Connection = connection
}

/**
 * When a pooled connection is retired: idle past [PoolTimings.maxIdleTime] (if set), or past its own lifetime, which
 * is [PoolTimings.maxLifeTime] less up to [PoolTimings.LIFETIME_SPREAD] of it, fixed per connection. The pool opens
 * all its connections together at start; without the spread they would all retire in one sweep, 30 minutes on.
 */
internal class Retirement(private val timings: PoolTimings) {
    fun lifetimeMillis(connection: Any): Long {
        val life = timings.maxLifeTime.toMillis()
        val share = Math.floorMod(System.identityHashCode(connection) * SPREAD_HASH, PERMILLE) / PERMILLE.toDouble()
        return life - (life * PoolTimings.LIFETIME_SPREAD * share).toLong()
    }

    fun due(connection: Connection, metadata: PooledRefMetadata): Boolean {
        val idleCut = timings.maxIdleTime?.toMillis()
        return (idleCut != null && metadata.idleTime() >= idleCut) || metadata.lifeTime() >= lifetimeMillis(connection)
    }

    private companion object {
        const val PERMILLE = 1000

        /** Spreads neighbouring identity hashes over the range (Knuth's multiplicative constant, as an Int). */
        const val SPREAD_HASH = -1640531535
    }
}

/** r2dbc-pool's acquire timeout: no connection came free within `maxAcquireTime`. */
internal fun Throwable.isAcquireTimeout(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is TimeoutException }

/**
 * Exposed's default logger prints every statement with its values filled in, which would put phone numbers and keys
 * in the log. This one prints the SQL with `?` placeholders, at DEBUG.
 */
internal object PlaceholderSqlLogger : SqlLogger {
    override fun log(context: StatementContext, transaction: Transaction) {
        if (sqlLog.isDebugEnabled) sqlLog.debug(context.sql(transaction))
    }
}
