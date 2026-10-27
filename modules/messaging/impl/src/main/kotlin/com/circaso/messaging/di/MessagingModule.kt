package com.circaso.messaging.di

import com.circaso.identity.api.DeviceSignatures
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.data.cache.LiveFramesImpl
import com.circaso.messaging.data.cache.REDIS_PREFIX
import com.circaso.messaging.data.cache.RealtimeFramesImpl
import com.circaso.messaging.data.cache.RedisBundleRateLimit
import com.circaso.messaging.data.cache.RedisMembersCache
import com.circaso.messaging.data.cache.RedisPendingCounts
import com.circaso.messaging.data.cache.RedisReceiptBuffer
import com.circaso.messaging.data.cache.RedisTypingMarks
import com.circaso.messaging.data.entities.ChatRow
import com.circaso.messaging.data.entities.ClientMessageRow
import com.circaso.messaging.data.entities.DirectChatClaimRow
import com.circaso.messaging.data.entities.GroupMessageRow
import com.circaso.messaging.data.entities.InboxRow
import com.circaso.messaging.data.entities.KS
import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.MemberRow
import com.circaso.messaging.data.entities.MessageRecordRow
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.MessagingRealtimeRow
import com.circaso.messaging.data.entities.MessagingSettingsRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.data.entities.UserChatRow
import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.data.migrations.V002_CreateKeyDirectoryTables
import com.circaso.messaging.data.migrations.V003_CreateChatTables
import com.circaso.messaging.data.migrations.V004_AddChatOwner
import com.circaso.messaging.data.migrations.V005_CreateDeliveryTables
import com.circaso.messaging.data.migrations.messagingEntities
import com.circaso.messaging.data.repositories.ChatMarkersImpl
import com.circaso.messaging.data.repositories.ChatRepositoryImpl
import com.circaso.messaging.data.repositories.DeliveryRepositoryImpl
import com.circaso.messaging.data.repositories.KeyRepositoryImpl
import com.circaso.messaging.data.repositories.MessagingOutboxStore
import com.circaso.messaging.data.repositories.MessagingStoreImpl
import com.circaso.messaging.data.repositories.SettingsRepositoryImpl
import com.circaso.messaging.data.repositories.UnloggedBatches
import com.circaso.messaging.data.rows
import com.circaso.messaging.domain.ports.BundleRateLimit
import com.circaso.messaging.domain.ports.LiveFrames
import com.circaso.messaging.domain.ports.MembersCache
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.messaging.domain.ports.RealtimeLog
import com.circaso.messaging.domain.ports.ReceiptBuffer
import com.circaso.messaging.domain.ports.TypingMarks
import com.circaso.messaging.domain.repositories.ChatMarkers
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.ChatWriter
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.InboxRemoval
import com.circaso.messaging.domain.support.KeyProofs
import com.circaso.messaging.domain.support.MemberRemoval
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessageSenders
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.OrderedIds
import com.circaso.messaging.domain.support.ReceiptBatcher
import com.circaso.messaging.domain.support.SharedGroups
import com.circaso.messaging.domain.usecases.AckDelivered
import com.circaso.messaging.domain.usecases.AddMembers
import com.circaso.messaging.domain.usecases.AddOneTimeKeys
import com.circaso.messaging.domain.usecases.CreateGroup
import com.circaso.messaging.domain.usecases.DeleteForEveryone
import com.circaso.messaging.domain.usecases.FetchKeyBundle
import com.circaso.messaging.domain.usecases.GetChat
import com.circaso.messaging.domain.usecases.GetKeyStatus
import com.circaso.messaging.domain.usecases.GetMembers
import com.circaso.messaging.domain.usecases.LeaveGroup
import com.circaso.messaging.domain.usecases.ListChats
import com.circaso.messaging.domain.usecases.MarkRead
import com.circaso.messaging.domain.usecases.OpenDirectChat
import com.circaso.messaging.domain.usecases.PublishKeys
import com.circaso.messaging.domain.usecases.ReadInbox
import com.circaso.messaging.domain.usecases.RelayTyping
import com.circaso.messaging.domain.usecases.RemoveMember
import com.circaso.messaging.domain.usecases.ReplayFrames
import com.circaso.messaging.domain.usecases.RetireReplacedPhone
import com.circaso.messaging.domain.usecases.SendMessage
import com.circaso.messaging.domain.usecases.SetRole
import com.circaso.messaging.domain.usecases.UpdateGroup
import com.circaso.messaging.domain.usecases.UpdateSettings
import com.circaso.messaging.presentation.api.ChatDirectoryImpl
import com.circaso.messaging.presentation.api.MessagingRealtimeInbound
import com.circaso.messaging.presentation.api.MessagingRealtimeReplay
import com.circaso.messaging.presentation.controllers.chatController
import com.circaso.messaging.presentation.controllers.keyController
import com.circaso.messaging.presentation.controllers.messageController
import com.circaso.messaging.presentation.events.IdentityEventHandler
import com.circaso.messaging.presentation.services.ChatService
import com.circaso.messaging.presentation.services.IdentityEventsService
import com.circaso.messaging.presentation.services.KeyService
import com.circaso.messaging.presentation.services.MessageService
import com.circaso.messaging.presentation.services.PhoneFrameService
import com.circaso.messaging.presentation.services.RealtimeService
import com.circaso.shared.EventSubscription
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeInboundModule
import com.circaso.shared.RealtimeModule
import com.circaso.users.api.Profiles
import com.circaso.users.api.ReachPolicy
import com.circaso.web.RouteContributor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Messaging's Koin bindings: its Kandra spec, its outbox, the key directory (MS2), chats and membership (MS3),
 * sending and delivery (MS4) with the phone-frame handler (`RealtimeInboundModule`) and its `events:identity`
 * subscription, its replay (`RealtimeModule`) and its `messaging:api`.
 */
