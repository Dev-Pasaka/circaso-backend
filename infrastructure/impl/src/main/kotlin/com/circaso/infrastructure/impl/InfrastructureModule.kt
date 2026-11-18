package com.circaso.infrastructure.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.GrpcClients
import com.circaso.infrastructure.api.HealthCheck
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.ObjectStores
import com.circaso.infrastructure.api.OpsJob
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.infrastructure.api.Scheduler
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.api.WarmUp
import com.circaso.infrastructure.impl.database.PoolTouch
import com.circaso.infrastructure.impl.database.PostgresDatabases
import com.circaso.infrastructure.impl.database.PostgresHealth
import com.circaso.infrastructure.impl.database.PostgresOutboxes
import com.circaso.infrastructure.impl.database.PostgresPools
import com.circaso.infrastructure.impl.database.PostgresSettings
import com.circaso.infrastructure.impl.database.PostgresStartup
import com.circaso.infrastructure.impl.events.OutboxRelay
import com.circaso.infrastructure.impl.events.RedisEventBus
import com.circaso.infrastructure.impl.events.StreamConsumersHealth
import com.circaso.infrastructure.impl.grpc.PeerGrpcClients
import com.circaso.infrastructure.impl.http.VendorHttpClients
import com.circaso.infrastructure.impl.redis.LettuceRedisScopes
import com.circaso.infrastructure.impl.redis.RedisConnection
import com.circaso.infrastructure.impl.redis.RedisHealth
import com.circaso.infrastructure.impl.scheduler.RedisScheduler
import com.circaso.infrastructure.impl.storage.ConfiguredObjectStores
import com.circaso.shared.EventBus
import com.circaso.shared.EventSubscription
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Everything infrastructure hands out, as one Koin module: config and secrets, PostgreSQL (its pools and each module's
 * `Database`), Redis and its scopes, the scheduler, the event bus and its relay, the vendor HTTP and gRPC clients, and
 * the health checks. Needs `Telemetry` (observability) bound alongside. A module gets its `OutboxStore` from its
 * spec (`outbox = true`). Modules add their `EventSubscription`s,
 * which the event runtime collects when [startInfrastructure] runs.
 */
fun infrastructureModule(config: Config, secrets: Secrets) =
    module {
        single<Config> { config }
        single<Secrets> { secrets }
        single { PostgresSettings(get(), get()) }
        single { PostgresPools(get(), get()) }
        single<WarmUp>(named("database.pool-touch")) { PoolTouch(get()) }
        single { PostgresDatabases(get()) } bind Databases::class
        single {
            val koin = this
            PostgresOutboxes(get(), { koin.getAll<DatabaseModuleSpec>() })
        }

        single { RedisConnection(get<Config>().section("redis").string("url", "redis://localhost:6379")) }
        single { LettuceRedisScopes(get()) } bind RedisScopes::class

        single { RedisScheduler(get(), get()) } bind Scheduler::class

        single {
            val koin = this
            RedisEventBus(get(), { koin.get<PostgresOutboxes>().stores }, get())
        } bind EventBus::class
        single {
            val koin = this
            OutboxRelay(get(), { koin.get<PostgresOutboxes>().stores })
        }

        single { ConfiguredObjectStores(get<Config>(), secrets) } bind ObjectStores::class

        single { VendorHttpClients(get()) } bind HttpClients::class
        single { PeerGrpcClients(get()) } bind GrpcClients::class

        single { PostgresStartup() }
        single<HealthCheck>(named("postgres")) { PostgresHealth(get(), get()) }
        single<HealthCheck>(named("redis")) { RedisHealth(get()) }
        single<HealthCheck>(named("streams")) { StreamConsumersHealth(get()) }
    }

/**
 * Starts the runtime that needs a running application: subscribes every module's `EventSubscription`, registers
 * the outbox relay and every module's `ScheduledJob`, and starts the stream consumers and the scheduler. Stops them,
 * and closes Redis and the HTTP clients, when the application stops.
 */
fun Application.startInfrastructure() {
    val koin = getKoin()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + MDCContext())
    val bus = koin.get<RedisEventBus>()
    val scheduler = koin.get<RedisScheduler>()

    koin.getAll<EventSubscription>().forEach(bus::subscribe)
    koin.get<OutboxRelay>().register(scheduler)
    scheduler.registerAll(koin.getAll<ScheduledJob>())
    bus.start(scope)
    scheduler.start(scope)

    monitor.subscribe(ApplicationStopping) {
        bus.close()
        scheduler.close()
        scope.cancel()
    }
    monitor.subscribe(ApplicationStopped) {
        koin.get<VendorHttpClients>().close()
        koin.get<PeerGrpcClients>().close()
        koin.get<RedisConnection>().close()
    }
}

/** Hands each module's [ScheduledJob] to the scheduler, which runs it on one node at a time, on the jobs pool. */
internal fun Scheduler.registerAll(jobs: Collection<ScheduledJob>) {
    jobs.forEach { every(it.name, it.interval, it.job) }
}

/**
 * The ops gateway's process (decision 2026-11-08): the same connections, but no event consumers, no outbox relay and
 * none of the app's scheduled jobs, which the app's processes run. What the staff routes write still reaches the
 * streams: a module publishes right after its commit (`XADD`), and an event that misses it is swept by the app's
 * single-leader relay from the module's outbox.
 *
 * It does run the modules' [OpsJob]s (decision 2026-11-18: work that needs a secret only this process holds, the fee
 * wallets): each once now, then on its interval, one ops process at a time under the scheduler's Redis lock.
 */
fun Application.startOpsInfrastructure() {
    val koin = getKoin()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + MDCContext())
    val scheduler = koin.get<RedisScheduler>()
    val jobs = koin.getAll<OpsJob>()
    jobs.forEach { scheduler.every(it.name, it.interval, it.job) }
    scheduler.start(scope)
    scope.launchAtStart(scheduler, jobs)

    monitor.subscribe(ApplicationStopping) {
        scheduler.close()
        scope.cancel()
    }
    monitor.subscribe(ApplicationStopped) {
        koin.get<VendorHttpClients>().close()
        koin.get<PeerGrpcClients>().close()
        koin.get<RedisConnection>().close()
    }
}

/**
 * Runs each job once as the process starts, without waiting for its first tick; a failure is logged and the server
 * starts all the same (the tick tries again). Another ops process already running it is left to it.
 */
internal fun CoroutineScope.launchAtStart(scheduler: Scheduler, jobs: Collection<OpsJob>) {
    val log = LoggerFactory.getLogger("com.circaso.infrastructure.OpsJobs")
    jobs.forEach { job ->
        launch {
            try {
                val handled = scheduler.withLock("${job.name}.start", OPS_START_LEASE) { job.job() }
                log.info("ops job ran at start: job={} handled={}", job.name, handled ?: "skipped (running elsewhere)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("ops job failed at start: job={} error={}", job.name, e::class.simpleName)
            }
        }
    }
}

/** How long one start-up run may hold its lock before another ops process may try. */
private val OPS_START_LEASE: Duration = Duration.ofMinutes(5)
