package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.support.verifiedUser
import com.circaso.identity.api.TokenVerifier
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId

/**
 * A11, the session re-check at each turn and every 60 s (decision 2026-10-26): [accessToken] must still be a valid
 * user token of [userId]. A revoked session (signed out, phone replaced, account deleted) fails, and so does an expired
 * token, so the phone sends a fresh one on the socket before its 15 minutes are up.
 */
class CheckLiveSession(private val verifier: TokenVerifier) {
    suspend operator fun invoke(userId: UserId, accessToken: String): Resource<Unit> {
        if (verifiedUser(verifier, accessToken) != userId) return Resource.error(SharedError.TokenInvalid)
        return Resource.success(Unit)
    }
}
