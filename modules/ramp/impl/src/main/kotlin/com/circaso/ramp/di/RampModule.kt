package com.circaso.ramp.di

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.OpsJob
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.CollectionNotifications
import com.circaso.integrations.api.CustodySetups
import com.circaso.integrations.api.DEV_TREASURY
import com.circaso.integrations.api.KycNotifications
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.ramp.api.Ramp
import com.circaso.ramp.api.RampAdmin
import com.circaso.ramp.api.RampEngineAdmin
import com.circaso.ramp.api.RampFrames
import com.circaso.ramp.data.cache.NodeTradingSessions
import com.circaso.ramp.data.cache.RampExpiry
import com.circaso.ramp.data.cache.RealtimeFramesImpl
import com.circaso.ramp.data.cache.RedisCounters
import com.circaso.ramp.data.cache.RedisPinAttempts
import com.circaso.ramp.data.cache.RedisSignInCodes
import com.circaso.ramp.data.repositories.BlockRepositoryImpl
import com.circaso.ramp.data.repositories.CommissionRepositoryImpl
import com.circaso.ramp.data.repositories.CredentialRepositoryImpl
import com.circaso.ramp.data.repositories.DatabaseTransactor
import com.circaso.ramp.data.repositories.EngineSettingsRepositoryImpl
import com.circaso.ramp.data.repositories.InsightRepositoryImpl
import com.circaso.ramp.data.repositories.MerchantRepositoryImpl
import com.circaso.ramp.data.repositories.MerchantWalletRepositoryImpl
import com.circaso.ramp.data.repositories.OrderEventRepositoryImpl
import com.circaso.ramp.data.repositories.OrderRepositoryImpl
import com.circaso.ramp.data.repositories.PayoutHoldRepositoryImpl
import com.circaso.ramp.data.repositories.PreviewAccessRepositoryImpl
import com.circaso.ramp.data.repositories.QuoteRepositoryImpl
import com.circaso.ramp.data.repositories.RampOutboxImpl
import com.circaso.ramp.data.repositories.ReservationRepositoryImpl
import com.circaso.ramp.data.repositories.RevenueWalletRepositoryImpl
import com.circaso.ramp.data.repositories.RoutingLogRepositoryImpl
import com.circaso.ramp.data.repositories.SessionRepositoryImpl
import com.circaso.ramp.data.repositories.SettingsRepositoryImpl
import com.circaso.ramp.data.repositories.SettlementRepositoryImpl
import com.circaso.ramp.data.repositories.StatementRepositoryImpl
import com.circaso.ramp.data.repositories.TestPayoutRepositoryImpl
import com.circaso.ramp.data.repositories.TopUpRepositoryImpl
import com.circaso.ramp.data.repositories.VerificationRepositoryImpl
import com.circaso.ramp.data.support.EnvelopeVault
import com.circaso.ramp.data.support.HmacIdentityKeys
import com.circaso.ramp.data.support.HttpPromptTransport
import com.circaso.ramp.data.support.LoggedAlerts
import com.circaso.ramp.data.support.LoggedPayoutJournal
import com.circaso.ramp.data.support.MasterKeyWebhookSecrets
import com.circaso.ramp.data.support.ReferenceRatePricing
import com.circaso.ramp.data.support.SecureTokens
import com.circaso.ramp.data.support.SettingsUsdcContracts
import com.circaso.ramp.data.tables.BlocksTable
import com.circaso.ramp.data.tables.CommissionsTable
import com.circaso.ramp.data.tables.EngineSettingsHistoryTable
import com.circaso.ramp.data.tables.EngineSettingsTable
import com.circaso.ramp.data.tables.FeeSettlementsTable
import com.circaso.ramp.data.tables.MerchantCredentialsTable
import com.circaso.ramp.data.tables.MerchantSessionsTable
import com.circaso.ramp.data.tables.MerchantWalletsTable
import com.circaso.ramp.data.tables.MerchantsTable
import com.circaso.ramp.data.tables.OrderEventsTable
import com.circaso.ramp.data.tables.OrdersTable
import com.circaso.ramp.data.tables.PayoutHoldsTable
import com.circaso.ramp.data.tables.PreviewAccessTable
import com.circaso.ramp.data.tables.QuotesTable
import com.circaso.ramp.data.tables.RealtimeTable
import com.circaso.ramp.data.tables.ReservationsTable
import com.circaso.ramp.data.tables.RevenueWalletsTable
import com.circaso.ramp.data.tables.RoutingDecisionsTable
import com.circaso.ramp.data.tables.SettingsTable
import com.circaso.ramp.data.tables.TestPayoutsTable
import com.circaso.ramp.data.tables.TopUpsTable
import com.circaso.ramp.data.tables.VerificationsTable
import com.circaso.ramp.domain.ports.CredentialVault
import com.circaso.ramp.domain.ports.FrameSender
import com.circaso.ramp.domain.ports.IdentityKeys
import com.circaso.ramp.domain.ports.KesFloat
import com.circaso.ramp.domain.ports.KycGate
import com.circaso.ramp.domain.ports.PayoutJournal
import com.circaso.ramp.domain.ports.PinAttempts
import com.circaso.ramp.domain.ports.Pricing
import com.circaso.ramp.domain.ports.PromptTransport
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.RampCounters
import com.circaso.ramp.domain.ports.RampOutbox
import com.circaso.ramp.domain.ports.RampTokens
import com.circaso.ramp.domain.ports.RealtimeLog
import com.circaso.ramp.domain.ports.SignInCodes
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.ports.UsdcContracts
import com.circaso.ramp.domain.ports.WebhookSecretVault
import com.circaso.ramp.domain.repositories.BlockRepository
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.EngineSettingsRepository
import com.circaso.ramp.domain.repositories.InsightRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderEventRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.PayoutHoldRepository
import com.circaso.ramp.domain.repositories.PreviewAccessRepository
import com.circaso.ramp.domain.repositories.QuoteRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.ramp.domain.repositories.RevenueWalletRepository
import com.circaso.ramp.domain.repositories.RoutingLogRepository
import com.circaso.ramp.domain.repositories.SessionRepository
import com.circaso.ramp.domain.repositories.SettingsRepository
import com.circaso.ramp.domain.repositories.SettlementRepository
import com.circaso.ramp.domain.repositories.StatementRepository
import com.circaso.ramp.domain.repositories.TestPayoutRepository
import com.circaso.ramp.domain.repositories.TopUpRepository
import com.circaso.ramp.domain.repositories.VerificationRepository
import com.circaso.ramp.domain.support.Engine
import com.circaso.ramp.domain.support.EngineBook
import com.circaso.ramp.domain.support.EngineFacts
import com.circaso.ramp.domain.support.FeeSweeps
import com.circaso.ramp.domain.support.FloatWallets
import com.circaso.ramp.domain.support.Gates
import com.circaso.ramp.domain.support.MerchantInsights
import com.circaso.ramp.domain.support.MerchantStatsBook
import com.circaso.ramp.domain.support.MerchantTopUps
import com.circaso.ramp.domain.support.MerchantViews
import com.circaso.ramp.domain.support.OrderFlow
import com.circaso.ramp.domain.support.PayoutFees
import com.circaso.ramp.domain.support.PinGuard
import com.circaso.ramp.domain.support.Presence
import com.circaso.ramp.domain.support.ProviderBalancesReader
import com.circaso.ramp.domain.support.PurchaseRouting
import com.circaso.ramp.domain.support.Releases
import com.circaso.ramp.domain.support.RoutingSimulator
import com.circaso.ramp.domain.support.SaleFlow
import com.circaso.ramp.domain.support.SaleFunding
import com.circaso.ramp.domain.support.SalePayouts
import com.circaso.ramp.domain.support.SaleRefunds
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.ramp.domain.support.TestPayouts
import com.circaso.ramp.domain.support.Verifications
import com.circaso.ramp.domain.usecases.ApplyAsMerchant
import com.circaso.ramp.domain.usecases.BlockUser
import com.circaso.ramp.domain.usecases.CallbackSigningSecret
import com.circaso.ramp.domain.usecases.CancelOrder
import com.circaso.ramp.domain.usecases.ChangePin
import com.circaso.ramp.domain.usecases.CreateQuote
import com.circaso.ramp.domain.usecases.DecideMerchant
import com.circaso.ramp.domain.usecases.EndSessions
import com.circaso.ramp.domain.usecases.GetEarnings
import com.circaso.ramp.domain.usecases.GetEngineSettings
import com.circaso.ramp.domain.usecases.GetMerchant
import com.circaso.ramp.domain.usecases.GetMerchantPerformance
import com.circaso.ramp.domain.usecases.GetMerchantSummary
import com.circaso.ramp.domain.usecases.GetMerchantVerification
import com.circaso.ramp.domain.usecases.GetOrder
import com.circaso.ramp.domain.usecases.GetRampOverview
import com.circaso.ramp.domain.usecases.GetRampStatus
import com.circaso.ramp.domain.usecases.GetRoutingDecision
import com.circaso.ramp.domain.usecases.GetSettings
import com.circaso.ramp.domain.usecases.GetStatement
import com.circaso.ramp.domain.usecases.GetTestPayout
import com.circaso.ramp.domain.usecases.GetTopUp
import com.circaso.ramp.domain.usecases.GetUserKyc
import com.circaso.ramp.domain.usecases.GetUserVerifications
import com.circaso.ramp.domain.usecases.GoOffline
import com.circaso.ramp.domain.usecases.GoOnline
import com.circaso.ramp.domain.usecases.HandleKycNotification
import com.circaso.ramp.domain.usecases.HandlePaymentCallback
import com.circaso.ramp.domain.usecases.HandlePayoutCallback
import com.circaso.ramp.domain.usecases.HandleSaleTransfer
import com.circaso.ramp.domain.usecases.HasOpenOrder
import com.circaso.ramp.domain.usecases.ListBlocks
import com.circaso.ramp.domain.usecases.ListCollectionProviders
import com.circaso.ramp.domain.usecases.ListKycProviders
import com.circaso.ramp.domain.usecases.ListMerchantOrders
import com.circaso.ramp.domain.usecases.ListMerchants
import com.circaso.ramp.domain.usecases.ListOrders
import com.circaso.ramp.domain.usecases.ListPreviewAccess
import com.circaso.ramp.domain.usecases.ListReviewOrders
import com.circaso.ramp.domain.usecases.ListRoutingDecisions
import com.circaso.ramp.domain.usecases.ListStaffMerchantOrders
import com.circaso.ramp.domain.usecases.ListTopUps
import com.circaso.ramp.domain.usecases.PruneRoutingLog
import com.circaso.ramp.domain.usecases.ReleaseOrders
import com.circaso.ramp.domain.usecases.ReplayFrames
import com.circaso.ramp.domain.usecases.RequestSignInCode
import com.circaso.ramp.domain.usecases.ResetMerchantVerification
import com.circaso.ramp.domain.usecases.ResetVerification
import com.circaso.ramp.domain.usecases.ResolveOrder
import com.circaso.ramp.domain.usecases.ResolveSession
import com.circaso.ramp.domain.usecases.RotateCallback
import com.circaso.ramp.domain.usecases.RunRechecks
import com.circaso.ramp.domain.usecases.SetMerchantOffers
import com.circaso.ramp.domain.usecases.SetMerchantTier
import com.circaso.ramp.domain.usecases.SetPayoutAccount
import com.circaso.ramp.domain.usecases.SetPreviewAccess
import com.circaso.ramp.domain.usecases.SetWebhookSecret
import com.circaso.ramp.domain.usecases.SettleFees
import com.circaso.ramp.domain.usecases.SignOut
import com.circaso.ramp.domain.usecases.SimulateRouting
import com.circaso.ramp.domain.usecases.StartMerchantKyc
import com.circaso.ramp.domain.usecases.StartOffRamp
import com.circaso.ramp.domain.usecases.StartOnRamp
import com.circaso.ramp.domain.usecases.StartTestPayout
import com.circaso.ramp.domain.usecases.StartTopUp
import com.circaso.ramp.domain.usecases.StartUserKyc
import com.circaso.ramp.domain.usecases.StorePaymentAccount
import com.circaso.ramp.domain.usecases.SweepOrders
import com.circaso.ramp.domain.usecases.SweepSales
import com.circaso.ramp.domain.usecases.SweepVerifications
import com.circaso.ramp.domain.usecases.UnblockUser
import com.circaso.ramp.domain.usecases.UpdateEngineSettings
import com.circaso.ramp.domain.usecases.UpdateSettings
import com.circaso.ramp.domain.usecases.VerifySignInCode
import com.circaso.ramp.presentation.api.RampAdminImpl
import com.circaso.ramp.presentation.api.RampCollectionNotifications
import com.circaso.ramp.presentation.api.RampEngineAdminImpl
import com.circaso.ramp.presentation.api.RampImpl
import com.circaso.ramp.presentation.api.RampKycNotifications
import com.circaso.ramp.presentation.api.RampRealtimeReplay
import com.circaso.ramp.presentation.controllers.merchantController
import com.circaso.ramp.presentation.controllers.rampController
import com.circaso.ramp.presentation.events.WalletEventHandler
import com.circaso.ramp.presentation.services.MerchantAuthService
import com.circaso.ramp.presentation.services.MerchantService
import com.circaso.ramp.presentation.services.RampAdminService
import com.circaso.ramp.presentation.services.RampEngineService
import com.circaso.ramp.presentation.services.RampEventsService
import com.circaso.ramp.presentation.services.RampOpsJobsService
import com.circaso.ramp.presentation.services.RampService
import com.circaso.shared.EventSubscription
import com.circaso.shared.Network
import com.circaso.shared.RealtimeModule
import com.circaso.shared.WalletNetwork
import com.circaso.web.RouteContributor
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/** Schema `ramp`: its tables, its Flyway scripts and its outbox. */
internal val rampDatabaseSpec =
    DatabaseModuleSpec(
        schema = "ramp",
        tables =
            listOf(
                SettingsTable,
                PreviewAccessTable,
                MerchantsTable,
                MerchantSessionsTable,
                MerchantWalletsTable,
                RevenueWalletsTable,
                MerchantCredentialsTable,
                QuotesTable,
                OrdersTable,
                ReservationsTable,
                CommissionsTable,
                FeeSettlementsTable,
                RealtimeTable,
                VerificationsTable,
                BlocksTable,
                OrderEventsTable,
                EngineSettingsTable,
                EngineSettingsHistoryTable,
                RoutingDecisionsTable,
                PayoutHoldsTable,
                TestPayoutsTable,
                TopUpsTable,
            ),
        migrationsLocation = "classpath:db/ramp",
        outbox = true,
    )

