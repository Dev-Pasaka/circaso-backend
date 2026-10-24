package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.HealthCheck
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.observability.api.Metrics
import com.circaso.observability.api.Telemetry
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraMetrics
import io.kandra.koin.kandraKoin
import io.kandra.ktor.Kandra
import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory
import io.kandra.ktor.kandra as kandraRuntime

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Kandra")

/**
 * Installs Kandra once, with the keyspaces the modules contribute and no default keyspace: Kandra refuses to
 * start if an entity forgets to name one (`@ScyllaTable(keyspace = …)`). Needs Koin installed first, and binds
 * every module's repositories into it (`kandraKoin`).
 *
 * Local runs let Kandra create keyspaces and tables (`AUTO_CREATE`); everywhere else it only validates
 * (`VALIDATE`), and `app migrate` owns every change. The database is reached with ScyllaDB Cloud's TLS and
 * credentials from `Secrets` (see [ScyllaSettings]).
 */
@OptIn(ExperimentalKandraApi::class)
fun Application.installDatabase(
    settings: ScyllaSettings,
    specs: List<KandraModuleSpec>,
    telemetry: Telemetry,
) {
    if (specs.isEmpty()) {
        // Kandra needs a keyspace to start; until a module contributes one there is nothing to connect to.
        log.info("No module declares a keyspace; Kandra is not installed")
        return
    }
    install(Kandra) {
        contactPoints = settings.contactPoints
        localDatacenter = settings.datacenter
        // no default keyspace: every entity names its own
        schemaMode = settings.schemaMode
        // ScyllaDB has no role_permissions table, so the check only costs round trips (and logs that it was skipped)
        validatePermissions = false
        replicationStrategy = settings.localReplication

        for (spec in specs) {
            keyspace(spec.keyspace) { autoCreate = settings.autoCreateKeyspaces }
            register(*spec.entities.toTypedArray())
        }

        ssl { enabled = settings.tls }
        auth { provider = settings.authProvider }
        debug { logSlowQueriesMs = 50 }
        metrics {
            enabled = true
            recorder =
                KandraMetrics { table, operation, durationMs ->
                    telemetry.record(
                        Metrics.KANDRA_QUERY_DURATION,
                        durationMs / 1000.0,
                        mapOf(
                            "table" to table,
                            "op" to operation,
                        ),
                    )
                }
        }
    }
    kandraKoin()
    getKoin().loadModules(listOf(scyllaHealthModule(this), kandraRuntimeModule(this)))
}

/** The Kandra runtime, so a module's data layer can write several tables in one batch. */
private fun kandraRuntimeModule(application: Application): Module =
    module {
        single { application.kandraRuntime }
    }

private fun scyllaHealthModule(application: Application): Module =
    module {
        single<HealthCheck>(named("scylla")) { ScyllaHealth(application) }
    }

class ScyllaHealth(private val application: Application) : HealthCheck {
    override val name = "scylla"

    override suspend fun check(): Resource<Unit> =
        if (application.kandraRuntime.isHealthy()) Resource.success(Unit) else Resource.error(SharedError.Internal)
}
