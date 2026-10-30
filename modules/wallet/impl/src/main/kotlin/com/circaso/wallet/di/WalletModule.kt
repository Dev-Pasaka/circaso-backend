package com.circaso.wallet.di

import com.circaso.identity.api.DeletionGuard
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.integrations.api.CustodyNotifications
import com.circaso.shared.Asset
import com.circaso.shared.RealtimeModule
import com.circaso.wallet.api.WalletFrames
import com.circaso.wallet.api.Wallets
import com.circaso.wallet.data.cache.REDIS_PREFIX
import com.circaso.wallet.data.cache.RealtimeFramesImpl
import com.circaso.wallet.data.cache.RedisActiveWallets
import com.circaso.wallet.data.cache.RedisAddingChains
import com.circaso.wallet.data.cache.RedisBalanceCache
import com.circaso.wallet.data.cache.RedisDailySpend
import com.circaso.wallet.data.cache.RedisReminders
import com.circaso.wallet.data.cache.RedisStuckNotices
import com.circaso.wallet.data.repositories.DatabaseTransactor
import com.circaso.wallet.data.repositories.LogWalletWarnings
import com.circaso.wallet.data.repositories.NoPaymentHolds
import com.circaso.wallet.data.repositories.TransactionRepositoryImpl
import com.circaso.wallet.data.repositories.TransferRepositoryImpl
import com.circaso.wallet.data.repositories.WalletExpiry
import com.circaso.wallet.data.repositories.WalletOutboxImpl
import com.circaso.wallet.data.repositories.WalletRepositoryImpl
import com.circaso.wallet.data.tables.CircleUsersTable
import com.circaso.wallet.data.tables.RealtimeTable
import com.circaso.wallet.data.tables.TransactionsTable
import com.circaso.wallet.data.tables.TransfersTable
import com.circaso.wallet.data.tables.WalletsTable
import com.circaso.wallet.domain.models.JobSettings
import com.circaso.wallet.domain.models.ReconcilePass
import com.circaso.wallet.domain.models.TransferLimits
import com.circaso.wallet.domain.ports.ActiveWallets
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.ports.BalanceCache
import com.circaso.wallet.domain.ports.DailySpend
import com.circaso.wallet.domain.ports.FrameSender
import com.circaso.wallet.domain.ports.PaymentHolds
import com.circaso.wallet.domain.ports.RealtimeLog
import com.circaso.wallet.domain.ports.Reminders
import com.circaso.wallet.domain.ports.StuckNotices
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletOutbox
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.CustodyUpdates
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.IncomingUpdates
import com.circaso.wallet.domain.support.OrderedIds
import com.circaso.wallet.domain.support.OutgoingUpdates
import com.circaso.wallet.domain.support.Payees
import com.circaso.wallet.domain.support.Reconciler
import com.circaso.wallet.domain.support.SenderRules
import com.circaso.wallet.domain.support.TransferEvents
import com.circaso.wallet.domain.support.WalletSync
import com.circaso.wallet.domain.usecases.AddChain
import com.circaso.wallet.domain.usecases.AddMissingChains
import com.circaso.wallet.domain.usecases.ApplyCustodyUpdate
import com.circaso.wallet.domain.usecases.CheckFundsBeforeDeletion
import com.circaso.wallet.domain.usecases.CheckRecipient
import com.circaso.wallet.domain.usecases.CheckStuckTransfers
import com.circaso.wallet.domain.usecases.CreateWallet
import com.circaso.wallet.domain.usecases.ExpireUnsignedTransfers
import com.circaso.wallet.domain.usecases.GetTransfer
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.HasWallet
import com.circaso.wallet.domain.usecases.ListChains
import com.circaso.wallet.domain.usecases.ListTransactions
import com.circaso.wallet.domain.usecases.LookupTransfer
import com.circaso.wallet.domain.usecases.OpenSession
import com.circaso.wallet.domain.usecases.PrepareTransfer
import com.circaso.wallet.domain.usecases.ReconcileWallets
import com.circaso.wallet.domain.usecases.ReplayFrames
import com.circaso.wallet.domain.usecases.RequestSetup
import com.circaso.wallet.domain.usecases.ValidateAddress
import com.circaso.wallet.presentation.api.WalletCustodyNotifications
import com.circaso.wallet.presentation.api.WalletDeletionGuard
import com.circaso.wallet.presentation.api.WalletRealtimeReplay
import com.circaso.wallet.presentation.api.WalletsImpl
import com.circaso.wallet.presentation.controllers.historyController
import com.circaso.wallet.presentation.controllers.recipientController
import com.circaso.wallet.presentation.controllers.transferController
import com.circaso.wallet.presentation.controllers.walletController
import com.circaso.wallet.presentation.services.HistoryService
import com.circaso.wallet.presentation.services.RecipientService
import com.circaso.wallet.presentation.services.TransferService
import com.circaso.wallet.presentation.services.WalletDirectoryService
import com.circaso.wallet.presentation.services.WalletJobsService
import com.circaso.wallet.presentation.services.WalletService
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration

