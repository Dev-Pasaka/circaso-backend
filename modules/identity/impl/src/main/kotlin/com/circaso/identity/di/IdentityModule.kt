package com.circaso.identity.di

import com.circaso.identity.api.AccountAdmin
import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.DeletionGuard
import com.circaso.identity.api.DeviceSignatures
import com.circaso.identity.api.PushRegistrations
import com.circaso.identity.api.SupportAccounts
import com.circaso.identity.api.TokenVerifier
import com.circaso.identity.data.cache.RedisIdentityCache
import com.circaso.identity.data.repositories.AccountAdminRepositoryImpl
import com.circaso.identity.data.repositories.AccountRepositoryImpl
import com.circaso.identity.data.repositories.DatabaseTransactor
import com.circaso.identity.data.repositories.DeviceRepositoryImpl
import com.circaso.identity.data.repositories.ExpiredRows
import com.circaso.identity.data.repositories.IdentityStoreImpl
import com.circaso.identity.data.repositories.ProviderRepositoryImpl
import com.circaso.identity.data.repositories.RecoveryRepositoryImpl
import com.circaso.identity.data.repositories.SecurityLogRepositoryImpl
import com.circaso.identity.data.repositories.SessionRepositoryImpl
import com.circaso.identity.data.security.EcdsaDeviceKeyVerifier
import com.circaso.identity.data.security.Ed25519Tokens
import com.circaso.identity.data.security.KeyedPhoneProtection
import com.circaso.identity.data.security.UnconfiguredAttestation
import com.circaso.identity.data.tables.AccountsTable
import com.circaso.identity.data.tables.DevicesTable
import com.circaso.identity.data.tables.ProviderLinksTable
import com.circaso.identity.data.tables.SecurityLogTable
import com.circaso.identity.data.tables.SessionsTable
import com.circaso.identity.data.tables.SupportRecoveriesTable
import com.circaso.identity.domain.ports.AccessTokenVerifier
import com.circaso.identity.domain.ports.AttestationMode
import com.circaso.identity.domain.ports.AttestationVerifier
import com.circaso.identity.domain.ports.DeviceKeyVerifier
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.identity.domain.ports.TokenIssuer
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.repositories.RecoveryRepository
import com.circaso.identity.domain.repositories.SecurityLogRepository
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.support.IdentityConfig
import com.circaso.identity.domain.support.PhoneOwnership
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.identity.domain.usecases.CompleteDeletions
import com.circaso.identity.domain.usecases.CountActiveUsers
import com.circaso.identity.domain.usecases.CountSignUps
import com.circaso.identity.domain.usecases.CreateSupportAccount
import com.circaso.identity.domain.usecases.FindAccountByPhone
import com.circaso.identity.domain.usecases.FindAccountsByEmail
import com.circaso.identity.domain.usecases.GetAccountCreatedAt
import com.circaso.identity.domain.usecases.GetAccountKind
import com.circaso.identity.domain.usecases.GetAccountRecords
import com.circaso.identity.domain.usecases.GetAccountStatus
import com.circaso.identity.domain.usecases.GetPublicKeys
import com.circaso.identity.domain.usecases.GetPushTarget
import com.circaso.identity.domain.usecases.GetRecoveryCase
import com.circaso.identity.domain.usecases.GetSecurityOverview
import com.circaso.identity.domain.usecases.GetSecuritySummary
import com.circaso.identity.domain.usecases.IssueNonce
import com.circaso.identity.domain.usecases.LinkProvider
import com.circaso.identity.domain.usecases.ListAccountDevices
import com.circaso.identity.domain.usecases.ListAccountSessions
import com.circaso.identity.domain.usecases.ListNewestAccounts
import com.circaso.identity.domain.usecases.ListSecurityEvents
import com.circaso.identity.domain.usecases.ListSupportAccounts
import com.circaso.identity.domain.usecases.RefreshSession
import com.circaso.identity.domain.usecases.RegisterDevice
import com.circaso.identity.domain.usecases.RemoveDevice
import com.circaso.identity.domain.usecases.RequestAccountDeletion
import com.circaso.identity.domain.usecases.RequestPhoneRelease
import com.circaso.identity.domain.usecases.RequestSupportRecovery
import com.circaso.identity.domain.usecases.ResolvePhones
import com.circaso.identity.domain.usecases.SignInWithProvider
import com.circaso.identity.domain.usecases.SignOut
import com.circaso.identity.domain.usecases.StartPhoneVerification
import com.circaso.identity.domain.usecases.SuspendAccount
import com.circaso.identity.domain.usecases.SweepPendingAccounts
import com.circaso.identity.domain.usecases.UnlinkProvider
import com.circaso.identity.domain.usecases.UnsuspendAccount
import com.circaso.identity.domain.usecases.UpdatePushToken
import com.circaso.identity.domain.usecases.VerifyAccessToken
import com.circaso.identity.domain.usecases.VerifyAccessTokenAlongside
import com.circaso.identity.domain.usecases.VerifyCarrierToken
import com.circaso.identity.domain.usecases.VerifyDeviceSignature
import com.circaso.identity.domain.usecases.VerifyPhoneCode
import com.circaso.identity.presentation.agent.agentController
import com.circaso.identity.presentation.api.AccountAdminImpl
import com.circaso.identity.presentation.api.AccountDirectoryImpl
import com.circaso.identity.presentation.api.DeviceSignaturesImpl
import com.circaso.identity.presentation.api.PushRegistrationsImpl
import com.circaso.identity.presentation.api.SupportAccountsImpl
import com.circaso.identity.presentation.api.TokenVerifierImpl
import com.circaso.identity.presentation.controllers.accountController
import com.circaso.identity.presentation.controllers.authController
import com.circaso.identity.presentation.controllers.deviceController
import com.circaso.identity.presentation.controllers.phoneController
import com.circaso.identity.presentation.controllers.recoveryController
import com.circaso.identity.presentation.services.AccountAdminService
import com.circaso.identity.presentation.services.AccountService
import com.circaso.identity.presentation.services.AuthService
import com.circaso.identity.presentation.services.DeviceService
import com.circaso.identity.presentation.services.DirectoryService
import com.circaso.identity.presentation.services.IdentityAgentService
import com.circaso.identity.presentation.services.MaintenanceService
import com.circaso.identity.presentation.services.PhoneService
import com.circaso.identity.presentation.services.RecoveryService
import com.circaso.identity.presentation.services.SupportAccountService
import com.circaso.identity.presentation.warmup.IdentityWarmUp
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.api.WarmUp
import com.circaso.shared.getOrNull
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration

