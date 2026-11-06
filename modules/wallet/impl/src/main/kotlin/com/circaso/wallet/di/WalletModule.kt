package com.circaso.wallet.di

import com.circaso.identity.api.DeletionGuard
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.infrastructure.api.WarmUp
import com.circaso.integrations.api.CustodyNotifications
import com.circaso.shared.RealtimeModule
import com.circaso.wallet.api.WalletFrames
import com.circaso.wallet.api.Wallets
import com.circaso.wallet.data.cache.REDIS_PREFIX
import com.circaso.wallet.data.cache.RealtimeFramesImpl
import com.circaso.wallet.data.cache.RedisActiveWallets
import com.circaso.wallet.data.cache.RedisAddingChains
import com.circaso.wallet.data.cache.RedisBalanceCache
import com.circaso.wallet.data.cache.RedisPendingSetups
import com.circaso.wallet.data.cache.RedisReminders
import com.circaso.wallet.data.cache.RedisSetupChecks
import com.circaso.wallet.data.cache.RedisStuckNotices
import com.circaso.wallet.data.repositories.BundledCoinIcons
import com.circaso.wallet.data.repositories.ConfiguredFees
import com.circaso.wallet.data.repositories.DatabaseTransactor
import com.circaso.wallet.data.repositories.FeeLedgerRepositoryImpl
import com.circaso.wallet.data.repositories.FeeQuoteRepositoryImpl
import com.circaso.wallet.data.repositories.LogWalletWarnings
import com.circaso.wallet.data.repositories.NoPaymentHolds
import com.circaso.wallet.data.repositories.RuleRepositoryImpl
import com.circaso.wallet.data.repositories.TransactionRepositoryImpl
import com.circaso.wallet.data.repositories.TransferRepositoryImpl
import com.circaso.wallet.data.repositories.WalletExpiry
import com.circaso.wallet.data.repositories.WalletOutboxImpl
import com.circaso.wallet.data.repositories.WalletOwnerRepositoryImpl
import com.circaso.wallet.data.repositories.WalletRepositoryImpl
import com.circaso.wallet.data.tables.CircleUsersTable
import com.circaso.wallet.data.tables.FeeBoundsTable
import com.circaso.wallet.data.tables.FeeLedgerTable
import com.circaso.wallet.data.tables.FeeQuotesTable
import com.circaso.wallet.data.tables.FeeRulesTable
import com.circaso.wallet.data.tables.LimitRulesTable
import com.circaso.wallet.data.tables.RealtimeTable
import com.circaso.wallet.data.tables.TransactionsTable
import com.circaso.wallet.data.tables.TransfersTable
import com.circaso.wallet.data.tables.WalletOwnersTable
import com.circaso.wallet.data.tables.WalletsTable
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.JobSettings
import com.circaso.wallet.domain.models.ReconcilePass
import com.circaso.wallet.domain.ports.ActiveWallets
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.ports.BalanceCache
import com.circaso.wallet.domain.ports.FeeConfig
import com.circaso.wallet.domain.ports.FrameSender
import com.circaso.wallet.domain.ports.PaymentHolds
import com.circaso.wallet.domain.ports.PendingSetups
import com.circaso.wallet.domain.ports.RealtimeLog
import com.circaso.wallet.domain.ports.Reminders
import com.circaso.wallet.domain.ports.SetupChecks
import com.circaso.wallet.domain.ports.StuckNotices
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.CoinIconRepository
import com.circaso.wallet.domain.repositories.FeeLedgerRepository
import com.circaso.wallet.domain.repositories.FeeQuoteRepository
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletOutbox
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.Counterparties
import com.circaso.wallet.domain.support.CustodyUpdates
import com.circaso.wallet.domain.support.FeeCalculator
import com.circaso.wallet.domain.support.FeeQuotes
import com.circaso.wallet.domain.support.FeeSettlement
import com.circaso.wallet.domain.support.HeldFunds
import com.circaso.wallet.domain.support.HolderFrames
import com.circaso.wallet.domain.support.Holders
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.IncomingUpdates
import com.circaso.wallet.domain.support.Limits
import com.circaso.wallet.domain.support.OrderedIds
import com.circaso.wallet.domain.support.OutgoingUpdates
import com.circaso.wallet.domain.support.Payees
import com.circaso.wallet.domain.support.PaymentPlanner
import com.circaso.wallet.domain.support.Reconciler
import com.circaso.wallet.domain.support.SenderRules
import com.circaso.wallet.domain.support.SetupProgress
import com.circaso.wallet.domain.support.SplitPayments
import com.circaso.wallet.domain.support.SupportAccountRule
import com.circaso.wallet.domain.support.TransferEvents
import com.circaso.wallet.domain.support.TransferQuoter
import com.circaso.wallet.domain.support.WalletCreation
import com.circaso.wallet.domain.support.WalletListings
import com.circaso.wallet.domain.support.WalletSync
import com.circaso.wallet.domain.usecases.AddChain
import com.circaso.wallet.domain.usecases.AddMissingChains
import com.circaso.wallet.domain.usecases.ApplyCustodyUpdate
import com.circaso.wallet.domain.usecases.ApplyWalletChallenge
import com.circaso.wallet.domain.usecases.CheckFundsBeforeDeletion
import com.circaso.wallet.domain.usecases.CheckRecipient
import com.circaso.wallet.domain.usecases.CheckSpaceWalletFunds
import com.circaso.wallet.domain.usecases.CheckStuckTransfers
import com.circaso.wallet.domain.usecases.CreateGroupWallet
import com.circaso.wallet.domain.usecases.CreateSpaceWallet
import com.circaso.wallet.domain.usecases.CreateWallet
import com.circaso.wallet.domain.usecases.EstimateFee
import com.circaso.wallet.domain.usecases.ExpireUnsignedTransfers
import com.circaso.wallet.domain.usecases.FindGroupWallet
import com.circaso.wallet.domain.usecases.FindSpaceWallet
import com.circaso.wallet.domain.usecases.GetCoinIcon
import com.circaso.wallet.domain.usecases.GetGroupWallet
import com.circaso.wallet.domain.usecases.GetSpaceWallet
import com.circaso.wallet.domain.usecases.GetTransaction
import com.circaso.wallet.domain.usecases.GetTransfer
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.HasWallet
import com.circaso.wallet.domain.usecases.ListChains
import com.circaso.wallet.domain.usecases.ListMyWallets
import com.circaso.wallet.domain.usecases.ListTransactions
import com.circaso.wallet.domain.usecases.LookupTransfer
import com.circaso.wallet.domain.usecases.OpenSession
import com.circaso.wallet.domain.usecases.PrepareTransfer
import com.circaso.wallet.domain.usecases.QuoteTransfer
import com.circaso.wallet.domain.usecases.ReconcileWallets
import com.circaso.wallet.domain.usecases.ReplayFrames
import com.circaso.wallet.domain.usecases.RequestSetup
import com.circaso.wallet.domain.usecases.SettleSplitPayments
import com.circaso.wallet.domain.usecases.SummariseTransactions
import com.circaso.wallet.domain.usecases.SweepWalletSetups
import com.circaso.wallet.domain.usecases.ValidateAddress
import com.circaso.wallet.domain.usecases.ValueAssets
import com.circaso.wallet.presentation.agent.agentController
import com.circaso.wallet.presentation.api.WalletCustodyNotifications
import com.circaso.wallet.presentation.api.WalletDeletionGuard
import com.circaso.wallet.presentation.api.WalletRealtimeReplay
import com.circaso.wallet.presentation.api.WalletsImpl
import com.circaso.wallet.presentation.controllers.coinIconController
import com.circaso.wallet.presentation.controllers.historyController
import com.circaso.wallet.presentation.controllers.recipientController
import com.circaso.wallet.presentation.controllers.spaceWalletController
import com.circaso.wallet.presentation.controllers.transferController
import com.circaso.wallet.presentation.controllers.walletController
import com.circaso.wallet.presentation.services.CoinIconService
import com.circaso.wallet.presentation.services.HistoryService
import com.circaso.wallet.presentation.services.RecipientService
import com.circaso.wallet.presentation.services.SpaceWalletService
import com.circaso.wallet.presentation.services.TransferService
import com.circaso.wallet.presentation.services.WalletAgentService
import com.circaso.wallet.presentation.services.WalletDirectoryService
import com.circaso.wallet.presentation.services.WalletJobsService
import com.circaso.wallet.presentation.services.WalletService
import com.circaso.wallet.presentation.warmup.WalletWarmUp
import com.circaso.web.RouteContributor
import com.circaso.web.unavailable
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration

