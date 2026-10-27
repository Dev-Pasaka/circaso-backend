package com.circaso.identity.data.repositories

import com.circaso.identity.data.entities.AccountRow
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId

class AccountRepositoryImpl(private val database: Database) : AccountRepository {
    override suspend fun find(id: UserId): Account? = null

    private fun AccountRow.toDomain(): Account = Account(UserId(userId), active)
}
