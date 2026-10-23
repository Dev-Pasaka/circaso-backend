package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.shared.Resource

/** I8: ends this session. The phone stays registered, so signing back in on it is one tap. */
class SignOut(
    private val sessions: SessionRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
) {
    suspend operator fun invoke(principal: Principal): Resource<Unit> {
        val session = sessions.find(principal.sessionId)
        if (session != null && session.status == SessionStatus.ACTIVE) {
            store.commit(
                Changes(
                    sessions = listOf(session.copy(status = SessionStatus.REVOKED)),
                    securityEvents = listOf(SecurityEvent(principal.userId, null, SecurityEventType.SIGN_OUT, null, null)),
                ),
            )
        }
        cache.revoke(principal.sessionId)
        return Resource.success(Unit)
    }
}
