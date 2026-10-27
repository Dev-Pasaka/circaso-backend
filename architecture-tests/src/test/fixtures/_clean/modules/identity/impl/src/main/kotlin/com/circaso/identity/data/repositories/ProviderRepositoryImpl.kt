package com.circaso.identity.data.repositories

import com.circaso.identity.data.tables.ProvidersTable
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import org.jetbrains.exposed.v1.r2dbc.insert

class ProviderRepositoryImpl(private val db: Database) : ProviderRepository {
    override suspend fun link(userId: UserId, provider: String, subject: String) {
        db.tx("providers.link") {
            ProvidersTable.insert {
                it[ProvidersTable.userId] = userId.value
                it[ProvidersTable.provider] = provider
                it[ProvidersTable.subject] = subject
            }
        }
    }
}

class DatabaseTransactor(private val db: Database) : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = db.tx(operation) { block() }
}