/** Schema `wallet`: its tables, its Flyway scripts and its outbox, which is infrastructure's (`outbox = true`). */
internal val walletDatabaseSpec =
    DatabaseModuleSpec(
        schema = "wallet",
        tables = listOf(CircleUsersTable, WalletsTable, TransfersTable, TransactionsTable, RealtimeTable),
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
    )
}

private fun Config.positive(key: String, default: Int): Int =
    int(key, default).also { check(it > 0) { "Configuration 'wallet.jobs.$key' must be above zero (was $it)" } }

private fun Config.seconds(key: String, default: Duration): Duration =
    Duration.ofSeconds(positive(key, default.seconds.toInt()).toLong())

private fun Config.minutes(key: String, default: Duration): Duration =
    Duration.ofMinutes(positive(key, default.toMinutes().toInt()).toLong())

/**
 * The transfer limits in whole USDC: `WALLET_TRANSFER_LIMIT_PER_PAYMENT` and `WALLET_TRANSFER_LIMIT_DAILY`.
 * TODO(decision: limits, KYC and licensing) the defaults are placeholders until the limits are decided.
 */
internal fun transferLimits(config: Config): TransferLimits {
    val transfer = config.section("wallet").section("transfer")
    return TransferLimits(
        perPaymentMinor = usdcMinor(transfer.string("limit-per-payment", "500")),
        dailyMinor = usdcMinor(transfer.string("limit-daily", "1000")),
    )
}

private fun usdcMinor(whole: String): Long =
    BigDecimal(whole.trim())
        .movePointRight(Asset.USDC.decimals)
        .longValueExact()
        .also { check(it > 0) { "A wallet transfer limit must be above zero (was '$whole')" } }

/**
 * Wallet's Koin bindings: data, domain, presentation, its `api`, identity's `DeletionGuard`, integrations'
 * `CustodyNotifications`, its realtime replay, its database spec (with the outbox) and its purge job. Circle and the
 * exchange rates come from integrations as `StablecoinCustody` and `ExchangeRates`; the wallet never reads a Circle
 * setting.
 */
