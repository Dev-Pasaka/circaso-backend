package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId

class GetAccount(private val accounts: AccountRepository) {
    suspend operator fun invoke(userId: UserId): Resource<Account> =
        accounts.find(userId)?.let { Resource.success(it) } ?: Resource.error(SharedError.NotFound)

    private fun unused() = Unit
}