/** Identity's settings from the `identity` config section; attestation is enforced in production and only logged elsewhere. */
fun identityConfig(config: Config): IdentityConfig {
    val section = config.section("identity")
    val default = if (config.environment == Environment.PRODUCTION) "ENFORCE" else "LOG"
    return IdentityConfig(
        attestation = AttestationMode.valueOf(section.string("attestation", default).uppercase()),
        smsRetrieverHash = section.string("sms-retriever-hash"),
        deletionGrace = Duration.ofDays(section.int("deletion-grace-days", 30).toLong()),
    )
}

private const val IDENTITY_SCHEMA = "identity"
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(5)
private val SWEEP_INTERVAL: Duration = Duration.ofHours(1)
private val DELETION_INTERVAL: Duration = Duration.ofMinutes(15)

/**
 * Identity's PostgreSQL: schema and role `identity`, its tables, its Flyway scripts in `db/identity`, and the shared
 * outbox, which infrastructure relays.
 */
fun identityDatabaseSpec() =
    DatabaseModuleSpec(
        schema = IDENTITY_SCHEMA,
        tables =
            listOf(
                AccountsTable,
                ProviderLinksTable,
                DevicesTable,
                SessionsTable,
                SecurityLogTable,
                SupportRecoveriesTable,
            ),
        migrationsLocation = "classpath:db/identity",
        outbox = true,
    )

