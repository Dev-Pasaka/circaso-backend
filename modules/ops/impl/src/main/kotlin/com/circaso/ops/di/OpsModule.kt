package com.circaso.ops.di

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.ops.api.StaffDirectory
import com.circaso.ops.data.repositories.AuditRepositoryImpl
import com.circaso.ops.data.repositories.StaffRepositoryImpl
import com.circaso.ops.data.tables.AuditLogTable
import com.circaso.ops.data.tables.StaffTable
import com.circaso.ops.domain.repositories.AuditRepository
import com.circaso.ops.domain.repositories.StaffRepository
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.domain.support.StaffRoster
import com.circaso.ops.domain.support.TicketViews
import com.circaso.ops.domain.support.UserViews
import com.circaso.ops.domain.usecases.AddStaff
import com.circaso.ops.domain.usecases.AssignTicket
import com.circaso.ops.domain.usecases.ChangeTicketStatus
import com.circaso.ops.domain.usecases.CreateSupportAccount
import com.circaso.ops.domain.usecases.GetDashboard
import com.circaso.ops.domain.usecases.GetFinanceReport
import com.circaso.ops.domain.usecases.GetGroup
import com.circaso.ops.domain.usecases.GetTicket
import com.circaso.ops.domain.usecases.GetTransaction
import com.circaso.ops.domain.usecases.GetUserAccount
import com.circaso.ops.domain.usecases.GetUserDevices
import com.circaso.ops.domain.usecases.GetUserWallets
import com.circaso.ops.domain.usecases.ListAudit
import com.circaso.ops.domain.usecases.ListGroups
import com.circaso.ops.domain.usecases.ListPermissions
import com.circaso.ops.domain.usecases.ListStaff
import com.circaso.ops.domain.usecases.ListSupportAccounts
import com.circaso.ops.domain.usecases.ListTickets
import com.circaso.ops.domain.usecases.ListTransactions
import com.circaso.ops.domain.usecases.ListUserGroups
import com.circaso.ops.domain.usecases.ListUserTickets
import com.circaso.ops.domain.usecases.ListUserTransfers
import com.circaso.ops.domain.usecases.NoteOnTicket
import com.circaso.ops.domain.usecases.RecordDevLogin
import com.circaso.ops.domain.usecases.ReplyToTicket
import com.circaso.ops.domain.usecases.SearchUsers
import com.circaso.ops.domain.usecases.SignInStaff
import com.circaso.ops.domain.usecases.SuspendUser
import com.circaso.ops.domain.usecases.UnsuspendUser
import com.circaso.ops.domain.usecases.UpdateStaff
import com.circaso.ops.presentation.api.StaffDirectoryImpl
import com.circaso.ops.presentation.controllers.dashboardController
import com.circaso.ops.presentation.controllers.groupController
import com.circaso.ops.presentation.controllers.meController
import com.circaso.ops.presentation.controllers.moneyController
import com.circaso.ops.presentation.controllers.staffController
import com.circaso.ops.presentation.controllers.ticketController
import com.circaso.ops.presentation.controllers.userController
import com.circaso.ops.presentation.services.AuditService
import com.circaso.ops.presentation.services.DashboardService
import com.circaso.ops.presentation.services.GroupService
import com.circaso.ops.presentation.services.MeService
import com.circaso.ops.presentation.services.MoneyService
import com.circaso.ops.presentation.services.StaffDirectoryService
import com.circaso.ops.presentation.services.StaffService
import com.circaso.ops.presentation.services.SupportAccountService
import com.circaso.ops.presentation.services.TicketService
import com.circaso.ops.presentation.services.UserService
import com.circaso.web.OpsRouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock

/** Schema `ops`: the staff list and the audit log. It publishes no events, so it has no outbox. */
internal val opsDatabaseSpec =
    DatabaseModuleSpec(
        schema = "ops",
        tables = listOf(StaffTable, AuditLogTable),
        migrationsLocation = "classpath:db/ops",
    )

