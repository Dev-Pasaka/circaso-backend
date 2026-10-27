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
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.SSL_MODE
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.SSL_ROOT_CERT
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider.TCP_KEEPALIVE
import io.r2dbc.postgresql.client.SSLMode
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.Option
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
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeoutException
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
 */
class Pool(val settings: PoolSettings, val connections: ConnectionPool) : AutoCloseable {
    val name: String get() = settings.name

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
            val factory = ConnectionFactories.get(connectionOptions(url, sslMode, sslRootCert, settings))
            val configuration =
                ConnectionPoolConfiguration
                    .builder(factory)
                    .name(settings.name)
                    .initialSize(minOf(settings.initialSize, settings.maxSize))
                    .maxSize(settings.maxSize)
                    .maxAcquireTime(settings.maxAcquireTime)
                    .maxIdleTime(timings.maxIdleTime)
                    .maxLifeTime(timings.maxLifeTime)
                    .validationQuery("SELECT 1")
                    .build()
            return Pool(settings, ConnectionPool(configuration))
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

    /** Opens both pools now and fills them to their initial size; fails if the database can't be reached. */
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
    override fun create(): Publisher<out Connection> = Mono.from(pool.create()).map { IgnoresStatementTimeout(it) }

    override fun getMetadata(): ConnectionFactoryMetadata = pool.metadata
}

private class IgnoresStatementTimeout(private val connection: Connection) :
    Connection by connection,
    Wrapped<Connection> {
    override fun setStatementTimeout(timeout: Duration): Publisher<Void> = Mono.empty()

    override fun unwrap(): Connection = connection
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
