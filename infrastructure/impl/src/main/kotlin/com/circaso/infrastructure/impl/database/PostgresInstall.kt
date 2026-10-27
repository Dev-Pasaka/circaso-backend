package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.HealthCheck
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.r2dbc.spi.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.time.Duration

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Postgres")

/**
 * Opens the app and jobs pools and checks that every module's migrations are applied: `serve` never migrates and never
 * creates a table, so a missing `Migrate` step must keep it out of service rather than fail later on a missing column.
 *
 * Both run in the background, started here: nothing in the application's start blocks a thread or waits on the
 * database (Ktor would also cancel a module that suspends past its startup timeout). They are retried until they pass
 * (the database may start after the app, or `Migrate` may still be running); until then readiness reports `postgres`
 * down, so the deploy never routes traffic to an instance whose database isn't ready. Closes the pools when the
 * application stops.
 */
fun Application.installPostgres(specs: List<DatabaseModuleSpec>) {
    val koin = getKoin()
    val pools = koin.get<PostgresPools>()
    koin.get<PostgresStartup>().begin(this) {
        pools.open()
        MigrationHistory(pools).requireCurrent(specs)
    }
    monitor.subscribe(ApplicationStopped) { pools.close() }
}

/**
 * The background start of [installPostgres]: retried with a backoff from [firstWait] doubling up to [longestWait],
 * logged at ERROR once per kind of failure (then at DEBUG, so a database that is down for an hour isn't an hour of
 * stack traces), and at INFO when it finally passes. [ready] answers for the latest attempt.
 */
class PostgresStartup(
    private val firstWait: Duration = Duration.ofSeconds(1),
    private val longestWait: Duration = Duration.ofSeconds(30),
) {
    private val passed = CompletableDeferred<Unit>()

    @Volatile
    private var began = false

    fun begin(scope: CoroutineScope, start: suspend () -> Unit) {
        began = true
        scope.launch {
            val reported = mutableSetOf<String>()
            var wait = firstWait
            while (true) {
                try {
                    start()
                    if (reported.isNotEmpty()) log.info("PostgreSQL is ready", kv("kind", "db.start"))
                    passed.complete(Unit)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val kind = "${e::class.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}"
                    if (reported.add(kind)) {
                        log.error("PostgreSQL is not ready; this instance stays out of service until it is", e)
                    } else {
                        log.debug("PostgreSQL still not ready", kv("kind", "db.start"), kv("reason", kind))
                    }
                }
                delay(wait.toMillis())
                wait = minOf(wait.multipliedBy(2), longestWait)
            }
        }
    }

    /** True once an attempt has passed; waits up to [wait] for one still trying. False if none began. */
    suspend fun ready(wait: Duration): Boolean {
        if (!began) return false
        return withTimeoutOrNull(wait.toMillis()) { passed.await() } != null
    }
}

/**
 * Compares each module's applied scripts (its `flyway_schema_history`, read over R2DBC as the module's role on the jobs
 * pool) with the `V<n>__<what>.sql` scripts this build ships. Checksums are Flyway's to check, at every `Migrate`.
 */
class MigrationHistory(private val pools: PostgresPools) {
    suspend fun requireCurrent(specs: List<DatabaseModuleSpec>) {
        val findings = specs.map { compare(it) }
        findings.flatMap { it.newer }.forEach { newer ->
            log.warn("applied script not in this build (a rollback?)", kv("kind", "db.migrate"), kv("script", newer))
        }
        val problems = findings.flatMap { it.problems }
        check(problems.isEmpty()) {
            "The database is not migrated to this build; run the Migrate step (locally ./gradlew :app:migrate):\n" +
                problems.joinToString("\n") { " - $it" }
        }
        specs.forEach { log.info("migrations current", kv("kind", "db.migrate"), kv("schema", it.schema)) }
    }

    /**
     * What stops the start (scripts this build ships that aren't applied, and failed runs) and what doesn't: scripts
     * applied but not in this build. Those are what a rollback to the previous image looks like, and the previous
     * image must still serve (migrations are additive), so they are only logged.
     */
    class Findings(val problems: List<String>, val newer: List<String>)

    suspend fun problems(spec: DatabaseModuleSpec): List<String> = compare(spec).problems

