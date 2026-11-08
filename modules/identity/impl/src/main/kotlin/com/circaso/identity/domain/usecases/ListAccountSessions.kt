package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.models.SessionRecord
import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * Staff: the account's sessions that haven't run out, newest first (decision 2026-11-08), revoked ones too, so staff
 * see that a suspension ended them. Never a token or its hash.
 */
class ListAccountSessions(private val admin: AccountAdminRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UserId, limit: Int): Resource<List<SessionRecord>> =
        Resource.success(admin.sessions(userId, clock.instant(), limit.coerceIn(1, MAX_SESSIONS)))

    private companion object {
        const val MAX_SESSIONS = 100
    }
}
