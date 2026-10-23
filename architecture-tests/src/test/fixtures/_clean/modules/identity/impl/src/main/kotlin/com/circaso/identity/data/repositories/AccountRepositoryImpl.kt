package com.circaso.identity.data.repositories

import com.circaso.identity.data.entities.AccountRow
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.UserId
import com.kandraorm.kandra.runtime.KandraSuspendRepository

class AccountRepositoryImpl(private val rows: KandraSuspendRepository<AccountRow>) : AccountRepository {
    override suspend fun find(id: UserId): Account? = null
}