fun messagingModule(config: Config): Module =
    module {
        val clock = Clock.systemUTC()
        val messaging =
            MessagingConfig(
                frameCiphertextMaxChars =
                    config.int(
                        "messaging.frame-ciphertext-max-chars",
                        FRAME_CIPHERTEXT_MAX_CHARS,
                    ),
            )
        val background = backgroundScope()

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
                    V005_CreateDeliveryTables,
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
        single { UnloggedBatches(get()) }
        single<DeliveryRepository> {
            DeliveryRepositoryImpl(
                get(),
                rows<InboxRow>(),
                rows<GroupMessageRow>(),
                rows<MessageRecordRow>(),
                rows<ClientMessageRow>(),
            )
        }
        single<ChatMarkers> { ChatMarkersImpl(get()) }
        single<SettingsRepository> { SettingsRepositoryImpl(rows<MessagingSettingsRow>()) }
        single<LiveFrames> { LiveFramesImpl(get<Realtime>(), clock) }
        single<PendingCounts> { RedisPendingCounts(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<ReceiptBuffer> { RedisReceiptBuffer(get<RedisScopes>().scope(REDIS_PREFIX), messaging.receiptBatch) }
        single<TypingMarks> { RedisTypingMarks(get<RedisScopes>().scope(REDIS_PREFIX), messaging.typingDedupe) }

        // domain: helpers
        single { KeyProofs(get<DeviceSignatures>(), clock, messaging.proofWindow) }
        single { Membership(get(), get()) }
        single { ChatViews(get(), get<Profiles>()) }
        single { ChatNotifier(get()) }
        single { ChatWriter(get(), get()) }
        single { MemberRemoval(get(), get(), get(), get(), get(), get()) }
        single { SharedGroups(get()) }
        single { OrderedIds(clock) }
        single { DeliveryNotifier(get(), get(), messaging) }
        single { ReceiptBatcher(get(), get(), background, clock, messaging) }
        single { InboxRemoval(get(), get()) }
        single { MessageSenders(get()) }

        // domain: use cases
        single { PublishKeys(get(), get(), clock) }
        single { AddOneTimeKeys(get(), get(), get()) }
        single { GetKeyStatus(get()) }
        single { FetchKeyBundle(get(), get<ReachPolicy>(), get(), get(), get()) }
        single { OpenDirectChat(get(), get(), get<ReachPolicy>(), get(), get(), clock) }
        single { CreateGroup(get(), get(), get<ReachPolicy>(), get(), get(), get(), get(), clock) }
        single { ListChats(get(), get()) }
        single { GetChat(get(), get()) }
        single { UpdateGroup(get(), get(), get(), get(), get()) }
        single { AddMembers(get(), get(), get(), get<ReachPolicy>(), get(), get(), get(), get(), get(), clock) }
        single { RemoveMember(get(), get()) }
        single { LeaveGroup(get(), get()) }
        single { SetRole(get(), get(), get(), get(), get()) }
        single { GetMembers(get()) }
        single { ReplayFrames(get()) }
        single {
            SendMessage(
                get(),
                get(),
                get<ReachPolicy>(),
                get(),
                get(),
                get(),
                get(),
                get(),
                background,
                messaging,
                clock,
            )
        }
        single { ReadInbox(get(), messaging) }
        single { AckDelivered(get(), get(), get(), clock) }
        single { MarkRead(get(), get(), get(), get(), get(), clock) }
        single { RelayTyping(get(), get(), get(), get(), messaging) }
        single { DeleteForEveryone(get(), get(), get(), get(), get(), get(), messaging, clock) }
        single { UpdateSettings(get()) }
        single { RetireReplacedPhone(get(), get(), get(), get(), get(), get()) }

        // presentation
        single { KeyService(get(), get(), get(), get()) }
        single { ChatService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { RealtimeService(get()) }
        single { MessageService(get(), get(), get(), get(), get()) }
        single { PhoneFrameService(get(), get(), get()) }
        single { IdentityEventsService(get()) }
        single<RouteContributor>(named("messaging.routes")) {
            val keys = get<KeyService>()
            val chats = get<ChatService>()
            val messages = get<MessageService>()
            RouteContributor { route ->
                route.keyController(keys)
                route.chatController(chats)
                route.messageController(messages, messaging.inboxPageMax)
            }
        }
        single<EventSubscription>(named("messaging.identity-events")) { IdentityEventHandler(get()).subscription() }

        // messaging:api, for wallet and calls; the replay and phone frames the gateway hands over
        single<ChatDirectory> { ChatDirectoryImpl(get()) }
        single(named("messaging.replay")) { RealtimeModule("messaging", MessagingRealtimeReplay(get())) }
        single(named("messaging.inbound")) {
            RealtimeInboundModule("messaging", MessagingRealtimeInbound(get(), messaging.inboxPageMax))
        }
    }

/** `MESSAGING_FRAME_CIPHERTEXT_MAX_CHARS` when unset (decision 2026-10-27). */
private const val FRAME_CIPHERTEXT_MAX_CHARS = 16 * 1_024

/**
 * Where messaging's work after an answer runs: `message.new` frames and pushes after a send, and receipt batches. A
 * failure there is logged by its class only, never its message, which could carry ids.
 */
private fun backgroundScope(): CoroutineScope {
    val log = LoggerFactory.getLogger("com.circaso.messaging.background")
    val handler = CoroutineExceptionHandler { _, e -> log.warn("background work failed: error={}", e.javaClass.name) }
    return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
}
