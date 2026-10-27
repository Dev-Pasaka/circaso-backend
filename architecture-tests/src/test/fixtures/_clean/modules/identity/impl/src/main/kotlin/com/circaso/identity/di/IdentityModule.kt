package com.circaso.identity.di

import com.circaso.identity.data.repositories.DatabaseTransactor
import com.circaso.identity.data.repositories.ProviderRepositoryImpl
import com.circaso.identity.data.tables.ProvidersTable
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import org.koin.dsl.module

val identityModule =
    module {
        single { DatabaseModuleSpec("identity", listOf(ProvidersTable), "classpath:db/identity") }
        single { get<Databases>().scope("identity") }
        single<Transactor> { DatabaseTransactor(get()) }
        single<ProviderRepository> { ProviderRepositoryImpl(get()) }
    }
