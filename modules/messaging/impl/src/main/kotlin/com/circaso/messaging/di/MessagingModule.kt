package com.circaso.messaging.di

import com.circaso.identity.api.DeviceSignatures
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.ObjectStores
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.infrastructure.api.WarmUp
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.data.cache.LiveFramesImpl
import com.circaso.messaging.data.cache.REDIS_PREFIX
import com.circaso.messaging.data.cache.RealtimeFramesImpl
import com.circaso.messaging.data.cache.RedisBundleRateLimit
import com.circaso.messaging.data.cache.RedisMembersCache
import com.circaso.messaging.data.cache.RedisPendingCounts
import com.circaso.messaging.data.cache.RedisPresenceStore
import com.circaso.messaging.data.cache.RedisPresenceWatchers
import com.circaso.messaging.data.cache.RedisReceiptBuffer
import com.circaso.messaging.data.cache.RedisTypingMarks
import com.circaso.messaging.data.repositories.AttachmentRepositoryImpl
import com.circaso.messaging.data.repositories.ChatRepositoryImpl
import com.circaso.messaging.data.repositories.DatabaseTransactor
import com.circaso.messaging.data.repositories.DeliveryRepositoryImpl
import com.circaso.messaging.data.repositories.KeyRepositoryImpl
import com.circaso.messaging.data.repositories.MessagingExpiry
import com.circaso.messaging.data.repositories.MessagingOutboxImpl
import com.circaso.messaging.data.repositories.MessagingWarmUpSample
import com.circaso.messaging.data.repositories.SettingsRepositoryImpl
import com.circaso.messaging.data.storage.ObjectStoreAttachments
import com.circaso.messaging.data.tables.AttachmentsTable
import com.circaso.messaging.data.tables.ChatsTable
import com.circaso.messaging.data.tables.ClientMessagesTable
import com.circaso.messaging.data.tables.GroupMessagesTable
import com.circaso.messaging.data.tables.InboxTable
import com.circaso.messaging.data.tables.KeyBundlesTable
import com.circaso.messaging.data.tables.MembersTable
import com.circaso.messaging.data.tables.MessageRecordsTable
import com.circaso.messaging.data.tables.OneTimePreKeysTable
import com.circaso.messaging.data.tables.RealtimeTable
import com.circaso.messaging.data.tables.SettingsTable
import com.circaso.messaging.domain.ports.AttachmentStorage
import com.circaso.messaging.domain.ports.BundleRateLimit
import com.circaso.messaging.domain.ports.LiveFrames
import com.circaso.messaging.domain.ports.MembersCache
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.ports.PresenceStore
import com.circaso.messaging.domain.ports.PresenceWatchers
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.messaging.domain.ports.RealtimeLog
import com.circaso.messaging.domain.ports.ReceiptBuffer
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.ports.TypingMarks
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatPresence
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.InboxRemoval
import com.circaso.messaging.domain.support.KeyProofs
import com.circaso.messaging.domain.support.MemberRemoval
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessageSenders
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.OrderedIds
import com.circaso.messaging.domain.support.ReceiptBatcher
import com.circaso.messaging.domain.usecases.AckDelivered
import com.circaso.messaging.domain.usecases.AddMembers
import com.circaso.messaging.domain.usecases.AddOneTimeKeys
import com.circaso.messaging.domain.usecases.CompleteAttachmentUpload
import com.circaso.messaging.domain.usecases.CreateGroup
import com.circaso.messaging.domain.usecases.DeleteForEveryone
import com.circaso.messaging.domain.usecases.FetchKeyBundle
import com.circaso.messaging.domain.usecases.GetAdmins
import com.circaso.messaging.domain.usecases.GetAttachmentDownload
import com.circaso.messaging.domain.usecases.GetChat
import com.circaso.messaging.domain.usecases.GetKeyStatus
import com.circaso.messaging.domain.usecases.GetMembers
import com.circaso.messaging.domain.usecases.GetOwner
import com.circaso.messaging.domain.usecases.GetPresence
import com.circaso.messaging.domain.usecases.GetStaticMap
import com.circaso.messaging.domain.usecases.IsGroup
import com.circaso.messaging.domain.usecases.LeaveGroup
import com.circaso.messaging.domain.usecases.ListChats
import com.circaso.messaging.domain.usecases.ListGroupIds
import com.circaso.messaging.domain.usecases.MarkRead
import com.circaso.messaging.domain.usecases.OpenDirectChat
import com.circaso.messaging.domain.usecases.PrepareAttachmentUpload
import com.circaso.messaging.domain.usecases.PublishKeys
import com.circaso.messaging.domain.usecases.PurgeAttachments
import com.circaso.messaging.domain.usecases.ReadInbox
import com.circaso.messaging.domain.usecases.RelayTyping
import com.circaso.messaging.domain.usecases.RemoveMember
import com.circaso.messaging.domain.usecases.ReplayFrames
import com.circaso.messaging.domain.usecases.RetireReplacedPhone
import com.circaso.messaging.domain.usecases.SendMessage
import com.circaso.messaging.domain.usecases.SetRole
import com.circaso.messaging.domain.usecases.SweepPresence
import com.circaso.messaging.domain.usecases.UpdateGroup
import com.circaso.messaging.domain.usecases.UpdatePresence
import com.circaso.messaging.domain.usecases.UpdateSettings
import com.circaso.messaging.domain.usecases.WatchPresence
import com.circaso.messaging.presentation.agent.agentController
import com.circaso.messaging.presentation.api.ChatDirectoryImpl
import com.circaso.messaging.presentation.api.MessagingRealtimeInbound
import com.circaso.messaging.presentation.api.MessagingRealtimeReplay
import com.circaso.messaging.presentation.controllers.attachmentController
import com.circaso.messaging.presentation.controllers.chatController
import com.circaso.messaging.presentation.controllers.keyController
import com.circaso.messaging.presentation.controllers.mapController
import com.circaso.messaging.presentation.controllers.messageController
import com.circaso.messaging.presentation.controllers.presenceController
import com.circaso.messaging.presentation.events.IdentityEventHandler
import com.circaso.messaging.presentation.services.AttachmentService
import com.circaso.messaging.presentation.services.ChatService
import com.circaso.messaging.presentation.services.IdentityEventsService
import com.circaso.messaging.presentation.services.KeyService
import com.circaso.messaging.presentation.services.MapService
import com.circaso.messaging.presentation.services.MessageService
import com.circaso.messaging.presentation.services.MessagingAgentService
import com.circaso.messaging.presentation.services.PhoneFrameService
import com.circaso.messaging.presentation.services.PresenceService
import com.circaso.messaging.presentation.services.RealtimeService
import com.circaso.messaging.presentation.warmup.MessagingWarmUp
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
import java.time.Duration

