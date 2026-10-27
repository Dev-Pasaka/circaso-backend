package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.UserId

class VerifyEmail(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
) {
    // A name built from a value would put the user id in the log line.
    suspend operator fun invoke(userId: UserId) = transactor.run("email.verify.$userId") { accounts.find(userId) }
}