private const val REDIS_PREFIX = "rmp"
private val SWEEP_INTERVAL: Duration = Duration.ofSeconds(30)
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(5)
private val END_SESSIONS_INTERVAL: Duration = Duration.ofMinutes(1)
private val VERIFICATIONS_INTERVAL: Duration = Duration.ofMinutes(2)
private val RELEASE_INTERVAL: Duration = Duration.ofSeconds(5)
private val SETTLE_INTERVAL: Duration = Duration.ofMinutes(10)
private val PRUNE_ROUTING_INTERVAL: Duration = Duration.ofHours(1)
private val PAYOUTS_INTERVAL: Duration = Duration.ofSeconds(10)

/** Runs [job] once per network, each with that network as the coroutine's (decision 2026-11-18). */
private suspend fun onEachNetwork(job: suspend (Network) -> Int): Int =
    Network.entries.sumOf { network -> withContext(WalletNetwork(network)) { job(network) } }

/**
 * The ramp's Koin bindings: data, domain, presentation, its `api` (and the staff contract the ops module calls), the
 * listener integrations ring for a payment callback, its replay and its jobs.
 *
 * It reads no setting from the environment (decision 2026-11-18): everything staff can change lives in
 * `ramp.settings`, the collections and KYC providers included (decision 2026-11-22: integrations offers every
 * provider at once, and the ramp names the one the network's row says on each call). The one secret is
 * `RAMP_CREDENTIALS_MASTER_KEY`: the master half of the credential envelope, read when a merchant stores or unlocks a
 * payment account, and what the KYC identity key's own key is derived from, read when a live verification is
 * approved; the server starts without it.
 *
 * Both processes load the module. The app runs the routes, the callback and KYC listeners and the `ScheduledJob`s;
 * the ops
 * process runs the two `OpsJob`s, `ramp.release` and `ramp.settle-fees`, because only it can sign for the wallets
 * (decision 2026-11-22). The treasury they sign with is the real one where custody is Circle, and the named stand-in
 * (`DEV_TREASURY`) with `log` or `fake`; in the app's processes there is neither and the jobs are never collected.
 */
