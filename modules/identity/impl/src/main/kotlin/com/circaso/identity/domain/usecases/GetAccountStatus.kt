package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** For other modules: is this account active, suspended, deleted? Null if there is no such account. */
class GetAccountStatus(private val accounts: AccountRepository) {
    suspend operator fun invoke(userId: UserId): Resource<AccountStatus?> =
        Resource.success(accounts.find(userId)?.status)
}
