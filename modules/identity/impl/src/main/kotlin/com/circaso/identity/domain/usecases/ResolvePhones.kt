package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** For contact sync: which of these numbers belong to active accounts. Never hands out a number it was not given. */
class ResolvePhones(private val accounts: AccountRepository) {
    suspend operator fun invoke(phones: Set<PhoneNumber>): Resource<Map<PhoneNumber, UserId>> {
        val found =
            buildMap {
                for (phone in phones) {
                    val account = accounts.findByPhone(phone)
                    if (account != null && account.status == AccountStatus.ACTIVE) put(phone, account.userId)
                }
            }
        return Resource.success(found)
    }
}
