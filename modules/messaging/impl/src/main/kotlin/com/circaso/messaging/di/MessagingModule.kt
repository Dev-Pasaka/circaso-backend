package com.circaso.messaging.di

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.data.entities.KS
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.data.migrations.messagingEntities
import com.circaso.messaging.data.repositories.MessagingOutboxStore
import com.circaso.messaging.data.rows
import com.circaso.messaging.presentation.api.ChatDirectoryImpl
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Messaging's Koin bindings. The foundation (MS1) holds its Kandra spec, its outbox and its `messaging:api`; routes,
 * the replay (`RealtimeModule`), the phone-frame handler (`RealtimeInboundModule`) and event subscriptions are bound
 * here as their slices arrive.
 */
@Suppress("UNUSED_PARAMETER")
fun messagingModule(config: Config): Module =
    module {
        // data
        single<OutboxStore>(named("messaging.outbox")) { MessagingOutboxStore(rows<MessagingOutboxRow>()) }
        single(named("messaging.spec")) { KandraModuleSpec(KS, messagingEntities, listOf(V001_CreateMessagingTables)) }

        // messaging:api, for wallet and calls
        single<ChatDirectory> { ChatDirectoryImpl() }
    }
