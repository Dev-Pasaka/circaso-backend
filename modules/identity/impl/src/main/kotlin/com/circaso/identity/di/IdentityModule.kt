package com.circaso.identity.di

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.DeletionGuard
import com.circaso.identity.api.DeviceSignatures
import com.circaso.identity.api.PushRegistrations
import com.circaso.identity.api.TokenVerifier
import com.circaso.identity.data.cache.RedisIdentityCache
import com.circaso.identity.data.entities.AccountRow
import com.circaso.identity.data.entities.DeviceRow
import com.circaso.identity.data.entities.IdentityOutboxRow
import com.circaso.identity.data.entities.PhoneClaimRow
import com.circaso.identity.data.entities.ProviderClaimRow
import com.circaso.identity.data.entities.ProviderLinkRow
import com.circaso.identity.data.entities.RecoveryByRequestRow
import com.circaso.identity.data.entities.SecurityEventRow
import com.circaso.identity.data.entities.SessionRow
import com.circaso.identity.data.entities.SupportRecoveryRow
import com.circaso.identity.data.migrations.V001_CreateIdentityTables
import com.circaso.identity.data.migrations.identityEntities
import com.circaso.identity.data.repositories.AccountRepositoryImpl
import com.circaso.identity.data.repositories.DeviceRepositoryImpl
import com.circaso.identity.data.repositories.IdentityOutboxStore
import com.circaso.identity.data.repositories.IdentityStoreImpl
import com.circaso.identity.data.repositories.ProviderRepositoryImpl
import com.circaso.identity.data.repositories.RecoveryRepositoryImpl
import com.circaso.identity.data.repositories.SecurityLogRepositoryImpl
import com.circaso.identity.data.repositories.SessionRepositoryImpl
import com.circaso.identity.data.rows
import com.circaso.identity.data.security.EcdsaDeviceKeyVerifier
import com.circaso.identity.data.security.Ed25519Tokens
import com.circaso.identity.data.security.KeyedPhoneProtection
import com.circaso.identity.data.security.UnconfiguredAttestation
import com.circaso.identity.domain.ports.AccessTokenVerifier
import com.circaso.identity.domain.ports.AttestationMode
import com.circaso.identity.domain.ports.AttestationVerifier
import com.circaso.identity.domain.ports.DeviceKeyVerifier
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.identity.domain.ports.TokenIssuer
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
import com.circaso.identity.domain.usecases.GetAccountStatus
import com.circaso.identity.domain.usecases.GetPublicKeys
import com.circaso.identity.domain.usecases.GetPushTarget
import com.circaso.identity.domain.usecases.GetRecoveryCase
import com.circaso.identity.domain.usecases.GetSecurityOverview
import com.circaso.identity.domain.usecases.IssueNonce
import com.circaso.identity.domain.usecases.LinkProvider
import com.circaso.identity.domain.usecases.ListSecurityEvents
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
import com.circaso.identity.domain.usecases.UnlinkProvider
import com.circaso.identity.domain.usecases.UpdatePushToken
import com.circaso.identity.domain.usecases.VerifyAccessToken
import com.circaso.identity.domain.usecases.VerifyCarrierToken
import com.circaso.identity.domain.usecases.VerifyDeviceSignature
import com.circaso.identity.domain.usecases.VerifyPhoneCode
import com.circaso.identity.presentation.api.AccountDirectoryImpl
import com.circaso.identity.presentation.api.DeviceSignaturesImpl
import com.circaso.identity.presentation.api.PushRegistrationsImpl
import com.circaso.identity.presentation.api.TokenVerifierImpl
import com.circaso.identity.presentation.controllers.accountController
import com.circaso.identity.presentation.controllers.authController
import com.circaso.identity.presentation.controllers.deviceController
import com.circaso.identity.presentation.controllers.phoneController
import com.circaso.identity.presentation.controllers.recoveryController
import com.circaso.identity.presentation.services.AccountService
import com.circaso.identity.presentation.services.AuthService
import com.circaso.identity.presentation.services.DeviceService
import com.circaso.identity.presentation.services.DirectoryService
import com.circaso.identity.presentation.services.PhoneService
import com.circaso.identity.presentation.services.RecoveryService
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.Secrets
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

