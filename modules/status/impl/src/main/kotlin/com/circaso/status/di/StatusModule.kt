package com.circaso.status.di

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.ObjectStores
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.shared.EventSubscription
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeModule
import com.circaso.status.api.StatusFrames
import com.circaso.status.data.cache.RealtimeFramesImpl
import com.circaso.status.data.cache.RedisKeyRequestThrottle
import com.circaso.status.data.repositories.DatabaseTransactor
import com.circaso.status.data.repositories.MediaRepositoryImpl
import com.circaso.status.data.repositories.MomentRepositoryImpl
import com.circaso.status.data.repositories.StatusExpiry
import com.circaso.status.data.storage.ObjectStoreMomentMedia
import com.circaso.status.data.tables.AudienceTable
import com.circaso.status.data.tables.MediaTable
import com.circaso.status.data.tables.MomentsTable
import com.circaso.status.data.tables.RealtimeTable
import com.circaso.status.domain.ports.FrameSender
import com.circaso.status.domain.ports.KeyRequestThrottle
import com.circaso.status.domain.ports.MediaStorage
import com.circaso.status.domain.ports.RealtimeLog
import com.circaso.status.domain.ports.Transactor
import com.circaso.status.domain.repositories.MediaRepository
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentReach
import com.circaso.status.domain.support.MomentRemoval
import com.circaso.status.domain.support.OrderedIds
import com.circaso.status.domain.support.StatusConfig
import com.circaso.status.domain.usecases.CompleteMomentMedia
import com.circaso.status.domain.usecases.DeleteMoment
import com.circaso.status.domain.usecases.GetMomentMediaDownload
import com.circaso.status.domain.usecases.ListMoments
import com.circaso.status.domain.usecases.ListViews
import com.circaso.status.domain.usecases.PostMoment
import com.circaso.status.domain.usecases.PrepareMomentMedia
import com.circaso.status.domain.usecases.PurgeMomentMedia
import com.circaso.status.domain.usecases.RemoveAccountMoments
import com.circaso.status.domain.usecases.ReplayFrames
import com.circaso.status.domain.usecases.RequestMomentKeys
import com.circaso.status.domain.usecases.ShareMomentKeyCopies
import com.circaso.status.domain.usecases.ViewMoment
import com.circaso.status.presentation.api.StatusRealtimeReplay
import com.circaso.status.presentation.controllers.momentController
import com.circaso.status.presentation.controllers.momentMediaController
import com.circaso.status.presentation.events.IdentityEventHandler
import com.circaso.status.presentation.services.IdentityEventsService
import com.circaso.status.presentation.services.MomentMediaService
import com.circaso.status.presentation.services.MomentService
import com.circaso.status.presentation.services.RealtimeService
import com.circaso.users.api.ReachPolicy
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

/** Schema `status`: its tables and its Flyway scripts. It publishes no events, so it has no outbox. */
internal val statusDatabaseSpec =
    DatabaseModuleSpec(
        schema = "status",
        tables = listOf(MomentsTable, AudienceTable, MediaTable, RealtimeTable),
        migrationsLocation = "classpath:db/status",
    )

/** Redis prefix `sts:` (`sts:seq:{userId}`, `sts:keyreq:{requester}:{poster}`). */
private const val REDIS_PREFIX = "sts"

/** `status.purge-expired` (Moments API → Retention): expired moments, their views and files, and the partitions. */
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(10)

/**
 * Status' Koin bindings: its database spec and sweep, moments (ST3-ST7) and their files (ST1, ST2, ST8), its
 * `events:identity` subscription and its replay (`RealtimeModule`).
 */
@Suppress("UNUSED_PARAMETER")
fun statusModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val status = StatusConfig()
        val background = backgroundScope()

        single { status }

        // data
        single(named("status.spec")) { statusDatabaseSpec }
        single(named("status.database")) { get<Databases>().scope(statusDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("status.database"))) }
        single<MomentRepository> { MomentRepositoryImpl(get(named("status.database"))) }
        single<MediaRepository> { MediaRepositoryImpl(get(named("status.database"))) }
        single<MediaStorage> { ObjectStoreMomentMedia(get<ObjectStores>().scope("status")) }
        single {
            RealtimeFramesImpl(
                get<RedisScopes>().scope(REDIS_PREFIX),
                get(named("status.database")),
                get<Realtime>(),
                clock,
            )
        }
        single<FrameSender> { get<RealtimeFramesImpl>() }
        single<KeyRequestThrottle> { RedisKeyRequestThrottle(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }
        single(named("status.purge-expired")) {
            val expiry = StatusExpiry(get(named("status.database")))
            val media = get<MomentMediaService>()
            ScheduledJob("status.purge-expired", PURGE_INTERVAL) { expiry.run() + media.purge() }
        }

        // domain
        single { MomentReach(get<ReachPolicy>()) }
        single { MomentRemoval(get(), get(), get(), get(), get(), clock) }
        single { PrepareMomentMedia(get(), get(), get(), status, clock) }
        single { CompleteMomentMedia(get(), get(), get(), clock) }
        single { GetMomentMediaDownload(get(), get(), get(), get(), status, clock) }
        single { PurgeMomentMedia(get(), get(), status, clock) }
        single { OrderedIds(clock) }
        single { PostMoment(get(), get(), get(), get(), get(), get(), background, status, clock) }
        single { ListMoments(get(), get(), clock) }
        single { ViewMoment(get(), get(), get(), get(), clock, profiles = getOrNull()) }
        single { ListViews(get(), clock) }
        single { DeleteMoment(get(), get(), clock) }
        single { RemoveAccountMoments(get(), get(), get()) }
        single { ReplayFrames(get()) }
        single { RequestMomentKeys(get(), get(), get(), get(), status, clock) }
        single { ShareMomentKeyCopies(get(), get(), get(), get(), status, clock) }

        // presentation
        single { MomentService(get(), get(), get(), get(), get(), get(), get()) }
        single { MomentMediaService(get(), get(), get(), get()) }
        single { IdentityEventsService(get()) }
        single { RealtimeService(get()) }
        single<RouteContributor>(named("status.routes")) {
            val moments = get<MomentService>()
            val media = get<MomentMediaService>()
            RouteContributor { route ->
                route.momentMediaController(media)
                route.momentController(moments, status.pageDefault, status.pageMax)
            }
        }
        single<EventSubscription>(named("status.identity-events")) { IdentityEventHandler(get()).subscription() }
        single(named("status.replay")) { RealtimeModule(StatusFrames.MODULE, StatusRealtimeReplay(get())) }
    }

/**
 * Where `status.posted` goes out after ST3 answers, so a 1,024-person audience doesn't hold the answer. A failure is
 * logged by its class only, never its message, which could carry ids.
 */
private fun backgroundScope(): CoroutineScope {
    val log = LoggerFactory.getLogger("com.circaso.status.background")
    val handler = CoroutineExceptionHandler { _, e -> log.warn("background work failed: error={}", e.javaClass.name) }
    return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
}
