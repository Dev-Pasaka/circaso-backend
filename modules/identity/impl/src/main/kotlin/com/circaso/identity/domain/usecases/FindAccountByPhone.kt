package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * Staff search by number (decision 2026-11-08), through the unique index on the number. Unlike [ResolvePhones] (contact
 * sync, active accounts only), staff find the account whatever its status: a suspended one is exactly what they look for.
 */
class FindAccountByPhone(private val accounts: AccountRepository) {
    suspend operator fun invoke(phone: PhoneNumber): Resource<UserId?> =
        Resource.success(accounts.findByPhone(phone)?.userId)
}