/** Schema `messaging`: its tables, its Flyway scripts and its outbox, which is infrastructure's (`outbox = true`). */
internal val messagingDatabaseSpec =
    DatabaseModuleSpec(
        schema = "messaging",
        tables =
            listOf(
                ChatsTable,
                MembersTable,
                KeyBundlesTable,
                OneTimePreKeysTable,
                InboxTable,
                GroupMessagesTable,
                MessageRecordsTable,
                ClientMessagesTable,
                SettingsTable,
                RealtimeTable,
                AttachmentsTable,
            ),
        migrationsLocation = "classpath:db/messaging",
        outbox = true,
    )

/** How often `messaging.purge-expired` runs. */
private val PURGE_INTERVAL: Duration = Duration.ofMinutes(5)

/** How often `messaging.presence-sweep` looks for phones that went quiet: offline at most this long after 75 s. */
private val PRESENCE_SWEEP_INTERVAL: Duration = Duration.ofSeconds(5)

/** How often `messaging.purge-attachments` runs: attachments live 30 days, so an hour late costs nothing. */
private val ATTACHMENT_PURGE_INTERVAL: Duration = Duration.ofHours(1)

/**
 * Messaging's Koin bindings: its database spec (with the outbox) and purge jobs, the key directory (MS2), chats and
 * membership (MS3), sending and delivery (MS4) with the phone-frame handler (`RealtimeInboundModule`) and its
 * `events:identity` subscription, encrypted attachments (AT1-AT3), its replay (`RealtimeModule`) and its
 * `messaging:api`.
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
        single(named("messaging.spec")) { messagingDatabaseSpec }
        single(named("messaging.database")) { get<Databases>().scope(messagingDatabaseSpec.schema) }
        single<Transactor> { DatabaseTransactor(get(named("messaging.database"))) }
        single<MessagingOutbox> { MessagingOutboxImpl(get(named("messaging.database"))) }
        single<KeyRepository> { KeyRepositoryImpl(get(named("messaging.database"))) }
        single<ChatRepository> { ChatRepositoryImpl(get(named("messaging.database"))) }
        single<DeliveryRepository> { DeliveryRepositoryImpl(get(named("messaging.database")), clock) }
        single<SettingsRepository> { SettingsRepositoryImpl(get(named("messaging.database"))) }
        single(named("messaging.purge-expired")) {
            val expiry = MessagingExpiry(get(named("messaging.database")))
            ScheduledJob("messaging.purge-expired", PURGE_INTERVAL) { expiry.run() }
        }
        single<AttachmentRepository> { AttachmentRepositoryImpl(get(named("messaging.database"))) }
        single<AttachmentStorage> { ObjectStoreAttachments(get<ObjectStores>().scope("messaging")) }
        single(named("messaging.purge-attachments")) {
            val attachments = get<AttachmentService>()
            ScheduledJob("messaging.purge-attachments", ATTACHMENT_PURGE_INTERVAL) { attachments.purge() }
        }
        single<BundleRateLimit> { RedisBundleRateLimit(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<MembersCache> { RedisMembersCache(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single {
            RealtimeFramesImpl(
                get<RedisScopes>().scope(REDIS_PREFIX),
                get(named("messaging.database")),
                get(),
                clock,
            )
        }
        single<RealtimeFrames> { get<RealtimeFramesImpl>() }
        single<RealtimeLog> { get<RealtimeFramesImpl>() }
        single<LiveFrames> { LiveFramesImpl(get<Realtime>(), clock) }
        single<PendingCounts> { RedisPendingCounts(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<ReceiptBuffer> { RedisReceiptBuffer(get<RedisScopes>().scope(REDIS_PREFIX), messaging.receiptBatch) }
        single<TypingMarks> { RedisTypingMarks(get<RedisScopes>().scope(REDIS_PREFIX), messaging.typingDedupe) }
        single<PresenceStore> { RedisPresenceStore(get<RedisScopes>().scope(REDIS_PREFIX)) }
        single<PresenceWatchers> {
            RedisPresenceWatchers(get<RedisScopes>().scope(REDIS_PREFIX), messaging.presenceWatch)
        }
        single(named("messaging.presence-sweep")) {
            val presence = get<PresenceService>()
            ScheduledJob("messaging.presence-sweep", PRESENCE_SWEEP_INTERVAL) { presence.sweep() }
        }

        // domain: helpers
        single { KeyProofs(get<DeviceSignatures>(), clock, messaging.proofWindow) }
        single { Membership(get(), get()) }
        single { ChatViews(get(), get<Profiles>()) }
        single { ChatNotifier(get()) }
        single { MemberRemoval(get(), get(), get(), get(), get(), get()) }
        single { OrderedIds(clock) }
        single { DeliveryNotifier(get(), get(), messaging) }
        single { ReceiptBatcher(get(), get(), background, clock, messaging) }
        single { InboxRemoval(get(), get()) }
        single { MessageSenders(get()) }
        single { ChatPresence(get(), get<ReachPolicy>(), get(), messaging) }

        // domain: use cases
        single { PublishKeys(get(), get(), get(), clock) }
        single { AddOneTimeKeys(get(), get(), get(), get(), clock) }
        single { GetKeyStatus(get()) }
        single { FetchKeyBundle(get(), get<ReachPolicy>(), get(), get(), get()) }
        // SpacePrivateChats is spaces' (it implements messaging:api's guard and binds it in its own Koin module).
        single { OpenDirectChat(get(), get(), get(), get<ReachPolicy>(), get(), get(), clock, getOrNull()) }
        single { CreateGroup(get(), get(), get(), get<ReachPolicy>(), get(), get(), get(), get(), clock) }
        single { ListChats(get(), get()) }
        single { GetChat(get(), get()) }
        single { UpdateGroup(get(), get(), get(), get(), get(), get()) }
        single {
            AddMembers(get(), get(), get(), get<ReachPolicy>(), get(), get(), get(), get(), get(), clock)
        }
        single { RemoveMember(get()) }
        single { LeaveGroup(get()) }
        single { SetRole(get(), get(), get(), get(), get()) }
        single { GetMembers(get()) }
        single { GetAdmins(get()) }
        single { GetOwner(get()) }
        single { IsGroup(get()) }
        single { ListGroupIds(get()) }
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
                getOrNull(),
            )
        }
        single { ReadInbox(get(), messaging) }
        single { AckDelivered(get(), get(), get(), clock) }
        single { MarkRead(get(), get(), get(), get(), clock) }
        single { RelayTyping(get(), get(), get(), get(), messaging) }
        single { UpdatePresence(get(), get(), get(), messaging, clock) }
        single { WatchPresence(get(), get(), get(), messaging, clock) }
        single { GetPresence(get(), clock) }
        single { SweepPresence(get(), get(), get(), messaging, clock) }
        single { DeleteForEveryone(get(), get(), get(), get(), get(), get(), get(), messaging, clock) }
        single { UpdateSettings(get()) }
        single { PrepareAttachmentUpload(get(), get(), get(), get(), messaging, clock) }
        single { CompleteAttachmentUpload(get(), get(), get(), clock) }
        single { GetAttachmentDownload(get(), get(), get(), messaging, clock) }
        single { PurgeAttachments(get(), get(), get(), messaging, clock) }
        single { RetireReplacedPhone(get(), get(), get(), get(), get(), get(), get()) }

        // presentation
        single { KeyService(get(), get(), get(), get()) }
        single {
            ChatService(
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
        single { RealtimeService(get()) }
        single { MessageService(get(), get(), get(), get(), get()) }
        single { PhoneFrameService(get(), get(), get()) }
        single { PresenceService(get(), get(), get(), get()) }
        single { IdentityEventsService(get()) }
        single { AttachmentService(get(), get(), get(), get()) }
        single { GetStaticMap(get()) }
        single { MapService(get()) }
        single { MessagingAgentService(get()) }
        single<RouteContributor>(named("messaging.routes")) {
            val keys = get<KeyService>()
            val chats = get<ChatService>()
            val messages = get<MessageService>()
            val attachments = get<AttachmentService>()
            val maps = get<MapService>()
            val agent = get<MessagingAgentService>()
            val presence = get<PresenceService>()
            RouteContributor { route ->
                route.keyController(keys)
                route.chatController(chats)
                route.messageController(messages, messaging.inboxPageMax)
                route.presenceController(presence)
                route.attachmentController(attachments)
                route.mapController(maps)
                route.agentController(agent)
            }
        }
        single<EventSubscription>(named("messaging.identity-events")) { IdentityEventHandler(get()).subscription() }

        // messaging:api, for wallet and calls; the replay and phone frames the gateway hands over
        single<ChatDirectory> { ChatDirectoryImpl(get()) }
        single(named("messaging.replay")) { RealtimeModule("messaging", MessagingRealtimeReplay(get())) }
        single(named("messaging.inbound")) {
            RealtimeInboundModule("messaging", MessagingRealtimeInbound(get(), get(), messaging.inboxPageMax))
        }

        // read-only, run at start-up before the app reports ready
        single<WarmUp>(named("messaging.warm-up")) {
            MessagingWarmUp(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                MessagingWarmUpSample(get(named("messaging.database"))),
            )
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