/** `OPS_BOOTSTRAP_ADMINS`: comma-separated emails, lowercased; always active ADMINs. */
fun opsSettings(config: Config): OpsSettings =
    OpsSettings(
        bootstrapAdmins =
            config
                .string("OPS_BOOTSTRAP_ADMINS")
                .orEmpty()
                .split(',')
                .map { StaffRoster.normalise(it) }
                .filter { it.isNotEmpty() }
                .toSet(),
    )

/**
 * The ops module's Koin bindings: its database spec, `StaffDirectory` for the ops gateway, and every staff route as one
 * [OpsRouteContributor]. Bound in both processes (so the app's `Migrate` and readiness see the schema), but only the
 * ops gateway mounts the routes: the app gateway collects `RouteContributor`s, never these.
 */
fun opsModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val settings = opsSettings(config)
        val environment = config.environment.name.lowercase()

        single { settings }

        // data
        single(named("ops.spec")) { opsDatabaseSpec }
        single(named("ops.database")) { get<Databases>().scope(opsDatabaseSpec.schema) }
        single<StaffRepository> { StaffRepositoryImpl(get(named("ops.database"))) }
        single<AuditRepository> { AuditRepositoryImpl(get(named("ops.database"))) }

        // domain
        single { StaffRoster(get(), settings) }
        single { AuditTrail(get(), clock) }
        single { UserViews(get(), get(), get()) }
        single { TicketViews(get(), get()) }
        single { SignInStaff(get()) }
        single { RecordDevLogin(get()) }
        single { ListPermissions() }
        single { ListStaff(get()) }
        single { AddStaff(get(), get(), get(), clock) }
        single { UpdateStaff(get(), get(), get(), clock) }
        single { SearchUsers(get(), get(), get()) }
        single { GetUserAccount(get()) }
        single { GetUserDevices(get()) }
        single { ListUserGroups(get(), get(), get(), get()) }
        single { ListUserTickets(get(), get(), get()) }
        single { SuspendUser(get(), get(), get()) }
        single { UnsuspendUser(get(), get(), get()) }
        single { GetUserWallets(get(), get()) }
        single { ListUserTransfers(get(), get(), get()) }
        single { ListTransactions(get(), get()) }
        single { GetTransaction(get(), get()) }
        single { GetFinanceReport(get(), settings) }
        single { ListGroups(get(), get(), get()) }
        single { GetGroup(get(), get(), get()) }
        single { ListTickets(get(), get()) }
        single { GetTicket(get(), get()) }
        single { ReplyToTicket(get(), get(), get()) }
        single { NoteOnTicket(get(), get(), get()) }
        single { AssignTicket(get(), get(), get(), get()) }
        single { ChangeTicketStatus(get(), get(), get()) }
        single { GetDashboard(get(), get(), get(), get(), settings, clock) }
        single { ListAudit(get()) }
        single { ListSupportAccounts(get()) }
        single { CreateSupportAccount(get(), get()) }

        // presentation
        single { StaffDirectoryService(get(), get()) }
        single { MeService(get()) }
        single { DashboardService(get()) }
        single { UserService(get(), get(), get(), get(), get(), get(), get()) }
        single { MoneyService(get(), get(), get(), get(), get()) }
        single { GroupService(get(), get()) }
        single { TicketService(get(), get(), get(), get(), get(), get()) }
        single { StaffService(get(), get(), get()) }
        single { AuditService(get()) }
        single { SupportAccountService(get(), get()) }
        single<StaffDirectory> { StaffDirectoryImpl(get()) }
        single<OpsRouteContributor>(named("ops.routes")) {
            val me = get<MeService>()
            val dashboard = get<DashboardService>()
            val users = get<UserService>()
            val money = get<MoneyService>()
            val groups = get<GroupService>()
            val tickets = get<TicketService>()
            val staff = get<StaffService>()
            val audit = get<AuditService>()
            val supportAccounts = get<SupportAccountService>()
            OpsRouteContributor { route ->
                route.meController(me, environment)
                route.dashboardController(dashboard)
                route.userController(users, money, settings)
                route.moneyController(money, settings)
                route.groupController(groups, settings)
                route.ticketController(tickets, settings)
                route.staffController(staff, audit, supportAccounts, settings)
            }
        }
    }
