package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.ports.AccessTokenVerifier
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/** What the gateway asks on every request: a good signature, not expired, and the session is not on the deny-list. */
class VerifyAccessToken(private val tokens: AccessTokenVerifier, private val cache: IdentityCache) {
    suspend operator fun invoke(accessToken: String): Resource<Principal> {
        val claims = tokens.verify(accessToken) ?: return Resource.error(SharedError.TokenInvalid)
        if (cache.isRevoked(claims.sessionId)) return Resource.error(com.circaso.identity.domain.errors.IdentityError.SessionRevoked)
        return Resource.success(Principal(claims.userId, claims.deviceId, claims.sessionId, claims.scope))
    }
}
