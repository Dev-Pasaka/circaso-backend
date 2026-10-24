package com.circaso.app

import com.circaso.assistant.di.assistantModule
import com.circaso.identity.di.identityModule
import com.circaso.infrastructure.api.Config
import com.circaso.users.di.usersModule
import org.koin.core.module.Module

/**
 * Every business module's Koin module, the only place `app` names them. Each one binds its repositories, use
 * cases, services, controllers and routes, plus the three things infrastructure collects: its
 * `KandraModuleSpec`, its `OutboxStore` and its `EventSubscription`s.
 */
fun businessModules(config: Config): List<Module> =
    listOf(
        identityModule(config),
        usersModule(config),
        assistantModule(config),
    )
