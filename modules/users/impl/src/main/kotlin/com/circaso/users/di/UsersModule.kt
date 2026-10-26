package com.circaso.users.di

import com.circaso.identity.api.AccountDirectory
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.api.ObjectStores
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.EventSubscription
import com.circaso.shared.RealtimeModule
import com.circaso.users.api.Profiles
import com.circaso.users.api.ReachPolicy
import com.circaso.users.data.cache.KeyedPhoneHasher
import com.circaso.users.data.cache.ObjectStoreAvatars
import com.circaso.users.data.cache.RealtimeFramesImpl
import com.circaso.users.data.cache.RedisReachCache
import com.circaso.users.data.cache.RedisUsersCache
import com.circaso.users.data.entities.BlockRow
import com.circaso.users.data.entities.BlockedByRow
import com.circaso.users.data.entities.ContactReverseRow
import com.circaso.users.data.entities.ContactRow
import com.circaso.users.data.entities.HandleClaimRow
import com.circaso.users.data.entities.InviteRow
import com.circaso.users.data.entities.ProfileRow
import com.circaso.users.data.entities.UsersOutboxRow
import com.circaso.users.data.entities.UsersRealtimeRow
import com.circaso.users.data.entities.WaitingContactRow
import com.circaso.users.data.migrations.V001_CreateUsersTables
import com.circaso.users.data.migrations.usersEntities
import com.circaso.users.data.repositories.BlockRepositoryImpl
import com.circaso.users.data.repositories.ContactRepositoryImpl
import com.circaso.users.data.repositories.HandleRepositoryImpl
import com.circaso.users.data.repositories.InviteRepositoryImpl
import com.circaso.users.data.repositories.ProfileRepositoryImpl
import com.circaso.users.data.repositories.UsersOutboxStore
import com.circaso.users.data.repositories.UsersStoreImpl
import com.circaso.users.data.repositories.WaitingContactRepositoryImpl
import com.circaso.users.data.rows
import com.circaso.users.domain.ports.AvatarStorage
import com.circaso.users.domain.ports.PhoneHasher
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.RealtimeFrames
import com.circaso.users.domain.ports.RealtimeLog
import com.circaso.users.domain.ports.UsersCache
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.InviteRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.repositories.WaitingContactRepository
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.ReachRule
import com.circaso.users.domain.support.UserNotifier
import com.circaso.users.domain.support.UsersConfig
import com.circaso.users.domain.support.WaitingContactMatcher
import com.circaso.users.domain.usecases.BlockUser
import com.circaso.users.domain.usecases.CanReach
import com.circaso.users.domain.usecases.CheckBlockedEitherWay
import com.circaso.users.domain.usecases.CheckHandle
import com.circaso.users.domain.usecases.CheckReach
import com.circaso.users.domain.usecases.ClaimHandle
import com.circaso.users.domain.usecases.ClearReachOnStatusChange
import com.circaso.users.domain.usecases.Connect
import com.circaso.users.domain.usecases.CreateInvite
import com.circaso.users.domain.usecases.CreateProfileOnActivation
import com.circaso.users.domain.usecases.DeleteUserData
import com.circaso.users.domain.usecases.FilterReachable
import com.circaso.users.domain.usecases.GetMyProfile
import com.circaso.users.domain.usecases.GetProfiles
import com.circaso.users.domain.usecases.GetPublicProfile
import com.circaso.users.domain.usecases.ListBlocks
import com.circaso.users.domain.usecases.ListContacts
import com.circaso.users.domain.usecases.LookupHandle
import com.circaso.users.domain.usecases.PrepareAvatarUpload
import com.circaso.users.domain.usecases.RecheckWaitingContacts
import com.circaso.users.domain.usecases.RemoveAvatar
import com.circaso.users.domain.usecases.RemoveContact
import com.circaso.users.domain.usecases.ReplayFrames
import com.circaso.users.domain.usecases.RevokeInvite
import com.circaso.users.domain.usecases.SearchContacts
import com.circaso.users.domain.usecases.SetAvatar
import com.circaso.users.domain.usecases.SyncContacts
import com.circaso.users.domain.usecases.UnblockUser
import com.circaso.users.domain.usecases.UpdateProfile
import com.circaso.users.presentation.agent.agentController
import com.circaso.users.presentation.api.ProfilesImpl
import com.circaso.users.presentation.api.ReachPolicyImpl
import com.circaso.users.presentation.api.UsersRealtimeReplay
import com.circaso.users.presentation.controllers.blockController
import com.circaso.users.presentation.controllers.contactController
import com.circaso.users.presentation.controllers.handleController
import com.circaso.users.presentation.controllers.profileController
import com.circaso.users.presentation.events.IdentityEventHandler
import com.circaso.users.presentation.services.BlockService
import com.circaso.users.presentation.services.ContactService
import com.circaso.users.presentation.services.DirectoryService
import com.circaso.users.presentation.services.HandleService
import com.circaso.users.presentation.services.IdentityEventsService
import com.circaso.users.presentation.services.ProfileService
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock

