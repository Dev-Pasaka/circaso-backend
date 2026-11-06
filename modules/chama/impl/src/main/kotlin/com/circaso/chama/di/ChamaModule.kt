package com.circaso.chama.di

import com.circaso.chama.api.ChamaFrames
import com.circaso.chama.api.Chamas
import com.circaso.chama.data.cache.ChamaExpiry
import com.circaso.chama.data.cache.RealtimeFramesImpl
import com.circaso.chama.data.repositories.ChamaOutboxImpl
import com.circaso.chama.data.repositories.ContributionRepositoryImpl
import com.circaso.chama.data.repositories.GoalRepositoryImpl
import com.circaso.chama.data.repositories.RequestRepositoryImpl
import com.circaso.chama.data.repositories.SettingsRepositoryImpl
import com.circaso.chama.data.tables.ApprovalsTable
import com.circaso.chama.data.tables.ContributionsTable
import com.circaso.chama.data.tables.GoalsTable
import com.circaso.chama.data.tables.GroupSettingsTable
import com.circaso.chama.data.tables.RealtimeTable
import com.circaso.chama.data.tables.RequestsTable
import com.circaso.chama.domain.ports.ChamaOutbox
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.ports.RealtimeLog
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.repositories.SettingsRepository
import com.circaso.chama.domain.support.ChamaNames
import com.circaso.chama.domain.support.ChamaViews
import com.circaso.chama.domain.support.Members
import com.circaso.chama.domain.support.RequestFrames
import com.circaso.chama.domain.usecases.AskPayout
import com.circaso.chama.domain.usecases.AttachPayment
import com.circaso.chama.domain.usecases.DecidePayout
import com.circaso.chama.domain.usecases.ExpireRequests
import com.circaso.chama.domain.usecases.FindApprovedPayout
import com.circaso.chama.domain.usecases.GetChama
import com.circaso.chama.domain.usecases.GetChamaDigest
import com.circaso.chama.domain.usecases.GetChamaStatement
import com.circaso.chama.domain.usecases.GetGroupMoney
import com.circaso.chama.domain.usecases.HasGoal
import com.circaso.chama.domain.usecases.ListLedger
import com.circaso.chama.domain.usecases.ListMyChamas
import com.circaso.chama.domain.usecases.RecordTransfer
import com.circaso.chama.domain.usecases.RemoveGoal
import com.circaso.chama.domain.usecases.ReplayFrames
import com.circaso.chama.domain.usecases.SetGoal
import com.circaso.chama.domain.usecases.SetSettings
import com.circaso.chama.presentation.agent.agentController
import com.circaso.chama.presentation.api.ChamaPayoutApprovals
import com.circaso.chama.presentation.api.ChamaRealtimeReplay
import com.circaso.chama.presentation.api.ChamasImpl
import com.circaso.chama.presentation.controllers.chamaController
import com.circaso.chama.presentation.events.WalletEventHandler
import com.circaso.chama.presentation.services.ChamaAgentService
import com.circaso.chama.presentation.services.ChamaEventsService
import com.circaso.chama.presentation.services.ChamaService
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.shared.EventSubscription
import com.circaso.shared.RealtimeModule
import com.circaso.wallet.api.PayoutApprovals
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration

/** Schema `chama`: its tables, its Flyway scripts and its outbox. */
internal val chamaDatabaseSpec =
    DatabaseModuleSpec(
        schema = "chama",
        tables =
            listOf(
                GoalsTable,
                ContributionsTable,
                RequestsTable,
                ApprovalsTable,
                RealtimeTable,
                GroupSettingsTable,
            ),
        migrationsLocation = "classpath:db/chama",
        outbox = true,
    )

private const val REDIS_PREFIX = "cha"
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(5)
private val EXPIRE_INTERVAL: Duration = Duration.ofMinutes(10)

/** Chama's Koin bindings: data, domain, presentation, its `api`, its wallet-events consumer, its replay and its jobs. */
@Suppress("UNUSED_PARAMETER")
fun chamaModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()

        // data
        single(named("chama.spec")) { chamaDatabaseSpec }
        single(named("chama.database")) { get<Databases>().scope(chamaDatabaseSpec.schema) }
        single<GoalRepository> { GoalRepositoryImpl(get(named("chama.database"))) }
        single<ContributionRepository> { ContributionRepositoryImpl(get(named("chama.database"))) }
        single<RequestRepository> { RequestRepositoryImpl(get(named("chama.database"))) }
        single<SettingsRepository> { SettingsRepositoryImpl(get(named("chama.database"))) }
        single<ChamaOutbox> { ChamaOutboxImpl(get(named("chama.database"))) }
        single {
            RealtimeFramesImpl(
                get<RedisScopes>().scope(REDIS_PREFIX),
                get(named("chama.database")),
                get(),
                clock,
            )
        }
        single<FrameSender> { get<RealtimeFramesImpl>() }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }
        single(named("chama.purge-expired")) {
            val expiry = ChamaExpiry(get(named("chama.database")))
            ScheduledJob("chama.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }
        single(named("chama.expire-requests")) {
            val events = get<ChamaEventsService>()
            ScheduledJob("chama.expire-requests", EXPIRE_INTERVAL) { events.expire() }
        }

        // domain
        single { Members(get(), get()) }
        single { RequestFrames(get(), get(), get(), get()) }
        single { SetGoal(get(), get(), get(), get(), clock, get()) }
        single { ChamaViews(get(), get(), get(), get(), get(), get()) }
        single { ChamaNames(get()) }
        single { GetChama(get()) }
        single { ListLedger(get(), get()) }
        single { ListMyChamas(get(), get(), get()) }
        single { GetChamaDigest(get(), get()) }
        single { GetChamaStatement(get(), get()) }
        single { AskPayout(get(), get(), get(), clock, get(), get()) }
        single { DecidePayout(get(), get(), get(), clock) }
        single { AttachPayment(get(), get(), get(), get(), clock) }
        single { RecordTransfer(get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { ExpireRequests(get(), clock) }
        single { ReplayFrames(get()) }
        single { HasGoal(get()) }
        single { GetGroupMoney(get()) }
        single { RemoveGoal(get(), get(), get()) }
        single { SetSettings(get(), get(), get(), clock) }
        single { FindApprovedPayout(get()) }

        // presentation
        single { ChamaService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { ChamaEventsService(get(), get(), get(), get(), get(), get()) }
        single { ChamaAgentService(get(), get()) }
        single<RouteContributor>(named("chama.routes")) {
            val chamas = get<ChamaService>()
            val agent = get<ChamaAgentService>()
            RouteContributor { route ->
                route.chamaController(chamas)
                route.agentController(agent)
            }
        }
        single<EventSubscription>(named("chama.wallet-events")) { WalletEventHandler(get()).subscription() }
        single<Chamas> { ChamasImpl(get()) }
        // wallet:api's guard: the wallet asks before it pays from a group wallet (decision 2026-11-03, option A).
        single<PayoutApprovals> { ChamaPayoutApprovals(get()) }
        single(named("chama.replay")) { RealtimeModule(ChamaFrames.MODULE, ChamaRealtimeReplay(get())) }
    }
