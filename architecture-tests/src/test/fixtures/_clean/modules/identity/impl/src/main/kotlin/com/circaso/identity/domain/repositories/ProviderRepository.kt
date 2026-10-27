package com.circaso.identity.domain.repositories

import com.circaso.shared.UserId

interface ProviderRepository {
    suspend fun link(userId: UserId, provider: String, subject: String)
}
