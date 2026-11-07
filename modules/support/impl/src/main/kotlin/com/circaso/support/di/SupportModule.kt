package com.circaso.support.di

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.shared.EventSubscription
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeModule
import com.circaso.support.api.SupportDesk
import com.circaso.support.api.SupportFrames
import com.circaso.support.api.SupportTickets
import com.circaso.support.data.cache.RealtimeFramesImpl
import com.circaso.support.data.cache.RedisTicketAllowance
import com.circaso.support.data.repositories.DatabaseTransactor
import com.circaso.support.data.repositories.MessageRepositoryImpl
import com.circaso.support.data.repositories.SupportExpiry
import com.circaso.support.data.repositories.TicketRepositoryImpl
import com.circaso.support.data.tables.MessagesTable
import com.circaso.support.data.tables.RealtimeTable
import com.circaso.support.data.tables.TicketsTable
import com.circaso.support.domain.ports.FrameSender
import com.circaso.support.domain.ports.RealtimeLog
import com.circaso.support.domain.ports.TicketAllowance
import com.circaso.support.domain.ports.Transactor
import com.circaso.support.domain.repositories.MessageRepository
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.support.OrderedIds
import com.circaso.support.domain.support.SupportConfig
import com.circaso.support.domain.usecases.AddStaffNote
import com.circaso.support.domain.usecases.AssignTicket
import com.circaso.support.domain.usecases.ChangeTicketStatus
import com.circaso.support.domain.usecases.CountTickets
import com.circaso.support.domain.usecases.GetMyTicket
import com.circaso.support.domain.usecases.GetTicket
import com.circaso.support.domain.usecases.ListMyTickets
import com.circaso.support.domain.usecases.ListTicketQueue
import com.circaso.support.domain.usecases.ListUserTickets
import com.circaso.support.domain.usecases.OpenTicket
import com.circaso.support.domain.usecases.RemoveAccountTickets
import com.circaso.support.domain.usecases.ReplayFrames
import com.circaso.support.domain.usecases.ReplyAsStaff
import com.circaso.support.domain.usecases.ReplyAsUser
import com.circaso.support.presentation.api.SupportDeskImpl
import com.circaso.support.presentation.api.SupportRealtimeReplay
import com.circaso.support.presentation.api.SupportTicketsImpl
import com.circaso.support.presentation.controllers.ticketController
import com.circaso.support.presentation.events.IdentityEventHandler
import com.circaso.support.presentation.services.IdentityEventsService
import com.circaso.support.presentation.services.RealtimeService
import com.circaso.support.presentation.services.SupportDeskService
import com.circaso.support.presentation.services.TicketService
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration

/**
 * Schema `support`: tickets, their messages and the kept frames. It publishes no events, so it has no outbox: nothing
 * else reacts to a ticket, and the staff audit line is the ops module's.
 */
internal val supportDatabaseSpec =
    DatabaseModuleSpec(
        schema = "support",
        tables = listOf(TicketsTable, MessagesTable, RealtimeTable),
        migrationsLocation = "classpath:db/support",
    )

/** Redis prefix `sup:` (`sup:seq:{userId}`, `sup:opened:{userId}`), every key with a TTL. */
private const val REDIS_PREFIX = "sup"

/** `support.purge-expired`: the daily partitions of `support.realtime` (frames kept 7 days). */
private val PURGE_INTERVAL: Duration = Duration.ofHours(1)

/** Support's settings from `SUPPORT_*` (`.env.example` → support tickets). */
fun supportConfig(config: Config): SupportConfig {
    val defaults = SupportConfig()
    return SupportConfig(
        ticketsPerDay = config.int("SUPPORT_TICKETS_PER_DAY", defaults.ticketsPerDay.toInt()).toLong(),
    )
}

/**
 * Support's Koin bindings: its database spec and purge job, the app's routes (SU1-SU4), `SupportDesk` for the ops
 * module and `SupportTickets` for Asha, its `events:identity` subscription and its replay (`RealtimeModule`).
 */
fun supportModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val support = supportConfig(config)

        single { support }

        // data
        single(named("support.spec")) { supportDatabaseSpec }
        single(named("support.database")) { get<Databases>().scope(supportDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("support.database"))) }
        single<TicketRepository> { TicketRepositoryImpl(get(named("support.database"))) }
        single<MessageRepository> { MessageRepositoryImpl(get(named("support.database"))) }
        single<TicketAllowance> { RedisTicketAllowance(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single {
            RealtimeFramesImpl(
                get<RedisScopes>().scope(REDIS_PREFIX),
                get(named("support.database")),
                get<Realtime>(),
                clock,
            )
        }
        single<FrameSender> { get<RealtimeFramesImpl>() }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }
        single(named("support.purge-expired")) {
            val expiry = SupportExpiry(get(named("support.database")))
            ScheduledJob("support.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }

        // domain
        single { OrderedIds(clock) }
        single { OpenTicket(get(), get(), get(), get(), get(), support) }
        single { ListMyTickets(get()) }
        single { GetMyTicket(get(), get()) }
        single { ReplyAsUser(get(), get(), get(), get()) }
        single { ListTicketQueue(get()) }
        single { GetTicket(get(), get()) }
        single { ListUserTickets(get()) }
        single { ReplyAsStaff(get(), get(), get(), get(), get()) }
        single { AddStaffNote(get(), get(), get(), get()) }
        single { AssignTicket(get(), get(), clock) }
        single { ChangeTicketStatus(get(), get(), get(), clock) }
        single { CountTickets(get()) }
        single { RemoveAccountTickets(get()) }
        single { ReplayFrames(get()) }

        // presentation
        single { TicketService(get(), get(), get(), get()) }
        single { SupportDeskService(get(), get(), get(), get(), get(), get(), get(), get()) }
        single { IdentityEventsService(get()) }
        single { RealtimeService(get()) }
        single<SupportDesk> { SupportDeskImpl(get()) }
        single<SupportTickets> { SupportTicketsImpl(get()) }
        single<RouteContributor>(named("support.routes")) {
            val tickets = get<TicketService>()
            RouteContributor { route -> route.ticketController(tickets, support) }
        }
        single<EventSubscription>(named("support.identity-events")) { IdentityEventHandler(get()).subscription() }
        single(named("support.replay")) { RealtimeModule(SupportFrames.MODULE, SupportRealtimeReplay(get())) }
    }
