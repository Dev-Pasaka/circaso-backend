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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory
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
 * database (Ktor would also cancel a module that suspends past its startup timeout). Until they succeed, readiness
 * reports `postgres` down, so the deploy never routes traffic to an instance whose database isn't ready; a failure is
 * logged at ERROR with what is missing. Closes the pools when the application stops.
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

/** The background start of [installPostgres]: done, still running, or failed (and why). */
class PostgresStartup {
    @Volatile
    private var job: Deferred<Unit>? = null

    fun begin(scope: CoroutineScope, start: suspend () -> Unit) {
        job =
            scope.async {
                try {
                    start()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("PostgreSQL is not ready; this instance stays out of service", e)
                    throw e
                }
            }
    }

    /** True once the start succeeded; waits up to [wait] for one still running. False if none began. */
    suspend fun ready(wait: Duration): Boolean {
        val started = job ?: return false
        return withTimeoutOrNull(wait.toMillis()) { runCatching { started.await() }.isSuccess } ?: false
    }
}

/**
 * Compares each module's applied scripts (its `flyway_schema_history`, read over R2DBC as the module's role on the jobs
 * pool) with the `V<n>__<what>.sql` scripts this build ships. Checksums are Flyway's to check, at every `Migrate`.
 */
class MigrationHistory(private val pools: PostgresPools) {
    suspend fun requireCurrent(specs: List<DatabaseModuleSpec>) {
        val problems = specs.flatMap { problems(it) }
        check(problems.isEmpty()) {
            "The database is not migrated to this build; run the Migrate step (locally ./gradlew :app:migrate):\n" +
                problems.joinToString("\n") { " - $it" }
        }
        specs.forEach { log.info("migrations current", kv("kind", "db.migrate"), kv("schema", it.schema)) }
    }

    /** Scripts not applied, applied scripts the build no longer has, and failed runs. */
    suspend fun problems(spec: DatabaseModuleSpec): List<String> {
        val shipped = MigrationScripts.versions(spec.migrationsLocation)
        val history =
            try {
                applied(spec.schema)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Most often the module's role doesn't exist yet, so the transaction can't switch to it.
                return listOf("${spec.schema}: never migrated (${e.message?.lineSequence()?.first()})")
            } ?: return listOf("${spec.schema}: never migrated (no history table)")
        val applied = history.filter { it.second }.map { it.first }.toSet()
        return history.filterNot { it.second }.map { "${spec.schema} V${it.first}: failed" } +
            (shipped - applied).sorted().map { "${spec.schema} V$it: not applied" } +
            (applied - shipped).sorted().map { "${spec.schema} V$it: applied, but not in this build" }
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
        val answered =
            withTimeoutOrNull(pool.settings.statementTimeout.toMillis()) {
                val connection: Connection = pool.connections.create().awaitFirst()
                try {
                    val result = connection.createStatement("SELECT 1").execute().awaitFirst()
                    result.map { row, _ -> row.get(0) }.awaitFirstOrNull() != null
                } finally {
                    connection.close().awaitFirstOrNull()
                }
            }
        return if (answered == true) Resource.success(Unit) else Resource.error(SharedError.ServiceUnavailable)
    }
}
