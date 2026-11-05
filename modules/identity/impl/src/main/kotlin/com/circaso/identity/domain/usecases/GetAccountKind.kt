package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountKind
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant

/** For other modules: a person's account or Circaso support's? Null if there is no such account. */
class GetAccountKind(private val accounts: AccountRepository) {
    suspend operator fun invoke(userId: UserId): Resource<AccountKind?> = Resource.success(accounts.find(userId)?.kind)
}

/** For other modules: when the account was made (spaces' 7-day rule). Null if there is no such account. */
class GetAccountCreatedAt(private val accounts: AccountRepository) {
    suspend operator fun invoke(userId: UserId): Resource<Instant?> = Resource.success(accounts.find(userId)?.createdAt)
}