/** Identity's Koin bindings: data, domain, presentation, its `api`, its Kandra spec and its outbox. */
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

        // data: repositories
        single<AccountRepository> { AccountRepositoryImpl(rows<AccountRow>(), rows<PhoneClaimRow>()) }
        single<ProviderRepository> { ProviderRepositoryImpl(rows<ProviderClaimRow>(), rows<ProviderLinkRow>()) }
        single<DeviceRepository> { DeviceRepositoryImpl(rows<DeviceRow>()) }
        single<SessionRepository> { SessionRepositoryImpl(rows<SessionRow>()) }
        single<SecurityLogRepository> { SecurityLogRepositoryImpl(rows<SecurityEventRow>()) }
        single<RecoveryRepository> { RecoveryRepositoryImpl(rows<SupportRecoveryRow>(), rows<RecoveryByRequestRow>()) }
        single<IdentityStore> {
            IdentityStoreImpl(
                get(),
                rows<AccountRow>(),
                rows<DeviceRow>(),
                rows<SessionRow>(),
                rows<ProviderLinkRow>(),
                rows<SecurityEventRow>(),
                rows<SupportRecoveryRow>(),
                rows<RecoveryByRequestRow>(),
                rows<IdentityOutboxRow>(),
                rows<ProviderClaimRow>(),
                get(),
            )
        }
        single<OutboxStore>(named("identity.outbox")) { IdentityOutboxStore(rows<IdentityOutboxRow>()) }
        single(named("identity.spec")) {
            KandraModuleSpec("circaso_identity", identityEntities, listOf(V001_CreateIdentityTables))
        }

        // domain: helpers and use cases
        single { SessionOpener(get(), get(), get(), clock) }
        single { PhoneOwnership(get(), get(), get(), get(), clock) }
        single { IssueNonce(get()) }
        single { SignInWithProvider(get(), get(), get(), get(), get(), get(), get(), get(), get(), clock) }
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
                clock,
            )
        }
        single { RefreshSession(get(), get(), get(), get(), get(), get(), get()) }
        single { SignOut(get(), get(), get(), get()) }
        single { UpdatePushToken(get(), get()) }
        single { RemoveDevice(get(), get(), get(), get(), get()) }
        single { GetSecurityOverview(get(), get(), get()) }
        single { LinkProvider(get(), get(), get(), get(), clock) }
        single { UnlinkProvider(get(), get()) }
        single { ListSecurityEvents(get()) }
        single { RequestAccountDeletion(get(), get(), getAll<DeletionGuard>(), get(), get(), get()) }
        single { RequestSupportRecovery(get(), get(), get(), get(), get(), get()) }
        single { RequestPhoneRelease(get(), get(), get(), get(), get(), get(), get(), clock) }
        single { GetRecoveryCase(get(), get()) }
        single { GetPublicKeys(get()) }
        single { VerifyAccessToken(get(), get()) }
        single { GetAccountStatus(get()) }
        single { ResolvePhones(get()) }
        single { VerifyDeviceSignature(get(), get()) }
        single { GetPushTarget(get(), get()) }

        // presentation
        single { AuthService(get(), get(), get(), get(), get()) }
        single { PhoneService(get(), get(), get(), get()) }
        single { DeviceService(get(), get(), get()) }
        single { AccountService(get(), get(), get(), get(), get()) }
        single { RecoveryService(get(), get()) }
        single { DirectoryService(get(), get(), get(), get(), get()) }
        single<RouteContributor>(named("identity.routes")) {
            val auth = get<AuthService>()
            val phone = get<PhoneService>()
            val devices = get<DeviceService>()
            val account = get<AccountService>()
            val recovery = get<RecoveryService>()
            RouteContributor { route ->
                route.authController(auth)
                route.phoneController(phone)
                route.deviceController(devices)
                route.accountController(account)
                route.recoveryController(recovery)
            }
        }

        // identity:api, for the gateway and the modules above
        single<TokenVerifier> { TokenVerifierImpl(get()) }
        single<AccountDirectory> { AccountDirectoryImpl(get()) }
        single<DeviceSignatures> { DeviceSignaturesImpl(get()) }
        single<PushRegistrations> { PushRegistrationsImpl(get()) }
    }
