package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.RefreshCommand
import com.circaso.identity.domain.models.RefreshedSession
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.identity.domain.support.sha256Hex
import com.circaso.shared.Resource
import java.security.MessageDigest

/**
 * I7: a new token pair for a signed refresh request. The old refresh token dies; presenting an old one again
 * ends the whole session. Only the registered, active phone can refresh.
 */
class RefreshSession(
    private val sessions: SessionRepository,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val opener: SessionOpener,
    private val proofs: DeviceProofs,
) {
    suspend operator fun invoke(command: RefreshCommand): Resource<RefreshedSession> {
        val sessionId = SessionOpener.sessionIdOf(command.refreshToken) ?: return Resource.error(IdentityError.SessionRevoked)
        val session = sessions.find(sessionId) ?: return Resource.error(IdentityError.SessionRevoked)
        if (session.status != SessionStatus.ACTIVE || session.deviceId != command.deviceId) return Resource.error(IdentityError.SessionRevoked)

        val account = accounts.find(session.userId)
        val device = devices.find(session.userId, session.deviceId)
        if (account == null || account.status != AccountStatus.ACTIVE || account.activeDeviceId != session.deviceId) {
            return Resource.error(IdentityError.SessionRevoked)
        }
        if (!proofs.valid(device, DeviceProofs.refreshPayload(command.refreshToken, command.proof), command.proof)) {
            return Resource.error(IdentityError.DeviceProofInvalid)
        }

        val presented = sha256Hex(command.refreshToken)
        if (!MessageDigest.isEqual(presented.toByteArray(), session.currentTokenHash.toByteArray())) {
            // An old refresh token: someone holds a copy. End the session.
            store.commit(
                Changes(
                    sessions = listOf(session.copy(status = SessionStatus.REVOKED)),
                    securityEvents = listOf(SecurityEvent(session.userId, null, SecurityEventType.SESSION_REUSE, device?.model, null)),
                ),
            )
            cache.revoke(session.sessionId)
            return Resource.error(IdentityError.SessionRevoked)
        }

        val issued = opener.rotate(account, session)
        store.commit(Changes(sessions = listOf(issued.session)))
        return Resource.success(RefreshedSession(issued.appTokens(), issued.agentTokens()))
    }
}
