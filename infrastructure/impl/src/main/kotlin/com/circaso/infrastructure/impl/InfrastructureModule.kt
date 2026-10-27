package com.circaso.infrastructure.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.GrpcClients
import com.circaso.infrastructure.api.HealthCheck
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.ObjectStores
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.Scheduler
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.impl.database.PostgresDatabases
import com.circaso.infrastructure.impl.database.PostgresHealth
import com.circaso.infrastructure.impl.database.PostgresPools
import com.circaso.infrastructure.impl.database.PostgresSettings
import com.circaso.infrastructure.impl.database.PostgresStartup
import com.circaso.infrastructure.impl.database.ScyllaSettings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.slf4j.MDCContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin

/**
 * Everything infrastructure hands out, as one Koin module: config and secrets, PostgreSQL (its pools and each
 * module's `Database`), Redis and its scopes, the scheduler, the event bus and its relay, the vendor HTTP and gRPC clients, and the health checks. Needs `Telemetry`
 * (observability) bound alongside. Modules add their own `OutboxStore` and `EventSubscription` bindings, which
 * the event runtime collects when [startInfrastructure] runs.
 */
fun infrastructureModule(config: Config, secrets: Secrets) =
    module {
        single<Config> { config }
        single<Secrets> { secrets }
        single { ScyllaSettings(get(), get()) }

        single { PostgresSettings(get(), get()) }
        single { PostgresPools(get(), get()) }
        single { PostgresDatabases(get()) } bind Databases::class

        single { RedisConnection(get<Config>().section("redis").string("url", "redis://localhost:6379")) }
        single { LettuceRedisScopes(get()) } bind RedisScopes::class

        single { RedisScheduler(get(), get()) } bind Scheduler::class

        single {
            val koin = this
            RedisEventBus(get(), { koin.getAll<OutboxStore>() }, get())
        } bind EventBus::class
        single {
            val koin = this
            OutboxRelay(get(), { koin.getAll<OutboxStore>() })
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
 * the outbox relay, and starts the stream consumers and the scheduler. Stops them, and closes Redis and the
 * HTTP clients, when the application stops.
 */
fun Application.startInfrastructure() {
    val koin = getKoin()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + MDCContext())
    val bus = koin.get<RedisEventBus>()
    val scheduler = koin.get<RedisScheduler>()

    koin.getAll<EventSubscription>().forEach(bus::subscribe)
    koin.get<OutboxRelay>().register(scheduler)
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
