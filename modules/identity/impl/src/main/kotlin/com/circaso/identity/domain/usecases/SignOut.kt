package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.api.SignedOut
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.envelope

/**
 * I8: ends this session. The phone stays registered, so signing back in on it is one tap. `SignedOut` tells the
 * gateway to close that session's socket (decision 2026-10-25), which would otherwise keep receiving frames. It is
 * written only by the call that moves the session from active to revoked, so a repeated sign-out publishes nothing.
 */
class SignOut(
    private val transactor: Transactor,
    private val sessions: SessionRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val bus: EventBus,
) {
    suspend operator fun invoke(principal: Principal): Resource<Unit> {
        val signedOut =
            Changes(
                securityEvents = listOf(SecurityEvent(principal.userId, null, SecurityEventType.SIGN_OUT, null, null)),
                events = listOf(bus.envelope(SignedOut(principal.userId, principal.sessionId))),
            )
        val ended =
            transactor.run("sessions.sign-out") {
                val revoked = sessions.revoke(principal.sessionId) != null
                if (revoked) store.write(signedOut)
                revoked
            }
        if (ended) store.publish(signedOut)
        cache.revoke(principal.sessionId)
        return Resource.success(Unit)
    }
}
