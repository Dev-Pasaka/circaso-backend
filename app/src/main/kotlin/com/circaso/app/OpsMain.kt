package com.circaso.app

import com.circaso.gateway.gatewayModule
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.HealthCheck
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.impl.database.installPostgres
import com.circaso.infrastructure.impl.infrastructureModule
import com.circaso.infrastructure.impl.startOpsInfrastructure
import com.circaso.integrations.impl.integrationsModule
import com.circaso.observability.impl.observabilityModule
import com.circaso.opsgateway.installOpsGateway
import com.circaso.opsgateway.opsGatewayModule
import com.circaso.web.installJson
import com.circaso.web.installRequestId
import com.circaso.web.installStatusPages
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import net.logstash.logback.argument.StructuredArguments.kv
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory

private fun log() = LoggerFactory.getLogger("com.circaso.app.OpsMain")

/** The streams' consumers run in the app's processes only, so the ops process doesn't report on them. */
private const val STREAMS_CHECK = "streams"

/**
 * The ops gateway's process (decision 2026-11-08, Pasaka: "separate endpoint, separate management, don't mix them
 * together"): the same image as the app, started with this main class, on its own port (`OPS_PORT`, default 8081) and
 * host. It runs the business modules (staff routes reach them through their contracts) and the ops gateway, and
 * nothing of the app gateway's edge: no app routes, no `/v1/ws`, no webhooks, no event consumers, no scheduled jobs.
 * `./gradlew :app:runOps` serves it locally.
 */
object OpsMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val env = DotEnv.environment() // before anything logs
        val app = OpsConfig.fromEnv(env)
        log().info("Starting the ops gateway", kv("environment", app.environment), kv("port", app.port))
        val server =
            embeddedServer(Netty, port = app.port, host = app.host) {
                circasoOpsServe(EnvConfig(env), EnvSecrets(env))
            }
        server.start(wait = false)
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000) })
        log().info("Ops gateway started", kv("url", Banner.url(app.host, app.port)), kv("environment", app.environment))
        Thread.currentThread().join()
    }
}

/** The ops process's own address: `OPS_HOST` (default 0.0.0.0) and `OPS_PORT` (default 8081), never the app's. */
object OpsConfig {
    fun fromEnv(env: Map<String, String>): AppConfig {
        val app = AppConfig.fromEnv(env)
        val port =
            env["OPS_PORT"]?.let {
                it.toIntOrNull()?.takeIf { port -> port in 1..65535 }
                    ?: throw IllegalArgumentException("OPS_PORT must be 1-65535 (was '$it')")
            } ?: DEFAULT_PORT
        return app.copy(host = env["OPS_HOST"] ?: app.host, port = port)
    }

    const val DEFAULT_PORT = 8081
}

/**
 * Wires the ops process: Koin with the same infrastructure and business modules as `serve` (and the gateway's
 * `Realtime`, so a staff reply still reaches the user's socket or phone through Redis), PostgreSQL, then the web side
 * with the health routes and the ops gateway. [config] and [secrets] are where settings come from; tests pass their own.
 */
fun Application.circasoOpsServe(config: Config, secrets: Secrets) {
    install(Koin) {
        modules(
            observabilityModule,
            infrastructureModule(config, secrets),
            integrationsModule(config, secrets),
            gatewayModule(config),
            opsGatewayModule(config, secrets),
        )
        modules(businessModules(config))
    }
    val koin = getKoin()
    installPostgres(koin.getAll<DatabaseModuleSpec>())
    startOpsInfrastructure()
    installRequestId()
    installJson()
    installStatusPages()
    val checks = koin.getAll<HealthCheck>().filter { it.name != STREAMS_CHECK }
    routing { healthRoutes(checks) }
    installOpsGateway()
}
