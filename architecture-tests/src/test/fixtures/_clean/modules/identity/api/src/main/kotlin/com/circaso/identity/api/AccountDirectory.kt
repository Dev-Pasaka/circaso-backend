package com.circaso.identity.api

import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

@Serializable
data class AccountSummary(val userId: UserId, val active: Boolean)

interface AccountDirectory {
    suspend fun find(userId: UserId): Resource<AccountSummary>
}
