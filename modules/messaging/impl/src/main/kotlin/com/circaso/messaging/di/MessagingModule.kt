package com.circaso.messaging.di

import com.circaso.identity.api.DeviceSignatures
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.data.cache.REDIS_PREFIX
import com.circaso.messaging.data.cache.RealtimeFramesImpl
import com.circaso.messaging.data.cache.RedisBundleRateLimit
import com.circaso.messaging.data.cache.RedisMembersCache
import com.circaso.messaging.data.entities.ChatRow
import com.circaso.messaging.data.entities.DirectChatClaimRow
import com.circaso.messaging.data.entities.KS
import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.MemberRow
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.MessagingRealtimeRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.data.entities.UserChatRow
import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.data.migrations.V002_CreateKeyDirectoryTables
import com.circaso.messaging.data.migrations.V003_CreateChatTables
import com.circaso.messaging.data.migrations.V004_AddChatOwner
import com.circaso.messaging.data.migrations.messagingEntities
import com.circaso.messaging.data.repositories.ChatRepositoryImpl
import com.circaso.messaging.data.repositories.KeyRepositoryImpl
import com.circaso.messaging.data.repositories.MessagingOutboxStore
import com.circaso.messaging.data.repositories.MessagingStoreImpl
import com.circaso.messaging.data.rows
import com.circaso.messaging.domain.ports.BundleRateLimit
import com.circaso.messaging.domain.ports.MembersCache
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.messaging.domain.ports.RealtimeLog
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.ChatWriter
import com.circaso.messaging.domain.support.KeyProofs
import com.circaso.messaging.domain.support.MemberRemoval
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.SharedGroups
import com.circaso.messaging.domain.usecases.AddMembers
import com.circaso.messaging.domain.usecases.AddOneTimeKeys
import com.circaso.messaging.domain.usecases.CreateGroup
import com.circaso.messaging.domain.usecases.FetchKeyBundle
import com.circaso.messaging.domain.usecases.GetChat
import com.circaso.messaging.domain.usecases.GetKeyStatus
import com.circaso.messaging.domain.usecases.GetMembers
import com.circaso.messaging.domain.usecases.LeaveGroup
import com.circaso.messaging.domain.usecases.ListChats
import com.circaso.messaging.domain.usecases.OpenDirectChat
import com.circaso.messaging.domain.usecases.PublishKeys
import com.circaso.messaging.domain.usecases.RemoveMember
import com.circaso.messaging.domain.usecases.ReplayFrames
import com.circaso.messaging.domain.usecases.SetRole
import com.circaso.messaging.domain.usecases.UpdateGroup
import com.circaso.messaging.presentation.api.ChatDirectoryImpl
import com.circaso.messaging.presentation.api.MessagingRealtimeReplay
import com.circaso.messaging.presentation.controllers.chatController
import com.circaso.messaging.presentation.controllers.keyController
import com.circaso.messaging.presentation.services.ChatService
import com.circaso.messaging.presentation.services.KeyService
import com.circaso.messaging.presentation.services.RealtimeService
import com.circaso.shared.RealtimeModule
import com.circaso.users.api.Profiles
import com.circaso.users.api.ReachPolicy
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock

/**
 * Messaging's Koin bindings: its Kandra spec, its outbox, the key directory (MS2), chats and membership (MS3), its
 * replay (`RealtimeModule`) and its `messaging:api`. The phone-frame handler (`RealtimeInboundModule`) and event
 * subscriptions are bound here as their slices arrive.
 */
@Suppress("UNUSED_PARAMETER")
fun messagingModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val messaging = MessagingConfig()

        single { messaging }

        // data
        single<OutboxStore>(named("messaging.outbox")) { MessagingOutboxStore(rows<MessagingOutboxRow>()) }
        single(named("messaging.spec")) {
            KandraModuleSpec(
                KS,
                messagingEntities,
                listOf(
                    V001_CreateMessagingTables,
                    V002_CreateKeyDirectoryTables,
                    V003_CreateChatTables,
                    V004_AddChatOwner,
                ),
            )
        }
        single<KeyRepository> { KeyRepositoryImpl(get(), rows<KeyBundleRow>(), rows<OneTimePreKeyRow>()) }
        single<BundleRateLimit> { RedisBundleRateLimit(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<ChatRepository> {
            ChatRepositoryImpl(rows<ChatRow>(), rows<DirectChatClaimRow>(), rows<MemberRow>(), rows<UserChatRow>())
        }
        single<MessagingStore> {
            MessagingStoreImpl(
                get(),
                rows<ChatRow>(),
                rows<MemberRow>(),
                rows<UserChatRow>(),
                rows<MessagingOutboxRow>(),
                get(),
            )
        }
        single<MembersCache> { RedisMembersCache(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single {
            RealtimeFramesImpl(get<RedisScopes>().scope(REDIS_PREFIX), rows<MessagingRealtimeRow>(), get(), clock)
        }
        single<RealtimeFrames> { get<RealtimeFramesImpl>() }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }

        // domain: helpers
        single { KeyProofs(get<DeviceSignatures>(), clock, messaging.proofWindow) }
        single { Membership(get(), get()) }
        single { ChatViews(get(), get<Profiles>()) }
        single { ChatNotifier(get()) }
        single { ChatWriter(get(), get()) }
        single { MemberRemoval(get(), get(), get(), get(), get(), get()) }
        single { SharedGroups(get()) }

        // domain: use cases
        single { PublishKeys(get(), get(), clock) }
        single { AddOneTimeKeys(get(), get(), get()) }
        single { GetKeyStatus(get()) }
        single { FetchKeyBundle(get(), get<ReachPolicy>(), get(), get(), get()) }
        single { OpenDirectChat(get(), get(), get<ReachPolicy>(), get(), get(), clock) }
        single { CreateGroup(get(), get(), get(), get<ReachPolicy>(), get(), get(), get(), get(), clock) }
        single { ListChats(get(), get()) }
        single { GetChat(get(), get()) }
        single { UpdateGroup(get(), get(), get(), get(), get()) }
        single { AddMembers(get(), get(), get(), get<ReachPolicy>(), get(), get(), get(), get(), get(), clock) }
        single { RemoveMember(get(), get()) }
        single { LeaveGroup(get(), get()) }
        single { SetRole(get(), get(), get(), get(), get(), get()) }
        single { GetMembers(get()) }
        single { ReplayFrames(get()) }

        // presentation
        single { KeyService(get(), get(), get(), get()) }
        single { ChatService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { RealtimeService(get()) }
        single<RouteContributor>(named("messaging.routes")) {
            val keys = get<KeyService>()
            val chats = get<ChatService>()
            RouteContributor { route ->
                route.keyController(keys)
                route.chatController(chats)
            }
        }

        // messaging:api, for wallet and calls, and the replay the gateway asks for
        single<ChatDirectory> { ChatDirectoryImpl(get()) }
        single(named("messaging.replay")) { RealtimeModule("messaging", MessagingRealtimeReplay(get())) }
    }
