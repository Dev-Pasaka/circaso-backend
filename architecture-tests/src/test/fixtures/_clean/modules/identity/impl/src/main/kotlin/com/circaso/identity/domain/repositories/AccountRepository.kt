package com.circaso.identity.domain.repositories

import com.circaso.identity.domain.models.Account
import com.circaso.shared.UserId

interface AccountRepository {
    suspend fun find(id: UserId): Account?
}
