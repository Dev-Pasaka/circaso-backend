package com.circaso.ops.di

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.integrations.api.EmailTemplateIds
import com.circaso.ops.api.StaffDirectory
import com.circaso.ops.data.repositories.AuditRepositoryImpl
import com.circaso.ops.data.repositories.EmailTemplateRepositoryImpl
import com.circaso.ops.data.repositories.LoggedEmailTemplateWarnings
import com.circaso.ops.data.repositories.StaffRepositoryImpl
import com.circaso.ops.data.tables.AuditLogTable
import com.circaso.ops.data.tables.EmailTemplatesTable
import com.circaso.ops.data.tables.StaffTable
import com.circaso.ops.domain.ports.EmailTemplateWarnings
import com.circaso.ops.domain.repositories.AuditRepository
import com.circaso.ops.domain.repositories.EmailTemplateRepository
import com.circaso.ops.domain.repositories.StaffRepository
import com.circaso.ops.domain.support.AiPricing
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.EmailTemplateBook
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.domain.support.StaffRoster
import com.circaso.ops.domain.support.TicketViews
import com.circaso.ops.domain.support.TransferParties
import com.circaso.ops.domain.support.UserViews
import com.circaso.ops.domain.usecases.AddFeeDestination
import com.circaso.ops.domain.usecases.AddStaff
import com.circaso.ops.domain.usecases.AssignTicket
import com.circaso.ops.domain.usecases.BlockRampUser
import com.circaso.ops.domain.usecases.ChangeFeeRule
import com.circaso.ops.domain.usecases.ChangeLimitRule
import com.circaso.ops.domain.usecases.ChangeTicketStatus
import com.circaso.ops.domain.usecases.CountPendingSignUps
import com.circaso.ops.domain.usecases.CreateFeeRule
import com.circaso.ops.domain.usecases.CreateLimitRule
import com.circaso.ops.domain.usecases.CreateSupportAccount
import com.circaso.ops.domain.usecases.DecideRampMerchant
import com.circaso.ops.domain.usecases.DeletePendingSignUps
import com.circaso.ops.domain.usecases.EndFeeRule
import com.circaso.ops.domain.usecases.EndLimitRule
import com.circaso.ops.domain.usecases.FindDiagnosticLogs
import com.circaso.ops.domain.usecases.GetAiUsageReport
import com.circaso.ops.domain.usecases.GetCallSheet
import com.circaso.ops.domain.usecases.GetDashboard
import com.circaso.ops.domain.usecases.GetEmailTemplateId
import com.circaso.ops.domain.usecases.GetFeeWallets
import com.circaso.ops.domain.usecases.GetFinanceReport
import com.circaso.ops.domain.usecases.GetGroup
import com.circaso.ops.domain.usecases.GetRampBlocks
import com.circaso.ops.domain.usecases.GetRampEngineSettings
import com.circaso.ops.domain.usecases.GetRampMerchantOrders
import com.circaso.ops.domain.usecases.GetRampMerchantSecurity
import com.circaso.ops.domain.usecases.GetRampMerchantSignals
import com.circaso.ops.domain.usecases.GetRampMerchantSummary
import com.circaso.ops.domain.usecases.GetRampMerchantWithdrawals
import com.circaso.ops.domain.usecases.GetRampMerchants
import com.circaso.ops.domain.usecases.GetRampOverview
import com.circaso.ops.domain.usecases.GetRampPreviewAccess
import com.circaso.ops.domain.usecases.GetRampPricing
import com.circaso.ops.domain.usecases.GetRampRates
import com.circaso.ops.domain.usecases.GetRampReviewOrders
import com.circaso.ops.domain.usecases.GetRampRoutingDecision
import com.circaso.ops.domain.usecases.GetRampRoutingDecisions
import com.circaso.ops.domain.usecases.GetRampSecuritySettings
import com.circaso.ops.domain.usecases.GetRampSettings
import com.circaso.ops.domain.usecases.GetRampUserVerification
import com.circaso.ops.domain.usecases.GetTicket
import com.circaso.ops.domain.usecases.GetTransaction
import com.circaso.ops.domain.usecases.GetUserAccount
import com.circaso.ops.domain.usecases.GetUserDevices
import com.circaso.ops.domain.usecases.GetUserWallets
import com.circaso.ops.domain.usecases.GetWalletNetworks
import com.circaso.ops.domain.usecases.ListAudit
import com.circaso.ops.domain.usecases.ListEmailTemplates
import com.circaso.ops.domain.usecases.ListFeeDestinations
import com.circaso.ops.domain.usecases.ListFeeRules
import com.circaso.ops.domain.usecases.ListFeeWithdrawals
import com.circaso.ops.domain.usecases.ListGroups
import com.circaso.ops.domain.usecases.ListLimitRules
import com.circaso.ops.domain.usecases.ListPermissions
import com.circaso.ops.domain.usecases.ListStaff
import com.circaso.ops.domain.usecases.ListSupportAccounts
import com.circaso.ops.domain.usecases.ListTickets
import com.circaso.ops.domain.usecases.ListTransactions
import com.circaso.ops.domain.usecases.ListUserCalls
import com.circaso.ops.domain.usecases.ListUserGroups
import com.circaso.ops.domain.usecases.ListUserTickets
import com.circaso.ops.domain.usecases.ListUserTransfers
import com.circaso.ops.domain.usecases.NoteOnTicket
import com.circaso.ops.domain.usecases.OpenDiagnosticLog
import com.circaso.ops.domain.usecases.RecordDevLogin
import com.circaso.ops.domain.usecases.RemoveFeeDestination
import com.circaso.ops.domain.usecases.ReplyToTicket
import com.circaso.ops.domain.usecases.ResetRampMerchantVerification
import com.circaso.ops.domain.usecases.ResetRampUserVerification
import com.circaso.ops.domain.usecases.ResolveRampOrder
import com.circaso.ops.domain.usecases.SearchUsers
import com.circaso.ops.domain.usecases.SetEmailTemplate
import com.circaso.ops.domain.usecases.SetRampMerchantTier
import com.circaso.ops.domain.usecases.SetRampPreviewAccess
import com.circaso.ops.domain.usecases.SetUserNetworkAccess
import com.circaso.ops.domain.usecases.SignInStaff
import com.circaso.ops.domain.usecases.SimulateRampRouting
import com.circaso.ops.domain.usecases.SuspendUser
import com.circaso.ops.domain.usecases.UnblockRampUser
import com.circaso.ops.domain.usecases.UnlockRampMerchantWithdrawals
import com.circaso.ops.domain.usecases.UnsuspendUser
import com.circaso.ops.domain.usecases.UpdateFeeBounds
import com.circaso.ops.domain.usecases.UpdateRampEngineSettings
import com.circaso.ops.domain.usecases.UpdateRampPricing
import com.circaso.ops.domain.usecases.UpdateRampSecuritySettings
import com.circaso.ops.domain.usecases.UpdateRampSettings
import com.circaso.ops.domain.usecases.UpdateStaff
import com.circaso.ops.domain.usecases.UpdateWalletNetwork
import com.circaso.ops.domain.usecases.WithdrawFees
import com.circaso.ops.presentation.api.StaffDirectoryImpl
import com.circaso.ops.presentation.api.StoredEmailTemplateIds
import com.circaso.ops.presentation.controllers.aiUsageController
import com.circaso.ops.presentation.controllers.callController
import com.circaso.ops.presentation.controllers.dashboardController
import com.circaso.ops.presentation.controllers.diagnosticController
import com.circaso.ops.presentation.controllers.emailTemplateController
import com.circaso.ops.presentation.controllers.feeController
import com.circaso.ops.presentation.controllers.feeWalletController
import com.circaso.ops.presentation.controllers.groupController
import com.circaso.ops.presentation.controllers.meController
import com.circaso.ops.presentation.controllers.moneyController
import com.circaso.ops.presentation.controllers.networkController
import com.circaso.ops.presentation.controllers.rampController
import com.circaso.ops.presentation.controllers.rampEngineController
import com.circaso.ops.presentation.controllers.rampRatesController
import com.circaso.ops.presentation.controllers.rampSecurityController
import com.circaso.ops.presentation.controllers.staffController
import com.circaso.ops.presentation.controllers.ticketController
import com.circaso.ops.presentation.controllers.userController
import com.circaso.ops.presentation.services.AiUsageService
import com.circaso.ops.presentation.services.AuditService
import com.circaso.ops.presentation.services.CallService
import com.circaso.ops.presentation.services.DashboardService
import com.circaso.ops.presentation.services.DiagnosticService
import com.circaso.ops.presentation.services.EmailTemplateService
import com.circaso.ops.presentation.services.FeeService
import com.circaso.ops.presentation.services.FeeWalletService
import com.circaso.ops.presentation.services.GroupService
import com.circaso.ops.presentation.services.MeService
import com.circaso.ops.presentation.services.MoneyService
import com.circaso.ops.presentation.services.NetworkService
import com.circaso.ops.presentation.services.RampEngineService
import com.circaso.ops.presentation.services.RampRatesService
import com.circaso.ops.presentation.services.RampSecurityService
import com.circaso.ops.presentation.services.RampService
import com.circaso.ops.presentation.services.StaffDirectoryService
import com.circaso.ops.presentation.services.StaffService
import com.circaso.ops.presentation.services.SupportAccountService
import com.circaso.ops.presentation.services.TicketService
import com.circaso.ops.presentation.services.UserService
import com.circaso.web.OpsRouteContributor
import com.circaso.web.networkFiltered
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/** Schema `ops`: the staff list, the audit log and the email template ids. It publishes no events, so it has no outbox. */
internal val opsDatabaseSpec =
    DatabaseModuleSpec(
        schema = "ops",
        tables = listOf(StaffTable, AuditLogTable, EmailTemplatesTable),
        migrationsLocation = "classpath:db/ops",
    )