/** Users' Koin bindings: data, domain, presentation, its `api`, its Kandra spec, its outbox and its event subscription. */
fun usersModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val users = config.section("users")
        val usersConfig =
            UsersConfig(
                inviteBaseUrl =
                    users.string("invite-base-url", "https://circaso.com/i/").trimEnd('/') + "/",
            )
        val local = config.environment.isLocal

        single { usersConfig }

        // data
        single<ReachCache> { RedisReachCache(get<RedisScopes>().scope("usr")) }
        single<UsersCache> { RedisUsersCache(get<RedisScopes>().scope("usr")) }
        single<PhoneHasher> { KeyedPhoneHasher(get<Secrets>(), local) }
        single<AvatarStorage> { ObjectStoreAvatars(get<ObjectStores>().scope("users")) }
        single { RealtimeFramesImpl(get<RedisScopes>().scope("usr"), rows<UsersRealtimeRow>(), get(), clock) }
        single<RealtimeFrames> { get<RealtimeFramesImpl>() }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }
        single<ProfileRepository> { ProfileRepositoryImpl(rows<ProfileRow>()) }
        single<HandleRepository> { HandleRepositoryImpl(rows<HandleClaimRow>()) }
        single<ContactRepository> { ContactRepositoryImpl(rows<ContactRow>(), rows<ContactReverseRow>()) }
        single<WaitingContactRepository> { WaitingContactRepositoryImpl(rows<WaitingContactRow>()) }
        single<BlockRepository> { BlockRepositoryImpl(rows<BlockRow>(), rows<BlockedByRow>()) }
        single<InviteRepository> { InviteRepositoryImpl(rows<InviteRow>()) }
        single<UsersStore> {
            UsersStoreImpl(
                get(),
                rows<ProfileRow>(),
                rows<HandleClaimRow>(),
                rows<ContactRow>(),
                rows<ContactReverseRow>(),
                rows<WaitingContactRow>(),
                rows<BlockRow>(),
                rows<BlockedByRow>(),
                rows<InviteRow>(),
                rows<UsersOutboxRow>(),
                get(),
            )
        }
        single<OutboxStore>(named("users.outbox")) { UsersOutboxStore(rows<UsersOutboxRow>()) }
        single(named("users.spec")) { KandraModuleSpec("circaso_users", usersEntities, listOf(V001_CreateUsersTables)) }

        // domain: helpers and use cases
        single { ReachRule(get<AccountDirectory>(), get(), get(), get()) }
        single { ProfileViews(get(), get()) }
        single { UserNotifier(get(), get()) }
        single { ProfileProvisioner(get(), get(), clock) }
        single { WaitingContactMatcher(get(), get(), get(), get(), clock) }
        single { GetMyProfile(get(), get()) }
        single { UpdateProfile(get(), get(), get(), get(), get(), get()) }
        single { PrepareAvatarUpload(get(), get()) }
        single { SetAvatar(get(), get(), get(), get(), get(), get(), get(), get()) }
        single { RemoveAvatar(get(), get(), get(), get(), get(), get(), get()) }
        single { CheckHandle(get()) }
        single { ClaimHandle(get(), get(), get(), get(), get(), get(), get(), clock) }
        single { SyncContacts(get<AccountDirectory>(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { ListContacts(get(), get(), get()) }
        single { LookupHandle(get(), get(), get(), get(), get()) }
        single { GetPublicProfile(get(), get(), get()) }
        single { Connect(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), clock) }
        single { CreateInvite(get(), get(), clock) }
        single { RevokeInvite(get(), get()) }
        single { ListBlocks(get(), get(), get()) }
        single { BlockUser(get(), get(), get(), get(), clock) }
        single { UnblockUser(get(), get(), get(), get(), clock) }
        single { CheckReach(get()) }
        single { RemoveContact(get(), get(), get(), get(), clock) }
        single { SearchContacts(get(), get(), get()) }
        single { CanReach(get()) }
        single { CheckBlockedEitherWay(get()) }
        single { FilterReachable(get()) }
        single { GetProfiles(get(), get()) }
        single { ReplayFrames(get()) }
        single { CreateProfileOnActivation(get(), get(), get()) }
        single { RecheckWaitingContacts(get(), get(), get()) }
        single { ClearReachOnStatusChange(get()) }
        single { DeleteUserData(get(), get(), get(), get(), get(), get(), clock) }

        // presentation
        single { ProfileService(get(), get(), get(), get(), get(), get()) }
        single { HandleService(get(), get(), get()) }
        single { ContactService(get(), get(), get(), get(), get(), get(), get()) }
        single { BlockService(get(), get(), get(), get()) }
        single { DirectoryService(get(), get(), get(), get(), get()) }
        single { IdentityEventsService(get(), get(), get(), get()) }
        single<RouteContributor>(named("users.routes")) {
            val profiles = get<ProfileService>()
            val handles = get<HandleService>()
            val contacts = get<ContactService>()
            val blocks = get<BlockService>()
            RouteContributor { route ->
                route.profileController(profiles)
                route.handleController(handles)
                route.contactController(contacts)
                route.blockController(blocks)
                route.agentController(contacts)
            }
        }
        single<EventSubscription>(named("users.identity-events")) { IdentityEventHandler(get()).subscription() }

        // users:api, for messaging, wallet and calls, and the replay the gateway asks for
        single<ReachPolicy> { ReachPolicyImpl(get()) }
        single<Profiles> { ProfilesImpl(get()) }
        single(named("users.replay")) { RealtimeModule("users", UsersRealtimeReplay(get())) }
    }