/** Identity's Koin bindings: data, domain, presentation, its `api`, its database spec and its scheduled jobs. */
fun identityModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val identity = identityConfig(config)
        val local = config.environment.isLocal

        single { identity }
        single { DeviceProofs(get<DeviceKeyVerifier>(), clock, identity.proofWindow) }

        // data: security
        single { Ed25519Tokens(get<Secrets>(), local, clock) }
        single<TokenIssuer> { get<Ed25519Tokens>() }
        single<AccessTokenVerifier> { get<Ed25519Tokens>() }
        single<PhoneProtection> { KeyedPhoneProtection(get<Secrets>(), local) }
        single<DeviceKeyVerifier> { EcdsaDeviceKeyVerifier() }
        single<AttestationVerifier> { UnconfiguredAttestation() }
        single<IdentityCache> { RedisIdentityCache(get<RedisScopes>().scope("idn")) }

        // data: PostgreSQL, schema and role `identity`
        single(named("identity.spec")) { identityDatabaseSpec() }
        single<Database>(named("identity.database")) { get<Databases>().scope(IDENTITY_SCHEMA) }
        single<Transactor> { DatabaseTransactor(get(named("identity.database"))) }
        single { AccountRepositoryImpl(get(named("identity.database")), clock) }
        single { ProviderRepositoryImpl(get(named("identity.database"))) }
        single { DeviceRepositoryImpl(get(named("identity.database"))) }
        single { SessionRepositoryImpl(get(named("identity.database")), clock) }
        single { SecurityLogRepositoryImpl(get(named("identity.database")), clock) }
        single { RecoveryRepositoryImpl(get(named("identity.database"))) }
        single<AccountRepository> { get<AccountRepositoryImpl>() }
        single<ProviderRepository> { get<ProviderRepositoryImpl>() }
        single<DeviceRepository> { get<DeviceRepositoryImpl>() }
        single<SessionRepository> { get<SessionRepositoryImpl>() }
        single<SecurityLogRepository> { get<SecurityLogRepositoryImpl>() }
        single<RecoveryRepository> { get<RecoveryRepositoryImpl>() }
        single<AccountAdminRepository> { AccountAdminRepositoryImpl(get(named("identity.database"))) }
        single<IdentityStore> {
            IdentityStoreImpl(get(named("identity.database")), get(), get(), get(), get(), get(), get(), get())
        }
        single { ExpiredRows(get(named("identity.database"))) }

        // domain: helpers and use cases
        single { SessionOpener(get(), get(), get(), clock) }
        single { PhoneOwnership(get(), get(), get(), get(), get(), get(), get(), identity, clock) }
        single { IssueNonce(get()) }
        single { SignInWithProvider(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { StartPhoneVerification(get(), get(), get(), get(), get(), get()) }
        single { VerifyPhoneCode(get(), get(), get(), get(), get(), get()) }
        single { VerifyCarrierToken(get(), get(), get()) }
        single {
            RegisterDevice(
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
            )
        }
        single { RefreshSession(get(), get(), get(), get(), get(), get(), get(), get()) }
        single { SignOut(get(), get(), get(), get(), get()) }
        single { UpdatePushToken(get(), get(), get(), get()) }
        single { RemoveDevice(get(), get(), get(), get(), get(), get()) }
        single { GetSecurityOverview(get(), get(), get()) }
        single { LinkProvider(get(), get(), get(), get(), get(), get(), clock) }
        single { UnlinkProvider(get(), get(), get(), get()) }
        single { ListSecurityEvents(get()) }
        single { GetSecuritySummary(get(), get(), get(), get()) }
        single {
            RequestAccountDeletion(get(), get(), get(), getAll<DeletionGuard>(), get(), get(), get(), identity, clock)
        }
        single { RequestSupportRecovery(get(), get(), get(), get(), get(), get()) }
        single { RequestPhoneRelease(get(), get(), get(), get(), get(), get(), get(), clock) }
        single { GetRecoveryCase(get(), get()) }
        single { GetPublicKeys(get()) }
        single { VerifyAccessToken(get(), get()) }
        single { VerifyAccessTokenAlongside(get(), get()) }
        single { GetAccountStatus(get()) }
        single { GetAccountKind(get()) }
        single { GetAccountCreatedAt(get()) }
        // SupportProfiles is users' (bound in its module, wired by `app`): identity never depends on users.
        single { CreateSupportAccount(get(), get(), get(), get(), get(), clock) }
        single { ListSupportAccounts(get(), get()) }
        single { ResolvePhones(get()) }
        single { VerifyDeviceSignature(get(), get()) }
        single { GetPushTarget(get(), get()) }
        single { SweepPendingAccounts(get(), identity, clock) }
        single { CompleteDeletions(get(), get(), get(), get(), clock) }
        // staff, on the ops gateway only (decision 2026-11-08)
        single { GetAccountRecords(get()) }
        single { FindAccountByPhone(get()) }
        single { FindAccountsByEmail(get()) }
        single { ListNewestAccounts(get()) }
        single { ListAccountDevices(get(), get()) }
        single { ListAccountSessions(get(), clock) }
        single { SuspendAccount(get(), get(), get(), get(), get(), get(), get()) }
        single { UnsuspendAccount(get(), get(), get(), get(), get()) }
        single { CountSignUps(get()) }
        single { CountActiveUsers(get()) }

        // presentation
        single { AuthService(get(), get(), get(), get(), get()) }
        single { PhoneService(get(), get(), get(), get()) }
        single { DeviceService(get(), get(), get()) }
        single { AccountService(get(), get(), get(), get(), get()) }
        single { IdentityAgentService(get()) }
        single { RecoveryService(get(), get()) }
        single { DirectoryService(get(), get(), get(), get(), get(), get(), get(), get()) }
        single { SupportAccountService(get(), get()) }
        single { MaintenanceService(get(), get()) }
        single { AccountAdminService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single<RouteContributor>(named("identity.routes")) {
            val auth = get<AuthService>()
            val phone = get<PhoneService>()
            val devices = get<DeviceService>()
            val account = get<AccountService>()
            val recovery = get<RecoveryService>()
            val agent = get<IdentityAgentService>()
            RouteContributor { route ->
                route.authController(auth)
                route.phoneController(phone)
                route.deviceController(devices)
                route.accountController(account)
                route.recoveryController(recovery)
                route.agentController(agent)
            }
        }

        // scheduled jobs, run on one node at a time
        single(named("identity.purge-expired")) {
            val expired = get<ExpiredRows>()
            ScheduledJob("identity.purge-expired", PURGE_INTERVAL) { expired.purge() }
        }
        single(named("identity.sweep-pending-accounts")) {
            val maintenance = get<MaintenanceService>()
            ScheduledJob("identity.sweep-pending-accounts", SWEEP_INTERVAL) {
                maintenance.sweepPendingAccounts().getOrNull() ?: 0
            }
        }
        single(named("identity.complete-deletions")) {
            val maintenance = get<MaintenanceService>()
            ScheduledJob("identity.complete-deletions", DELETION_INTERVAL) {
                maintenance.completeDeletions().getOrNull() ?: 0
            }
        }

        // identity:api, for the gateway and the modules above
        single<TokenVerifier> { TokenVerifierImpl(get()) }
        single<AccountDirectory> { AccountDirectoryImpl(get()) }
        single<DeviceSignatures> { DeviceSignaturesImpl(get()) }
        single<PushRegistrations> { PushRegistrationsImpl(get()) }

        // identity:api's staff contracts, for the ops module behind the ops gateway; never mounted on the app gateway
        // (decision 2026-11-08). OPS1 and OPS2 moved there from the app's routes.
        single<AccountAdmin> { AccountAdminImpl(get()) }
        single<SupportAccounts> { SupportAccountsImpl(get()) }

        // read-only, run at start-up before the app reports ready
        single<WarmUp>(named("identity.warm-up")) { IdentityWarmUp(get(), get(), get(), get(), get(), get()) }
    }
