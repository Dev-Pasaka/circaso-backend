package com.circaso.app

import com.circaso.assistant.di.assistantModule
import com.circaso.calls.di.callsModule
import com.circaso.chama.di.chamaModule
import com.circaso.identity.di.identityModule
import com.circaso.infrastructure.api.Config
import com.circaso.messaging.di.messagingModule
import com.circaso.spaces.di.spacesModule
import com.circaso.status.di.statusModule
import com.circaso.users.di.usersModule
import com.circaso.wallet.di.walletModule
import org.koin.core.module.Module

/**
 * Every business module's Koin module, the only place `app` names them. Each one binds its repositories, use
 * cases, services, controllers and routes, plus what infrastructure collects: its `DatabaseModuleSpec`, its
 * `EventSubscription`s and its `ScheduledJob`s.
 */
fun businessModules(config: Config): List<Module> =
    listOf(
        identityModule(config),
        usersModule(config),
        messagingModule(config),
        walletModule(config),
        chamaModule(config),
        callsModule(config),
        statusModule(config),
        spacesModule(config),
        assistantModule(config),
    )
