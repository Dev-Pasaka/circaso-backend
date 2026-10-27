package com.circaso.identity.domain.support

import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.UserId

// A domain helper outside `usecases` opens transactions too, and is held to the same rule (PG5 review).
class PhoneClaims(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val claimCache: ClaimCache,
) {
    suspend fun claim(userId: UserId) {
        transactor.run("phone.claim") {
            accounts.find(userId)
            claimCache.forget(userId)
        }
    }
}
