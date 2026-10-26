package com.circaso.assistant.domain.support

import com.circaso.identity.api.TokenVerifier
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import kotlinx.coroutines.CancellationException

/**
 * The user behind [accessToken] when it is a valid user (`app`) token, or null for anything else: a bad, expired or
 * revoked token, an agent token, or the verifier failing. The live voice socket treats them all as `unauthorized`.
 */
suspend fun verifiedUser(verifier: TokenVerifier, accessToken: String): UserId? {
    val verified =
        try {
            verifier.verify(accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
    val principal = (verified as? Resource.Success)?.data ?: return null
    return principal.userId.takeIf { principal.scope == Scope.APP }
}
