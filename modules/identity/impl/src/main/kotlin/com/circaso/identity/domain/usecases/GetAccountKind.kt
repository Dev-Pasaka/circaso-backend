package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountKind
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** For other modules: a person's account or Circaso support's? Null if there is no such account. */
class GetAccountKind(private val accounts: AccountRepository) {
    suspend operator fun invoke(userId: UserId): Resource<AccountKind?> = Resource.success(accounts.find(userId)?.kind)
}
