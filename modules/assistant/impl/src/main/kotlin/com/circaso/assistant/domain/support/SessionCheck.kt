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
suspend fun verifiedUser(verifier: TokenVerifier, accessToken: String): UserId? =
    verified(verifier, accessToken, Scope.APP)

/**
 * Whether [agentToken] is a valid agent token of [userId] (decision 2026-10-26). Asha's tools act with it, so it must be
 * the caller's own; no token at all is fine (Asha then has no lookup tools).
 */
suspend fun isOwnAgentToken(
    verifier: TokenVerifier,
    userId: UserId,
    agentToken: String?,
): Boolean = agentToken == null || verified(verifier, agentToken, Scope.AGENT) == userId

private suspend fun verified(
    verifier: TokenVerifier,
    token: String,
    scope: Scope,
): UserId? {
    val verified =
        try {
            verifier.verify(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
    val principal = (verified as? Resource.Success)?.data ?: return null
    return principal.userId.takeIf { principal.scope == scope }
}