/**
 * The AI cost estimate's price table (decision 2026-11-08): `OPS_AI_PRICES` (`model=input/output` dollars per million
 * tokens, comma separated; default `claude-haiku-5-5=0.10/0.50`), `OPS_AI_CACHE_WRITE_MULTIPLIER` (1.25, the 5-minute
 * cache), `OPS_AI_CACHE_READ_MULTIPLIER` (0.1), `OPS_AI_WEB_SEARCH_PER_1000_USD` (10) and `OPS_AI_PRICES_AS_OF`
 * (2026-11-08, the day the defaults were Anthropic's list prices). A value that isn't one stops startup.
 */
fun aiPricing(config: Config): AiPricing {
    fun decimal(key: String, default: BigDecimal): BigDecimal =
        config.string(key)?.let { AiPricing.decimal(key, it.trim()) } ?: default
    return AiPricing(
        models = AiPricing.parseModels(config.string("ops.ai.prices", AiPricing.DEFAULT_MODELS)),
        cacheReadMultiplier = decimal("ops.ai.cache-read-multiplier", AiPricing.DEFAULT_CACHE_READ),
        cacheWriteMultiplier = decimal("ops.ai.cache-write-multiplier", AiPricing.DEFAULT_CACHE_WRITE),
        webSearchPer1000 = decimal("ops.ai.web-search-per-1000-usd", AiPricing.DEFAULT_WEB_SEARCH_PER_1000),
        asOf =
            config.string("ops.ai.prices-as-of")?.let {
                runCatching { LocalDate.parse(it.trim()) }.getOrNull()
                    ?: throw IllegalStateException("Configuration 'ops.ai.prices-as-of' must be a date (was '$it')")
            } ?: AiPricing.DEFAULT_AS_OF,
    )
}

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
        val pricing = aiPricing(config)
        val environment = config.environment.name.lowercase()

        single { settings }

        // data
        single(named("ops.spec")) { opsDatabaseSpec }
        single(named("ops.database")) { get<Databases>().scope(opsDatabaseSpec.schema) }
        single<StaffRepository> { StaffRepositoryImpl(get(named("ops.database"))) }
        single<AuditRepository> { AuditRepositoryImpl(get(named("ops.database"))) }
        single<EmailTemplateRepository> { EmailTemplateRepositoryImpl(get(named("ops.database"))) }
        single<EmailTemplateWarnings> { LoggedEmailTemplateWarnings() }

        // domain
        single { StaffRoster(get(), settings) }
        single { AuditTrail(get(), clock) }
        single { UserViews(get(), get(), get(), get()) }
        single { TicketViews(get(), get()) }
        single { TransferParties(get(), get()) }
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
        single { CountPendingSignUps(get()) }
        single { DeletePendingSignUps(get(), get()) }
        single { UnsuspendUser(get(), get(), get()) }
        single { GetUserWallets(get(), get()) }
        single { ListUserTransfers(get(), get(), get()) }
        single { ListTransactions(get(), get()) }
        single { GetTransaction(get(), get()) }
        single { GetFinanceReport(get(), settings) }
        single { GetAiUsageReport(get(), get(), get(), pricing, settings) }
        single { ListGroups(get(), get(), get()) }
        single { GetGroup(get(), get(), get()) }
        single { ListUserCalls(get()) }
        single { GetCallSheet(get(), get()) }
        single { FindDiagnosticLogs(get()) }
        single { OpenDiagnosticLog(get(), get()) }
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
        single { ListFeeRules(get()) }
        single { CreateFeeRule(get(), get()) }
        single { ChangeFeeRule(get(), get()) }
        single { EndFeeRule(get(), get()) }
        single { UpdateFeeBounds(get(), get()) }
        single { ListLimitRules(get()) }
        single { CreateLimitRule(get(), get()) }
        single { ChangeLimitRule(get(), get()) }
        single { EndLimitRule(get(), get()) }

        // presentation
        single { StaffDirectoryService(get(), get()) }
        single { MeService(get()) }
        single { DashboardService(get()) }
        single { UserService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { MoneyService(get(), get(), get(), get(), get()) }
        single { AiUsageService(get()) }
        single { GroupService(get(), get()) }
        single { TicketService(get(), get(), get(), get(), get(), get()) }
        single { CallService(get(), get()) }
        single { DiagnosticService(get(), get()) }
        single { StaffService(get(), get(), get()) }
        single { AuditService(get()) }
        single { SupportAccountService(get(), get()) }
        single { FeeService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { SetUserNetworkAccess(get(), get(), get(), get()) }
        single { GetWalletNetworks(get()) }
        single { UpdateWalletNetwork(get(), get()) }
        single { NetworkService(get(), get(), get()) }
        single { GetFeeWallets(get()) }
        single { ListFeeDestinations(get()) }
        single { AddFeeDestination(get(), get()) }
        single { RemoveFeeDestination(get(), get()) }
        single { ListFeeWithdrawals(get()) }
        single { WithdrawFees(get(), get()) }
        single { FeeWalletService(get(), get(), get(), get(), get(), get()) }
        // Email template ids (decision 2026-11-22): rows staff set, read back by the email vendor through
        // integrations:api's EmailTemplateIds, which this module answers in both processes
        single { EmailTemplateBook(get(), get(), get(), clock) }
        single { GetEmailTemplateId(get()) }
        single { ListEmailTemplates(get()) }
        single { SetEmailTemplate(get(), get(), get(), clock) }
        single { EmailTemplateService(get(), get(), get()) }
        single<EmailTemplateIds> { StoredEmailTemplateIds(get()) }
        // The ramp for staff (decision 2026-11-22), through ramp:api's RampAdmin
        single { GetRampMerchants(get()) }
        single { DecideRampMerchant(get(), get()) }
        single { GetRampSettings(get()) }
        single { UpdateRampSettings(get(), get()) }
        single { GetRampPreviewAccess(get()) }
        single { SetRampPreviewAccess(get(), get(), get()) }
        single { GetRampReviewOrders(get()) }
        single { GetRampOverview(get()) }
        single { GetRampMerchantSummary(get()) }
        single { GetRampMerchantOrders(get()) }
        single { GetRampMerchantWithdrawals(get()) }
        single { GetRampUserVerification(get(), get()) }
        single { GetRampBlocks(get()) }
        single { BlockRampUser(get(), get(), get()) }
        single { UnblockRampUser(get(), get()) }
        single { ResetRampUserVerification(get(), get()) }
        single { ResetRampMerchantVerification(get(), get()) }
        single { ResolveRampOrder(get(), get()) }
        single {
            RampService(
                getRampMerchants = get(),
                decideRampMerchant = get(),
                getRampSettings = get(),
                updateRampSettings = get(),
                getRampPreviewAccess = get(),
                setRampPreviewAccess = get(),
                getRampReviewOrders = get(),
                getRampOverview = get(),
                getRampMerchantSummary = get(),
                getRampMerchantOrders = get(),
                getRampUserVerification = get(),
                getRampBlocks = get(),
                blockRampUser = get(),
                unblockRampUser = get(),
                resetRampUserVerification = get(),
                resetRampMerchantVerification = get(),
                resolveRampOrder = get(),
                getRampMerchantWithdrawals = get(),
            )
        }
        // The merchant engine for staff (decision 2026-11-23), through ramp:api's RampEngineAdmin
        single { GetRampEngineSettings(get()) }
        single { UpdateRampEngineSettings(get(), get()) }
        single { GetRampRoutingDecisions(get()) }
        single { GetRampRoutingDecision(get()) }
        single { SimulateRampRouting(get()) }
        single { SetRampMerchantTier(get(), get()) }
        single { GetRampMerchantSignals(get()) }
        single { RampEngineService(get(), get(), get(), get(), get(), get(), get()) }
        // The ramp's rates for staff (decision 2026-11-24), through ramp:api's RampRatesAdmin
        single { GetRampPricing(get()) }
        single { UpdateRampPricing(get(), get()) }
        single { GetRampRates(get()) }
        single { RampRatesService(get(), get(), get()) }
        // Ramp merchant security for staff (2026-11-24), through ramp:api's RampSecurityAdmin
        single { GetRampMerchantSecurity(get()) }
        single { UnlockRampMerchantWithdrawals(get(), get()) }
        single { GetRampSecuritySettings(get()) }
        single { UpdateRampSecuritySettings(get(), get()) }
        single { RampSecurityService(get(), get(), get(), get()) }
        single<StaffDirectory> { StaffDirectoryImpl(get()) }
        single<OpsRouteContributor>(named("ops.routes")) {
            val me = get<MeService>()
            val dashboard = get<DashboardService>()
            val users = get<UserService>()
            val money = get<MoneyService>()
            val aiUsage = get<AiUsageService>()
            val groups = get<GroupService>()
            val tickets = get<TicketService>()
            val calls = get<CallService>()
            val diagnostics = get<DiagnosticService>()
            val staff = get<StaffService>()
            val audit = get<AuditService>()
            val supportAccounts = get<SupportAccountService>()
            val fees = get<FeeService>()
            val networks = get<NetworkService>()
            val feeWallets = get<FeeWalletService>()
            val ramp = get<RampService>()
            val rampEngine = get<RampEngineService>()
            val rampRates = get<RampRatesService>()
            val rampSecurity = get<RampSecurityService>()
            val emailTemplates = get<EmailTemplateService>()
            OpsRouteContributor { root ->
                // `?network=MAINNET|TESTNET` narrows any money read to one network; without it, both (2026-11-18).
                root.networkFiltered {
                    val route = this
                    route.meController(me, environment)
                    route.dashboardController(dashboard)
                    route.userController(users, money, settings, networks)
                    route.moneyController(money, settings)
                    route.aiUsageController(aiUsage)
                    route.groupController(groups, settings)
                    route.ticketController(tickets, settings)
                    route.callController(calls, settings)
                    route.diagnosticController(diagnostics, settings)
                    route.staffController(staff, audit, supportAccounts, settings)
                    route.feeController(fees)
                    route.networkController(networks)
                    route.feeWalletController(feeWallets, settings, clock)
                    route.rampController(ramp, settings, clock)
                    route.rampEngineController(rampEngine, settings, clock)
                    route.rampRatesController(rampRates)
                    route.rampSecurityController(rampSecurity)
                    route.emailTemplateController(emailTemplates)
                }
            }
        }
    }