    suspend fun compare(spec: DatabaseModuleSpec): Findings {
        val shipped = MigrationScripts.versions(spec.migrationsLocation)
        val history =
            try {
                applied(spec.schema)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Most often the module's role doesn't exist yet, so the transaction can't switch to it.
                return Findings(listOf("${spec.schema}: never migrated (${e.message})"), emptyList())
            } ?: return Findings(listOf("${spec.schema}: never migrated (no history table)"), emptyList())
        val applied = history.filter { it.second }.map { it.first }.toSet()
        return Findings(
            problems =
                history.filterNot { it.second }.map { "${spec.schema} V${it.first}: failed" } +
                    (shipped - applied).sorted().map { "${spec.schema} V$it: not applied" },
            newer = (applied - shipped).sorted().map { "${spec.schema} V$it" },
        )
    }

    /** `(version, success)` per versioned row of the module's history, or null when it has no history table. */
    private suspend fun applied(schema: String): List<Pair<String, Boolean>>? =
        withContext(OnJobsPool) {
            val database = ModuleDatabase(schema, pools)
            database.tx("migrations.check") {
                val exists =
                    query(
                        "SELECT 1 FROM pg_tables WHERE schemaname = ? AND tablename = 'flyway_schema_history'",
                        schema,
                    ) { 1 }.isNotEmpty()
                if (!exists) return@tx null
                query(
                    "SELECT version, success::text FROM ${qualified(schema, "flyway_schema_history")} " +
                        "WHERE version IS NOT NULL",
                ) { MigrationScripts.normalise(it.text(0)) to (it.text(1) == "true") }
            }
        }
}

/** The versions of the Flyway scripts at a `classpath:` location, read from file names only (no database). */
internal object MigrationScripts {
    private val script = Regex("""V([0-9][0-9._]*)__.+\.sql""")

    fun versions(location: String): Set<String> {
        val path = location.removePrefix("classpath:").trim('/')
        val loader = Thread.currentThread().contextClassLoader ?: MigrationScripts::class.java.classLoader
        return loader
            .getResources(path)
            .toList()
            .flatMap { names(it, path) }
            .mapNotNull { script.matchEntire(it)?.groupValues?.get(1) }
            .map(::normalise)
            .toSet()
    }

    /** `001` and `1` are the same version, as are `1_1` and `1.1`: Flyway compares them as numbers. */
    fun normalise(version: String): String =
        version.split('.', '_').joinToString(".") { it.trimStart('0').ifEmpty { "0" } }

    private fun names(url: URL, path: String): List<String> =
        when (url.protocol) {
            "file" -> {
                File(url.toURI()).listFiles().orEmpty().map { it.name }
            }

            "jar" -> {
                // Not the shared cached jar: closing that would break class loading from it.
                (url.openConnection() as JarURLConnection).apply { useCaches = false }.jarFile.use { jar ->
                    jar
                        .entries()
                        .toList()
                        .map { it.name }
                        .filter { it.startsWith("$path/") && '/' !in it.removePrefix("$path/") }
                        .map { it.substringAfterLast('/') }
                }
            }

            else -> {
                emptyList()
            }
        }
}

/**
 * Readiness: the start has succeeded (pools open, migrations current), then `SELECT 1` through the app pool, failing if
 * no connection can be borrowed within the timeout.
 */
class PostgresHealth(private val pools: PostgresPools, private val startup: PostgresStartup) : HealthCheck {
    override val name = "postgres"

    override suspend fun check(): Resource<Unit> {
        if (!startup.ready(Duration.ofSeconds(5))) return Resource.error(SharedError.ServiceUnavailable)
        val pool = pools.app
        // usingWhen gives the connection back however the query ends, a timeout's cancellation included.
        val select1 =
            Mono.usingWhen(
                pool.connections.create(),
                { connection: Connection ->
                    Mono
                        .from(connection.createStatement("SELECT 1").execute())
                        .flatMap { result -> Mono.from(result.map { row, _ -> row.get(0) }) }
                },
                { connection: Connection -> connection.close() },
            )
        val answered = withTimeoutOrNull(pool.settings.statementTimeout.toMillis()) { select1.awaitFirstOrNull() }
        return if (answered != null) Resource.success(Unit) else Resource.error(SharedError.ServiceUnavailable)
    }
}