@Suppress("UNUSED_PARAMETER")
fun rampModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        // This process's name in `rmp:session:{merchantId}`: new on every start, so a restart holds nobody's keys.
        val nodeId = UUID.randomUUID().toString()

        // data
        single(named("ramp.spec")) { rampDatabaseSpec }
        single(named("ramp.database")) { get<Databases>().scope(rampDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("ramp.database"))) }
        single<RampOutbox> { RampOutboxImpl(get(named("ramp.database"))) }
        single<SettingsRepository> { SettingsRepositoryImpl(get(named("ramp.database"))) }
        single<PreviewAccessRepository> { PreviewAccessRepositoryImpl(get(named("ramp.database"))) }
        single<MerchantRepository> { MerchantRepositoryImpl(get(named("ramp.database"))) }
        single<MerchantWalletRepository> { MerchantWalletRepositoryImpl(get(named("ramp.database"))) }
        single<RevenueWalletRepository> { RevenueWalletRepositoryImpl(get(named("ramp.database"))) }
        single<CredentialRepository> { CredentialRepositoryImpl(get(named("ramp.database"))) }
        single<SessionRepository> { SessionRepositoryImpl(get(named("ramp.database"))) }
        single<QuoteRepository> { QuoteRepositoryImpl(get(named("ramp.database"))) }
        single<OrderRepository> { OrderRepositoryImpl(get(named("ramp.database"))) }
        single<PayoutHoldRepository> { PayoutHoldRepositoryImpl(get(named("ramp.database"))) }
        single<TestPayoutRepository> { TestPayoutRepositoryImpl(get(named("ramp.database"))) }
        single<TopUpRepository> { TopUpRepositoryImpl(get(named("ramp.database"))) }
        single<ReservationRepository> { ReservationRepositoryImpl(get(named("ramp.database"))) }
        single<CommissionRepository> { CommissionRepositoryImpl(get(named("ramp.database"))) }
        single<SettlementRepository> { SettlementRepositoryImpl(get(named("ramp.database"))) }
        single<StatementRepository> { StatementRepositoryImpl(get(named("ramp.database"))) }
        single<InsightRepository> { InsightRepositoryImpl(get(named("ramp.database"))) }
        single<VerificationRepository> { VerificationRepositoryImpl(get(named("ramp.database"))) }
        single<BlockRepository> { BlockRepositoryImpl(get(named("ramp.database"))) }
        single<OrderEventRepository> { OrderEventRepositoryImpl(get(named("ramp.database"))) }
        single<EngineSettingsRepository> { EngineSettingsRepositoryImpl(get(named("ramp.database"))) }
        single<RoutingLogRepository> { RoutingLogRepositoryImpl(get(named("ramp.database"))) }
        single<PromptTransport> { HttpPromptTransport() }
        single<RampCounters> { RedisCounters(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<PinAttempts> { RedisPinAttempts(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<SignInCodes> { RedisSignInCodes(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<TradingSessions> { NodeTradingSessions(get<RedisScopes>().scope(REDIS_PREFIX), nodeId, clock) }
        single {
            RealtimeFramesImpl(
                get<RedisScopes>().scope(REDIS_PREFIX),
                get(named("ramp.database")),
                get(),
                clock,
            )
        }
        single<FrameSender> { get<RealtimeFramesImpl>() }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }
        single<CredentialVault> {
            val secrets = get<Secrets>()
            EnvelopeVault(masterKey = { EnvelopeVault.masterKey(secrets.get(EnvelopeVault.MASTER_KEY_SECRET)) })
        }
        single<WebhookSecretVault> {
            val secrets = get<Secrets>()
            MasterKeyWebhookSecrets(
                masterKey = { EnvelopeVault.masterKey(secrets.get(EnvelopeVault.MASTER_KEY_SECRET)) },
            )
        }
        single<RampTokens> { SecureTokens() }
        single<IdentityKeys> {
            val secrets = get<Secrets>()
            HmacIdentityKeys(masterKey = { EnvelopeVault.masterKey(secrets.get(EnvelopeVault.MASTER_KEY_SECRET)) })
        }
        single<Pricing> { ReferenceRatePricing(get()) }
        single<RampAlerts> { LoggedAlerts() }
        single<UsdcContracts> {
            val koin = this
            SettingsUsdcContracts(setups = { koin.getOrNull<CustodySetups>() }, seeds = get())
        }

        // domain
        single { SettingsBook(get(), get(), get(), get(), clock) }
        single {
            Verifications(
                rows = get(),
                blocks = get(),
                providers = get(),
                identities = get(),
                settings = get(),
                transactor = get(),
                frames = get(),
                alerts = get(),
                clock = clock,
            )
        }
        single<KycGate> { get<Verifications>() }
        single { Gates(get(), get(), get()) }
        single { EngineBook(get(), get(), clock) }
        single { MerchantStatsBook(get(), get(), clock) }
        single { EngineFacts(get(), get(), get(), get(), get(), get(), get(), clock, get()) }
        single {
            Engine(
                facts = get(),
                book = get(),
                merchants = get(),
                wallets = get(),
                reservations = get(),
                commissions = get(),
                orders = get(),
                transactor = get(),
                log = get(),
                random = Random.Default,
                clock = clock,
                payoutHolds = get(),
            )
        }
        single {
            OrderFlow(
                orders = get(),
                reservations = get(),
                merchants = get(),
                transactor = get(),
                outbox = get(),
                bus = get(),
                frames = get(),
                counters = get(),
                alerts = get(),
                providers = get(),
                sessions = get(),
                settings = get(),
                presence = get(),
                transport = get(),
                clock = clock,
            )
        }
        single { Releases(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        // Selling (decision 2026-11-23).
        single { PayoutFees(get()) }
        single<PayoutJournal> { LoggedPayoutJournal() }
        single { ProviderBalancesReader(get(), get(), get(), clock, get()) }
        single<KesFloat> { get<ProviderBalancesReader>() }
        single { SaleFlow(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { SalePayouts(get(), get(), get(), get(), get(), get(), get(), get(), get(), clock, get()) }
        single { SaleFunding(get(), get(), get(), get(), get(), get(), get(), clock, get()) }
        single { SaleRefunds(get(), get(), get(), get(), clock) }
        single { TestPayouts(get(), get(), get(), get(), get(), get(), get(), clock, get()) }
        single { MerchantTopUps(get(), get(), get(), get(), get(), get(), get(), get(), clock, get()) }
        single { FloatWallets(get(), get(), get(), get(), get(), clock) }
        single { FeeSweeps(get(), get(), get(), get(), get(), get(), get(), clock) }
        single {
            MerchantViews(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                clock,
                get(),
                get(),
                get(),
            )
        }
        single { PinGuard(get(), get()) }
        single { Presence(get(), get(), get(), clock) }
        single { GetRampStatus(get(), get(), get(), get(), get()) }
        single { StartUserKyc(get(), get(), get()) }
        single { GetUserKyc(get(), get(), get()) }
        single { StartMerchantKyc(get(), get(), get()) }
        single { HandleKycNotification(get(), get(), get()) }
        single { SweepVerifications(get(), get(), get(), clock) }
        single { GetUserVerifications(get(), get()) }
        single { GetMerchantVerification(get(), get()) }
        single { ListBlocks(get()) }
        single { BlockUser(get(), get(), get(), clock) }
        single { UnblockUser(get()) }
        single { ResetVerification(get()) }
        single { ResetMerchantVerification(get(), get()) }
        single { ListKycProviders(get()) }
        single { CreateQuote(get(), get(), get(), get(), clock, get()) }
        single { PurchaseRouting(get(), get(), get(), clock) }
        single { StartOnRamp(get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { GetOrder(get(), get()) }
        single { ListOrders(get(), get()) }
        single { CancelOrder(get(), get(), get(), get()) }
        single { StartOffRamp(get(), get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { HandleSaleTransfer(get(), get(), get(), get()) }
        single { HandlePayoutCallback(get(), get(), get(), get()) }
        single { SweepSales(get(), get(), get(), get(), clock) }
        single { ResolveOrder(get(), get(), get(), get(), clock) }
        single { RunRechecks(get(), get(), get(), get()) }
        single { SetPayoutAccount(get(), get(), clock) }
        single { StartTestPayout(get()) }
        single { GetTestPayout(get()) }
        single { StartTopUp(get()) }
        single { GetTopUp(get()) }
        single { ListTopUps(get()) }
        single { HandlePaymentCallback(get(), get(), get(), get(), get()) }
        single { SweepOrders(get(), get(), clock) }
        single { ReplayFrames(get()) }
        single { HasOpenOrder(get()) }
        single { RequestSignInCode(get(), get(), get()) }
        single { VerifySignInCode(get(), get(), get(), get(), get(), clock) }
        single { ResolveSession(get(), get(), clock) }
        single { SignOut(get(), get(), get(), clock) }
        single { GetMerchant(get(), get(), get(), get(), get()) }
        single { ApplyAsMerchant(get(), get()) }
        single { StorePaymentAccount(get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { RotateCallback(get(), get(), get(), get()) }
        single { SetWebhookSecret(get(), get(), get(), clock) }
        single { CallbackSigningSecret(get(), get()) }
        single { GoOnline(get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { GoOffline(get(), clock) }
        single { ChangePin(get(), get(), clock) }
        single { ListMerchantOrders(get()) }
        single { EndSessions(get(), get(), get(), get(), clock, get()) }
        single { GetEarnings(get(), get(), get(), clock) }
        single { GetStatement(get(), clock) }
        single { ListMerchants(get(), get(), get(), get()) }
        single { DecideMerchant(get(), get(), get(), get(), get(), get(), clock) }
        single { GetSettings(get(), get()) }
        single { UpdateSettings(get(), get(), get(), get(), clock) }
        single { ListCollectionProviders(get()) }
        single { ListPreviewAccess(get()) }
        single { SetPreviewAccess(get(), clock) }
        single { ListReviewOrders(get(), get()) }
        single { MerchantInsights(get(), get()) }
        single { GetRampOverview(get(), get(), get(), clock) }
        single { GetMerchantSummary(get(), get(), get(), get(), get(), get(), get(), get(), get(), clock, get()) }
        single { ListStaffMerchantOrders(get(), get()) }
        single { GetEngineSettings(get(), get()) }
        single { UpdateEngineSettings(get(), get(), clock) }
        single { ListRoutingDecisions(get(), get()) }
        single { GetRoutingDecision(get()) }
        single { RoutingSimulator(get(), get(), get(), get(), get(), clock) }
        single { SimulateRouting(get()) }
        single { SetMerchantTier(get(), get(), get(), get()) }
        single { GetMerchantPerformance(get(), get(), get()) }
        single { SetMerchantOffers(get(), get()) }
        single { PruneRoutingLog(get(), get(), clock) }
        // The treasury only the ops process has: Circle's where custody is Circle, the named stand-in otherwise.
        single { ReleaseOrders(treasury(), get(), get(), get(), clock, get()) }
        single { SettleFees(treasury(), get()) }

        // presentation
        single { RampService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { MerchantAuthService(get(), get(), get(), get()) }
        single {
            MerchantService(
                getMerchant = get(),
                applyAsMerchant = get(),
                storePaymentAccount = get(),
                rotateCallbackUseCase = get(),
                goOnlineUseCase = get(),
                goOfflineUseCase = get(),
                changePinUseCase = get(),
                listMerchantOrders = get(),
                getEarnings = get(),
                getStatement = get(),
                startMerchantKyc = get(),
                getMerchantPerformance = get(),
                setMerchantOffers = get(),
                setPayoutAccount = get(),
                startTestPayout = get(),
                getTestPayout = get(),
                setWebhookSecret = get(),
                startTopUp = get(),
                getTopUp = get(),
                listTopUps = get(),
            )
        }
        single { RampEngineService(get(), get(), get(), get(), get(), get(), get(), get()) }
        single {
            RampEventsService(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
        single { RampOpsJobsService(get(), get()) }
        single {
            RampAdminService(
                listMerchants = get(),
                decideMerchant = get(),
                getSettings = get(),
                updateSettingsUseCase = get(),
                listPreviewAccess = get(),
                setPreviewAccessUseCase = get(),
                listReviewOrders = get(),
                listCollectionProviders = get(),
                getRampOverview = get(),
                getMerchantSummary = get(),
                listStaffMerchantOrders = get(),
                listKycProviders = get(),
                getUserVerifications = get(),
                getMerchantVerification = get(),
                listBlocks = get(),
                blockUser = get(),
                unblockUser = get(),
                resetVerification = get(),
                resetMerchantVerificationUseCase = get(),
                resolveOrder = get(),
            )
        }
        single<RouteContributor>(named("ramp.routes")) {
            val ramp = get<RampService>()
            val auth = get<MerchantAuthService>()
            val merchants = get<MerchantService>()
            RouteContributor { route ->
                route.rampController(ramp)
                route.merchantController(auth, merchants)
            }
        }
        single<Ramp> { RampImpl(get()) }
        single<RampAdmin> { RampAdminImpl(get()) }
        single<RampEngineAdmin> { RampEngineAdminImpl(get()) }
        single<CollectionNotifications> { RampCollectionNotifications(get()) }
        single<KycNotifications> { RampKycNotifications(get()) }
        single(named("ramp.replay")) { RealtimeModule(RampFrames.MODULE, RampRealtimeReplay(get())) }
        // The off-ramp: a confirmed W4 transfer that names a ramp order funds a sale (decision 2026-11-23).
        single<EventSubscription>(named("ramp.wallet-events")) { WalletEventHandler(get()).subscription() }

        // the app's jobs
        single(named("ramp.expire-orders")) {
            val events = get<RampEventsService>()
            ScheduledJob("ramp.expire-orders", SWEEP_INTERVAL) { onEachNetwork { events.sweep(it) } }
        }
        single(named("ramp.payouts")) {
            val events = get<RampEventsService>()
            ScheduledJob("ramp.payouts", PAYOUTS_INTERVAL) {
                onEachNetwork { events.sweepSales(it) } + events.rechecksAndTests()
            }
        }
        single(named("ramp.end-sessions")) {
            val events = get<RampEventsService>()
            ScheduledJob("ramp.end-sessions", END_SESSIONS_INTERVAL) { events.endSessions() }
        }
        single(named("ramp.sweep-verifications")) {
            val events = get<RampEventsService>()
            ScheduledJob("ramp.sweep-verifications", VERIFICATIONS_INTERVAL) { events.sweepVerifications() }
        }
        single(named("ramp.prune-routing")) {
            val engine = get<RampEngineService>()
            ScheduledJob("ramp.prune-routing", PRUNE_ROUTING_INTERVAL) { engine.pruneRoutingLog() }
        }
        single(named("ramp.purge-expired")) {
            val expiry = RampExpiry(get(named("ramp.database")))
            ScheduledJob("ramp.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }

        // the ops process's jobs: only it holds the secret that signs for the ramp's wallets
        single(named("ramp.release")) {
            val jobs = get<RampOpsJobsService>()
            OpsJob("ramp.release", RELEASE_INTERVAL) { onEachNetwork { jobs.release(it) } }
        }
        single(named("ramp.settle-fees")) {
            val jobs = get<RampOpsJobsService>()
            OpsJob("ramp.settle-fees", SETTLE_INTERVAL) { onEachNetwork { jobs.settle(it) } }
        }
    }

private fun Scope.treasury(): TreasuryCustody? =
    getOrNull<TreasuryCustody>() ?: getOrNull<TreasuryCustody>(named(DEV_TREASURY))
