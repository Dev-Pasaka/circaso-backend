package com.circaso.wallet.di

import com.circaso.identity.api.DeletionGuard
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.wallet.api.Wallets
import com.circaso.wallet.data.cache.REDIS_PREFIX
import com.circaso.wallet.data.cache.RedisAddingChains
import com.circaso.wallet.data.cache.RedisBalanceCache
import com.circaso.wallet.data.repositories.DatabaseTransactor
import com.circaso.wallet.data.repositories.LogWalletWarnings
import com.circaso.wallet.data.repositories.WalletExpiry
import com.circaso.wallet.data.repositories.WalletOutboxImpl
import com.circaso.wallet.data.repositories.WalletRepositoryImpl
import com.circaso.wallet.data.tables.CircleUsersTable
import com.circaso.wallet.data.tables.WalletsTable
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.ports.BalanceCache
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.WalletOutbox
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.WalletSync
import com.circaso.wallet.domain.usecases.AddChain
import com.circaso.wallet.domain.usecases.CheckFundsBeforeDeletion
import com.circaso.wallet.domain.usecases.CreateWallet
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.HasWallet
import com.circaso.wallet.domain.usecases.ListChains
import com.circaso.wallet.domain.usecases.OpenSession
import com.circaso.wallet.presentation.api.WalletDeletionGuard
import com.circaso.wallet.presentation.api.WalletsImpl
import com.circaso.wallet.presentation.controllers.walletController
import com.circaso.wallet.presentation.services.WalletDirectoryService
import com.circaso.wallet.presentation.services.WalletService
import com.circaso.web.RouteContributor
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
        tables = listOf(CircleUsersTable, WalletsTable),
        migrationsLocation = "classpath:db/wallet",
        outbox = true,
    )

/** How often `wallet.purge-expired` runs. */
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(5)

/**
 * Wallet's Koin bindings: data, domain, presentation, its `api`, identity's `DeletionGuard`, its database spec (with
 * the outbox) and its purge job. Circle and the exchange rates come from integrations as `StablecoinCustody` and
 * `ExchangeRates`; the wallet never reads a Circle setting.
 */
@Suppress("UNUSED_PARAMETER")
fun walletModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()

        // data
        single(named("wallet.spec")) { walletDatabaseSpec }
        single(named("wallet.database")) { get<Databases>().scope(walletDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("wallet.database"))) }
        single<WalletRepository> { WalletRepositoryImpl(get(named("wallet.database"))) }
        single<WalletOutbox> { WalletOutboxImpl(get(named("wallet.database"))) }
        single<BalanceCache> { RedisBalanceCache(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<AddingChains> { RedisAddingChains(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<WalletWarnings> { LogWalletWarnings() }
        single(named("wallet.purge-expired")) {
            val expiry = WalletExpiry(get(named("wallet.database")))
            ScheduledJob("wallet.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }

        // domain: helpers and use cases
        single { WalletSync(get(), get(), get(), get(), get(), get(), clock) }
        single { HoldingsReader(get(), get(), get()) }
        single { GetWallet(get(), get(), get(), get(), get()) }
        single { CreateWallet(get(), get(), get(), get(), clock) }
        single { OpenSession(get(), get()) }
        single { ListChains(get(), get(), get(), get()) }
        single { AddChain(get(), get(), get(), get()) }
        single { CheckFundsBeforeDeletion(get(), get(), get()) }
        single { HasWallet(get()) }

        // presentation
        single { WalletService(get(), get(), get(), get(), get()) }
        single { WalletDirectoryService(get(), get()) }
        single<RouteContributor>(named("wallet.routes")) {
            val wallets = get<WalletService>()
            RouteContributor { route -> route.walletController(wallets) }
        }

        // wallet:api, and the guard identity asks before a deletion
        single<Wallets> { WalletsImpl(get()) }
        single(named("wallet.deletion-guard")) { WalletDeletionGuard(get()) } bind DeletionGuard::class
    }