/** Schema `wallet`: its tables, its Flyway scripts and its outbox, which is infrastructure's (`outbox = true`). */
internal val walletDatabaseSpec =
    DatabaseModuleSpec(
        schema = "wallet",
        tables =
            listOf(
                CircleUsersTable,
                WalletsTable,
                TransfersTable,
                TransactionsTable,
                RealtimeTable,
                WalletOwnersTable,
                FeeBoundsTable,
                FeeRulesTable,
                LimitRulesTable,
                FeeQuotesTable,
                FeeLedgerTable,
            ),
        migrationsLocation = "classpath:db/wallet",
        outbox = true,
    )

/** How often `wallet.purge-expired` runs. */
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(5)

/** `wallet.expire-unsigned` runs every minute (the signing window is 15 minutes). */
private val EXPIRE_INTERVAL: Duration = Duration.ofMinutes(1)

/** `wallet.reconcile-daily` runs once a day. */
private val DAILY_INTERVAL: Duration = Duration.ofDays(1)

/**
 * The jobs' settings (`WALLET_JOBS_*`): how often `wallet.stuck-transfers` and `wallet.reconcile-active` run, after
 * how long a submitted transfer is read from Circle, who counts as active, and how much one run may ask Circle for.
 */
internal fun jobSettings(config: Config): JobSettings {
    val jobs = config.section("wallet").section("jobs")
    val defaults = JobSettings()
    return JobSettings(
        stuckInterval = jobs.seconds("stuck-interval-seconds", defaults.stuckInterval),
        stuckAfter = jobs.seconds("stuck-after-seconds", defaults.stuckAfter),
        stuckAlertAfter = jobs.minutes("stuck-alert-minutes", defaults.stuckAlertAfter),
        transferBatch = jobs.positive("transfer-batch", defaults.transferBatch),
        reconcileInterval = jobs.seconds("reconcile-interval-seconds", defaults.reconcileInterval),
        activeWindow = jobs.minutes("active-window-minutes", defaults.activeWindow),
        activeLookback = jobs.minutes("active-lookback-minutes", defaults.activeLookback),
        activeBatch = jobs.positive("active-batch", defaults.activeBatch),
        dailyBatch = jobs.positive("daily-batch", defaults.dailyBatch),
        transactionsPerUser = jobs.positive("transactions-per-user", defaults.transactionsPerUser),
        splitInterval = jobs.seconds("split-interval-seconds", defaults.splitInterval),
        splitAfter = jobs.seconds("split-after-seconds", defaults.splitAfter),
        splitMaxAge = jobs.minutes("split-max-age-minutes", defaults.splitMaxAge),
    )
}

