package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.ports.AccessTokenVerifier
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * [VerifyAccessToken]'s answer, with [work] run while the deny-list lookup is in flight (see
 * `TokenVerifier.verifyAlongside`). The lookup is sent first and not awaited, so both share one Redis round trip on
 * the shared connection. [work] runs only for a good signature; its result comes back even for a revoked session, so
 * the caller can undo it.
 */
class VerifyAccessTokenAlongside(private val tokens: AccessTokenVerifier, private val cache: IdentityCache) {
    suspend operator fun <T> invoke(
        accessToken: String,
        work: suspend (Principal) -> T,
    ): Pair<Resource<Principal>, T?> {
        val claims = tokens.verify(accessToken) ?: return Resource.error(SharedError.TokenInvalid) to null
        val principal = Principal(claims.userId, claims.deviceId, claims.sessionId, claims.scope)
        return coroutineScope {
            // UNDISPATCHED: the EXISTS is written to the connection before [work] sends its own commands.
            val revoked = async(start = CoroutineStart.UNDISPATCHED) { cache.isRevoked(claims.sessionId) }
            val done = work(principal)
            val answer =
                if (revoked.await()) Resource.error(IdentityError.SessionRevoked) else Resource.success(principal)
            answer to done
        }
    }
}