fun walletModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val limits = transferLimits(config)
        val jobs = jobSettings(config)

        // data
        single(named("wallet.spec")) { walletDatabaseSpec }
        single(named("wallet.database")) { get<Databases>().scope(walletDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("wallet.database"))) }
        single<WalletRepository> { WalletRepositoryImpl(get(named("wallet.database"))) }
        single<TransferRepository> { TransferRepositoryImpl(get(named("wallet.database"))) }
        single<TransactionRepository> { TransactionRepositoryImpl(get(named("wallet.database"))) }
        single<WalletOutbox> { WalletOutboxImpl(get(named("wallet.database"))) }
        single<BalanceCache> { RedisBalanceCache(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<AddingChains> { RedisAddingChains(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<DailySpend> { RedisDailySpend(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<Reminders> { RedisReminders(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<PaymentHolds> { NoPaymentHolds() }
        single<WalletWarnings> { LogWalletWarnings() }
        single<ActiveWallets> { RedisActiveWallets(get<RedisScopes>().scope(REDIS_PREFIX), jobs.activeWindow, clock) }
        single<StuckNotices> { RedisStuckNotices(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single {
            RealtimeFramesImpl(get<RedisScopes>().scope(REDIS_PREFIX), get(named("wallet.database")), get(), clock)
        }
        single<FrameSender> { get<RealtimeFramesImpl>() }
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
        single(named("wallet.reconcile-daily")) {
            val service = get<WalletJobsService>()
            ScheduledJob("wallet.reconcile-daily", DAILY_INTERVAL) { service.reconcile(ReconcilePass.DAILY) }
        }

        // domain: helpers and use cases
        single { OrderedIds(clock) }
        single { WalletSync(get(), get(), get(), get(), get(), get(), get(), clock) }
        single { HoldingsReader(get(), get(), get()) }
        single { Payees(get(), get(), get(), get()) }
        single { SenderRules(get(), get(), get(), get(), get(), get(), limits, clock) }
        single { TransferEvents(get()) }
        single { OutgoingUpdates(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { IncomingUpdates(get(), get(), get(), get(), get(), get()) }
        single { CustodyUpdates(get(), get(), get(), get(), get(), get(), clock) }
        single { Reconciler(get(), get(), get(), get()) }
        single { GetWallet(get(), get(), get(), get(), get(), get(), get()) }
        single { CreateWallet(get(), get(), get(), get(), clock) }
        single { OpenSession(get(), get(), get()) }
        single { ListChains(get(), get(), get(), get()) }
        single { AddChain(get(), get(), get(), get(), clock) }
        single { AddMissingChains(get(), get(), get(), get(), clock) }
        single { CheckFundsBeforeDeletion(get(), get(), get()) }
        single { HasWallet(get()) }
        single { PrepareTransfer(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { GetTransfer(get()) }
        single { ListTransactions(get(), get()) }
        single { CheckRecipient(get(), get()) }
        single { RequestSetup(get(), get(), get(), get()) }
        single { ValidateAddress(get()) }
        single { ApplyCustodyUpdate(get()) }
        single { ExpireUnsignedTransfers(get(), get(), jobs, clock) }
        single { CheckStuckTransfers(get(), get(), get(), get(), get(), get(), jobs, clock) }
        single { ReconcileWallets(get(), get(), get(), jobs, clock) }
        single { ReplayFrames(get()) }
        single { LookupTransfer(get()) }

        // presentation
        single { WalletService(get(), get(), get(), get(), get(), get()) }
        single { TransferService(get(), get(), get()) }
        single { HistoryService(get()) }
        single { RecipientService(get(), get()) }
        single { WalletDirectoryService(get(), get(), get(), get(), get()) }
        single { WalletJobsService(get(), get(), get()) }
        single<RouteContributor>(named("wallet.routes")) {
            val wallets = get<WalletService>()
            val transfers = get<TransferService>()
            val recipients = get<RecipientService>()
            val history = get<HistoryService>()
            RouteContributor { route ->
                route.walletController(wallets)
                route.transferController(transfers)
                route.recipientController(recipients)
                route.historyController(history)
            }
        }

        // wallet:api, the guard identity asks before a deletion, Circle's updates and the replay after a reconnect
        single<Wallets> { WalletsImpl(get()) }
        single(named("wallet.deletion-guard")) { WalletDeletionGuard(get()) } bind DeletionGuard::class
        single<CustodyNotifications> { WalletCustodyNotifications(get()) }
        single(named("wallet.replay")) { RealtimeModule(WalletFrames.MODULE, WalletRealtimeReplay(get())) }
    }