private fun Config.positive(key: String, default: Int): Int =
    int(key, default).also { check(it > 0) { "Configuration 'wallet.jobs.$key' must be above zero (was $it)" } }

private fun Config.seconds(key: String, default: Duration): Duration =
    Duration.ofSeconds(positive(key, default.seconds.toInt()).toLong())

private fun Config.minutes(key: String, default: Duration): Duration =
    Duration.ofMinutes(positive(key, default.toMinutes().toInt()).toLong())

/**
 * The fee settings (phase 2F): `wallet.fees.chain.<chain>.address` (Circaso's fee wallet there; none, no fee) and
 * `wallet.fees.chain.<chain>.native-usd`. The limits are rules in the database now (`limit_rules`), not settings.
 */
internal fun feeConfig(config: Config): FeeConfig {
    val fees = config.section("wallet").section("fees")
    return ConfiguredFees { chain, name -> fees.string("chain.${chain.lowercase()}.$name") }
}

/**
 * `SPACES_ENABLED`, spaces' switch (spaces' `SPACES_ENABLED_KEY`; this module can't name spaces' impl). Spaces is
 * archived (decision 2026-11-05): while it is off, no space wallet is offered and W18 and W19 answer
 * `spaces.unavailable`.
 */
private const val SPACES_ENABLED_KEY = "spaces.enabled"

/** W18 and W19's path (`spaceWalletController`). */
private const val SPACE_WALLET_PATH = "/wallet/spaces"

/**
 * Wallet's Koin bindings: data, domain, presentation, its `api`, identity's `DeletionGuard`, integrations'
 * `CustodyNotifications`, its realtime replay, its database spec (with the outbox) and its purge job. Circle and the
 * exchange rates come from integrations as `StablecoinCustody` and `ExchangeRates`; the wallet never reads a Circle
 * setting.
 */
