package com.circaso.app

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.impl.database.DatabaseMigrator
import com.circaso.infrastructure.impl.database.ScyllaSettings
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import net.logstash.logback.argument.StructuredArguments.kv
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

// A function, not a top-level val: a val would start logging when this file's class loads, before `main` has read
// `.env`, and the log format and levels set there would arrive too late.
private fun log() = LoggerFactory.getLogger("com.circaso.app.Main")

/**
 * Starts the server: an embedded Netty server, configured from environment variables. For local development those
 * come from `.env` (see `.env.example`); a variable really set in the environment wins over the file.
 */
fun main() {
    val env = DotEnv.environment() // before anything logs
    val app = AppConfig.fromEnv(env)
    log().info("Starting", kv("environment", app.environment), kv("version", app.version), kv("port", app.port))
    val server =
        embeddedServer(Netty, port = app.port, host = app.host) {
            circasoServe(app, EnvConfig(env), EnvSecrets(env))
        }
    server.start(wait = false) // returns once the port is bound, so what is printed next is true
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000) })
    log().info(
        "Application started",
        kv("url", Banner.url(app.host, app.port)),
        kv("environment", app.environment),
        kv("version", app.version),
    )
    Banner.print(app, env["LOG_FORMAT"])
    Thread.currentThread().join() // until the process is stopped; the hook above closes the server
}

/**
 * The migration entry point: `./gradlew :app:migrate`, or the same image started with this main class. Runs each
 * module's migrations once, then exits. Containers never migrate at boot, so two of them can't race on one keyspace;
 * a failed migration fails this command, which stops the deploy while the old containers keep serving.
 */
object Migrate {
    @JvmStatic
    fun main(args: Array<String>) {
        val env = DotEnv.environment()
        val applied =
            try {
                migrate(EnvConfig(env), EnvSecrets(env))
            } catch (e: Exception) {
                log().error("Migrations failed", e)
                exitProcess(1)
            }
        log().info("Migrations applied", kv("count", applied))
    }
}

/** Runs the migrations of every module and returns how many were applied. */
fun migrate(config: Config, secrets: Secrets): Int {
    // The specs are plain values in each module's Koin module; nothing else of the module is started.
    val specs = koinApplication { modules(businessModules(config)) }.koin.getAll<KandraModuleSpec>()
    val outcome = DatabaseMigrator(ScyllaSettings(config, secrets)).migrate(specs)
    // on the app logger, so the deploy's log tail shows a created keyspace whatever LOG_LEVEL_DATABASE is
    log().info(
        "Migrations done",
        kv("keyspaces", specs.size),
        kv("applied", outcome.applied),
        kv("created", outcome.createdKeyspaces),
    )
    return outcome.applied
}
