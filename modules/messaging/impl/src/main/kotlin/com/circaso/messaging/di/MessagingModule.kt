package com.circaso.messaging.di

import com.circaso.identity.api.DeviceSignatures
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.data.cache.REDIS_PREFIX
import com.circaso.messaging.data.cache.RedisBundleRateLimit
import com.circaso.messaging.data.entities.KS
import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.data.migrations.V002_CreateKeyDirectoryTables
import com.circaso.messaging.data.migrations.messagingEntities
import com.circaso.messaging.data.repositories.KeyRepositoryImpl
import com.circaso.messaging.data.repositories.MessagingOutboxStore
import com.circaso.messaging.data.rows
import com.circaso.messaging.domain.ports.BundleRateLimit
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.support.KeyProofs
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.usecases.AddOneTimeKeys
import com.circaso.messaging.domain.usecases.FetchKeyBundle
import com.circaso.messaging.domain.usecases.GetKeyStatus
import com.circaso.messaging.domain.usecases.PublishKeys
import com.circaso.messaging.presentation.api.ChatDirectoryImpl
import com.circaso.messaging.presentation.controllers.keyController
import com.circaso.messaging.presentation.services.KeyService
import com.circaso.users.api.ReachPolicy
import com.circaso.web.RouteContributor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock

/**
 * Messaging's Koin bindings: its Kandra spec, its outbox, the key directory (MS2) and its `messaging:api`. The replay
 * (`RealtimeModule`), the phone-frame handler (`RealtimeInboundModule`) and event subscriptions are bound here as
 * their slices arrive.
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
                listOf(V001_CreateMessagingTables, V002_CreateKeyDirectoryTables),
            )
        }
        single<KeyRepository> { KeyRepositoryImpl(get(), rows<KeyBundleRow>(), rows<OneTimePreKeyRow>()) }
        single<BundleRateLimit> { RedisBundleRateLimit(get<RedisScopes>().scope(REDIS_PREFIX)) }

        // domain
        single { KeyProofs(get<DeviceSignatures>(), clock, messaging.proofWindow) }
        single { PublishKeys(get(), get(), clock) }
        single { AddOneTimeKeys(get(), get(), get()) }
        single { GetKeyStatus(get()) }
        single { FetchKeyBundle(get(), get<ReachPolicy>(), get(), get()) }

        // presentation
        single { KeyService(get(), get(), get(), get()) }
        single<RouteContributor>(named("messaging.routes")) {
            val keys = get<KeyService>()
            RouteContributor { route -> route.keyController(keys) }
        }

        // messaging:api, for wallet and calls
        single<ChatDirectory> { ChatDirectoryImpl() }
    }