fun walletModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val fees = feeConfig(config)
        val jobs = jobSettings(config)
        val spacesEnabled = config.boolean(SPACES_ENABLED_KEY, false)

        // data
        single(named("wallet.spec")) { walletDatabaseSpec }
        single(named("wallet.database")) { get<Databases>().scope(walletDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("wallet.database"))) }
        single<WalletRepository> { WalletRepositoryImpl(get(named("wallet.database"))) }
        single<TransferRepository> { TransferRepositoryImpl(get(named("wallet.database"))) }
        single<TransactionRepository> { TransactionRepositoryImpl(get(named("wallet.database"))) }
        single<WalletOutbox> { WalletOutboxImpl(get(named("wallet.database"))) }
        single<WalletOwnerRepository> { WalletOwnerRepositoryImpl(get(named("wallet.database"))) }
        single<BalanceCache> { RedisBalanceCache(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<AddingChains> { RedisAddingChains(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<RuleRepository> { RuleRepositoryImpl(get(named("wallet.database"))) }
        single<FeeQuoteRepository> { FeeQuoteRepositoryImpl(get(named("wallet.database"))) }
        single<FeeLedgerRepository> { FeeLedgerRepositoryImpl(get(named("wallet.database"))) }
        single<FeeConfig> { fees }
        single<Reminders> { RedisReminders(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<PaymentHolds> { NoPaymentHolds() }
        single<WalletWarnings> { LogWalletWarnings() }
        single<ActiveWallets> { RedisActiveWallets(get<RedisScopes>().scope(REDIS_PREFIX), jobs.activeWindow, clock) }
        single<StuckNotices> { RedisStuckNotices(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<SetupChecks> { RedisSetupChecks(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<PendingSetups> { RedisPendingSetups(get<RedisScopes>().scope(REDIS_PREFIX), clock) }
        single {
            RealtimeFramesImpl(get<RedisScopes>().scope(REDIS_PREFIX), get(named("wallet.database")), get(), clock)
        }
        // Frames name a wallet's holder; HolderFrames sends them on to the people behind it (a group wallet's signer
        // or members, decision 2026-11-03).
        single<FrameSender> { HolderFrames(get(), get(), get<RealtimeFramesImpl>(), getOrNull()) }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }
        single(named("wallet.purge-expired")) {
            val expiry = WalletExpiry(get(named("wallet.database")))
            ScheduledJob("wallet.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }
        single(named("wallet.expire-unsigned")) {
            val service = get<WalletJobsService>()
            ScheduledJob("wallet.expire-unsigned", EXPIRE_INTERVAL) { service.expireUnsigned() }
        }
        single(named("wallet.stuck-transfers")) {
            val service = get<WalletJobsService>()
            ScheduledJob("wallet.stuck-transfers", jobs.stuckInterval) { service.checkStuck() }
        }
        single(named("wallet.reconcile-active")) {
            val service = get<WalletJobsService>()
            ScheduledJob("wallet.reconcile-active", jobs.reconcileInterval) { service.reconcile(ReconcilePass.ACTIVE) }
        }
        single(named("wallet.unsettled-splits")) {
            val service = get<WalletJobsService>()
            ScheduledJob("wallet.unsettled-splits", jobs.splitInterval) { service.settleSplits() }
        }
        single(named("wallet.setup-sweep")) {
            val service = get<WalletJobsService>()
            ScheduledJob("wallet.setup-sweep", SweepWalletSetups.SETUP_SWEEP_INTERVAL) { service.sweepSetups() }
        }
        single(named("wallet.reconcile-daily")) {
            val service = get<WalletJobsService>()
            ScheduledJob("wallet.reconcile-daily", DAILY_INTERVAL) { service.reconcile(ReconcilePass.DAILY) }
        }

        // domain: helpers and use cases
        single { OrderedIds(clock) }
        // SpaceWalletPolicy is spaces' (it implements wallet:api's guard and binds it in its own Koin module).
        single { Holders(get(), get(), getOrNull()) }
        single { WalletSync(get(), get(), get(), get(), get(), get(), get(), clock, get()) }
        single { SetupProgress(get(), get(), get(), get()) }
        single { WalletCreation(get(), get(), get(), get(), clock, get()) }
        single { WalletListings(get(), get()) }
        single { HoldingsReader(get(), get(), get()) }
        single { SupportAccountRule(get()) }
        single { Payees(get(), get(), get(), get(), get(), get(), getOrNull()) }
        single { SenderRules(get(), get(), get(), get(), get(), get()) }
        single { Limits(get(), get(), clock) }
        single { FeeCalculator(get(), get(), get(), get(), get(), get(), clock) }
        single { PaymentPlanner(get(), get(), get(), get(), getOrNull()) }
        single { FeeQuotes(get(), clock) }
        single { FeeSettlement(get(), get(), get()) }
        single { TransferEvents(get()) }
        single { OutgoingUpdates(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { IncomingUpdates(get(), get(), get(), get(), get(), get(), get()) }
        single { SplitPayments(get(), get(), get(), clock) }
        single { CustodyUpdates(get(), get(), get(), get(), get(), get(), clock, get(), get()) }
        single { Reconciler(get(), get(), get(), get()) }
        single { GetWallet(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { CreateWallet(get(), get()) }
        single { CreateGroupWallet(get(), get(), get(), get(), get(), clock) }
        single { GetGroupWallet(get(), get(), get(), get()) }
        single { ListMyWallets(get(), get(), get(), getOrNull()) }
        single { HeldFunds(get(), get(), get()) }
        // W18 and W19 need spaces' SpaceWalletPolicy, which only a live Spaces binds (archived 2026-11-05)
        if (spacesEnabled) {
            single { CreateSpaceWallet(get(), get(), get(), get(), get(), get(), clock) }
            single { GetSpaceWallet(get(), get(), get(), get()) }
        }
        single { FindSpaceWallet(get(), get()) }
        single { CheckSpaceWalletFunds(get(), get()) }
        single { FindGroupWallet(get(), get()) }
        single { OpenSession(get(), get(), get(), get()) }
        single { ListChains(get(), get(), get(), get(), get()) }
        single { AddChain(get(), get(), get(), get(), get(), clock, get()) }
        single { AddMissingChains(get(), get(), get(), get(), get(), clock, get()) }
        single { CheckFundsBeforeDeletion(get(), get(), get(), get()) }
        single { HasWallet(get()) }
        // PayoutApprovals is chama's (it implements wallet:api's guard and binds it in its own Koin module).
        single {
            PrepareTransfer(
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
                clock,
                get(),
                get(),
            )
        }
        single { TransferQuoter(get(), get(), get(), clock) }
        single { QuoteTransfer(get()) }
        single { GetTransfer(get(), get(), getOrNull()) }
        single { Counterparties(get(), get(), get()) }
        single { ListTransactions(get(), get(), get(), get()) }
        single { GetTransaction(get(), get(), get()) }
        single { SummariseTransactions(get(), get(), get()) }
        single { CheckRecipient(get(), get(), get()) }
        single { RequestSetup(get(), get(), get(), get(), get()) }
        single { ValidateAddress(get()) }
        single { ApplyCustodyUpdate(get()) }
        single { ApplyWalletChallenge(get()) }
        single { SweepWalletSetups(get(), clock) }
        single { ExpireUnsignedTransfers(get(), get(), jobs, clock) }
        single { CheckStuckTransfers(get(), get(), get(), get(), get(), get(), jobs, clock) }
        single { ReconcileWallets(get(), get(), get(), jobs, clock) }
        single { ReplayFrames(get()) }
        single { LookupTransfer(get()) }

        // presentation
        single { WalletService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { TransferService(get(), get(), get(), get()) }
        single { HistoryService(get()) }
        single<CoinIconRepository> { BundledCoinIcons() }
        single { GetCoinIcon(get()) }
        single { CoinIconService(get()) }
        single { ValueAssets(get()) }
        single { EstimateFee(get(), get()) }
        single { WalletAgentService(get(), get(), get(), get(), get(), get(), get()) }
        single { RecipientService(get(), get()) }
        single { WalletDirectoryService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        if (spacesEnabled) single { SpaceWalletService(get(), get()) }
        single { SettleSplitPayments(get(), get(), get(), get(), get(), jobs, clock) }
        single { WalletJobsService(get(), get(), get(), get(), get()) }
        single<RouteContributor>(named("wallet.routes")) {
            val wallets = get<WalletService>()
            val transfers = get<TransferService>()
            val recipients = get<RecipientService>()
            val history = get<HistoryService>()
            val agent = get<WalletAgentService>()
            val coinIcons = get<CoinIconService>()
            val spaceWallets = if (spacesEnabled) get<SpaceWalletService>() else null
            RouteContributor { route ->
                route.walletController(wallets)
                if (spaceWallets != null) {
                    route.spaceWalletController(spaceWallets)
                } else {
                    route.unavailable(SPACE_WALLET_PATH, WalletError.SpacesUnavailable)
                }
                route.transferController(transfers)
                route.recipientController(recipients)
                route.historyController(history)
                route.agentController(agent)
                route.coinIconController(coinIcons)
            }
        }

        // wallet:api, the guard identity asks before a deletion, Circle's updates and the replay after a reconnect
        single<Wallets> { WalletsImpl(get()) }
        single(named("wallet.deletion-guard")) { WalletDeletionGuard(get()) } bind DeletionGuard::class
        single<CustodyNotifications> { WalletCustodyNotifications(get()) }
        single(named("wallet.replay")) { RealtimeModule(WalletFrames.MODULE, WalletRealtimeReplay(get())) }

        // read-only and database-only (never Circle), run at start-up before the app reports ready
        single<WarmUp>(named("wallet.warm-up")) { WalletWarmUp(get(), get(), get(), get(), get(), clock) }
    }
