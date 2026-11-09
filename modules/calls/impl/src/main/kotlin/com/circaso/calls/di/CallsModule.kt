package com.circaso.calls.di

import com.circaso.calls.api.CallFrames
import com.circaso.calls.api.Calls
import com.circaso.calls.data.cache.CallsExpiry
import com.circaso.calls.data.cache.RealtimeFramesImpl
import com.circaso.calls.data.repositories.CallRepositoryImpl
import com.circaso.calls.data.repositories.CallsOutboxImpl
import com.circaso.calls.data.repositories.DatabaseTransactor
import com.circaso.calls.data.tables.CallsTable
import com.circaso.calls.data.tables.ParticipantsTable
import com.circaso.calls.data.tables.RealtimeTable
import com.circaso.calls.data.tables.TracksTable
import com.circaso.calls.domain.ports.CallsOutbox
import com.circaso.calls.domain.ports.FrameSender
import com.circaso.calls.domain.ports.RealtimeLog
import com.circaso.calls.domain.ports.Transactor
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.CallChanges
import com.circaso.calls.domain.support.CallNotices
import com.circaso.calls.domain.support.CallViews
import com.circaso.calls.domain.support.People
import com.circaso.calls.domain.support.Sessions
import com.circaso.calls.domain.usecases.DeclineCall
import com.circaso.calls.domain.usecases.ExpireRings
import com.circaso.calls.domain.usecases.FindLiveCall
import com.circaso.calls.domain.usecases.GetCall
import com.circaso.calls.domain.usecases.GetLiveCall
import com.circaso.calls.domain.usecases.JoinCall
import com.circaso.calls.domain.usecases.LeaveCall
import com.circaso.calls.domain.usecases.PullTracks
import com.circaso.calls.domain.usecases.Renegotiate
import com.circaso.calls.domain.usecases.ReplayFrames
import com.circaso.calls.domain.usecases.ReportConnected
import com.circaso.calls.domain.usecases.SetMedia
import com.circaso.calls.domain.usecases.StartCall
import com.circaso.calls.presentation.api.CallsImpl
import com.circaso.calls.presentation.api.CallsRealtimeReplay
import com.circaso.calls.presentation.controllers.callController
import com.circaso.calls.presentation.services.CallEventsService
import com.circaso.calls.presentation.services.CallService
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.shared.RealtimeModule
import com.circaso.web.RouteContributor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/** Schema `calls`: its tables, its Flyway scripts and its outbox. */
internal val callsDatabaseSpec =
    DatabaseModuleSpec(
        schema = "calls",
        tables = listOf(CallsTable, ParticipantsTable, TracksTable, RealtimeTable),
        migrationsLocation = "classpath:db/calls",
        outbox = true,
    )

private const val REDIS_PREFIX = "cal"
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(5)
private val RING_TIMEOUT_INTERVAL: Duration = Duration.ofSeconds(15)

/** Calls' Koin bindings: data, domain, presentation, its `api`, its replay and its jobs. */
@Suppress("UNUSED_PARAMETER")
fun callsModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()

        // data
        single(named("calls.spec")) { callsDatabaseSpec }
        single(named("calls.database")) { get<Databases>().scope(callsDatabaseSpec.schema) }
        single<CallRepository> { CallRepositoryImpl(get(named("calls.database"))) }
        single<CallsOutbox> { CallsOutboxImpl(get(named("calls.database"))) }
        single<Transactor> { DatabaseTransactor(get(named("calls.database"))) }
        single {
            RealtimeFramesImpl(
                get<RedisScopes>().scope(REDIS_PREFIX),
                get(named("calls.database")),
                get(),
                clock,
            )
        }
        single<FrameSender> { get<RealtimeFramesImpl>() }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }
        single(named("calls.purge-expired")) {
            val expiry = CallsExpiry(get(named("calls.database")))
            ScheduledJob("calls.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }
        single(named("calls.ring-timeout")) {
            val events = get<CallEventsService>()
            ScheduledJob("calls.ring-timeout", RING_TIMEOUT_INTERVAL) { events.expireRings() }
        }

        // domain
        single { People(get(), get()) }
        single { CallViews(get(), get()) }
        single { CallNotices(get(), get(), get(), get(), backgroundScope()) }
        single { CallChanges(get(), get(), get(), get(), get()) }
        single { Sessions(get()) }
        single { StartCall(get(), get(), get(), get(), clock) }
        single { GetCall(get(), get(), get()) }
        single { GetLiveCall(get(), get(), get()) }
        single { FindLiveCall(get()) }
        single { JoinCall(get(), get(), get(), get(), get(), get(), clock) }
        single { PullTracks(get(), get(), get(), clock) }
        single { Renegotiate(get(), get(), get(), clock) }
        single { DeclineCall(get(), get(), clock) }
        single {
            com.circaso.calls.domain.usecases
                .RingAgain(get(), get(), get(), get(), clock)
        }
        single { LeaveCall(get(), get(), clock) }
        single { SetMedia(get(), clock) }
        single { ReportConnected(get(), clock) }
        single { ExpireRings(get(), get(), clock) }
        single { ReplayFrames(get()) }

        // presentation
        single { CallService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { CallEventsService(get(), get(), get()) }
        single<RouteContributor>(named("calls.routes")) {
            val calls = get<CallService>()
            RouteContributor { route -> route.callController(calls) }
        }
        single<Calls> { CallsImpl(get()) }
        single(named("calls.replay")) { RealtimeModule(CallFrames.MODULE, CallsRealtimeReplay(get())) }
    }

/**
 * Where the SFU clean-up of a hang-up or a replaced session runs, after the answer has gone (2026-11-09). A failure
 * is logged by its class only, never its message, which could carry a session id.
 */
private fun backgroundScope(): CoroutineScope {
    val log = LoggerFactory.getLogger("com.circaso.calls.background")
    val handler = CoroutineExceptionHandler { _, e -> log.warn("background work failed: error={}", e.javaClass.name) }
    return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
}
