package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.support.randomToken
import com.circaso.shared.Resource

/** I1: a one-time nonce for the Google/Apple request, valid 5 minutes. */
class IssueNonce(private val cache: IdentityCache) {
    suspend operator fun invoke(): Resource<String> {
        val nonce = randomToken(24)
        cache.saveNonce(nonce)
        return Resource.success(nonce)
    }
}
