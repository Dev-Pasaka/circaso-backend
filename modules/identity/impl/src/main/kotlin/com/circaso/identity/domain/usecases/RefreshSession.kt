package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.RefreshCommand
import com.circaso.identity.domain.models.RefreshedSession
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.support.RefreshOutcome
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.identity.domain.support.sha256Hex
import com.circaso.shared.Resource
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * I7: a new token pair for a signed refresh request. The old refresh token dies; presenting an old one again
 * ends the whole session. Only the registered, active phone can refresh.
 *
 * The check and the rotation run in one transaction under the account's lock, so two refreshes with the same token
 * can't both rotate: the second sees the new hash and is treated as a reuse.
 */
class RefreshSession(
    private val transactor: Transactor,
    private val sessions: SessionRepository,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val opener: SessionOpener,
    private val proofs: DeviceProofs,
) {
    suspend operator fun invoke(command: RefreshCommand): Resource<RefreshedSession> {
        val sessionId =
            SessionOpener.sessionIdOf(command.refreshToken) ?: return Resource.error(IdentityError.SessionRevoked)
        // Read once to learn whose session it is, so the account can be locked before the session is read again.
        val owner = sessions.find(sessionId)?.userId ?: return Resource.error(IdentityError.SessionRevoked)

        val outcome =
            transactor.run("sessions.refresh") {
                accounts.lock(owner)
                val session = sessions.find(sessionId)
                if (session == null) RefreshOutcome.Refused(IdentityError.SessionRevoked) else decide(command, session)
            }
        return when (outcome) {
            is RefreshOutcome.Refused -> {
                Resource.error(outcome.error)
            }

            is RefreshOutcome.Reused -> {
                cache.revoke(outcome.session.sessionId)
                Resource.error(IdentityError.SessionRevoked)
            }

            is RefreshOutcome.Rotated -> {
                Resource.success(RefreshedSession(outcome.issued.appTokens(), outcome.issued.agentTokens()))
            }
        }
    }

    /** Inside the transaction: refuse, end the session on a reused token, or rotate it. */
    private suspend fun decide(command: RefreshCommand, session: Session): RefreshOutcome {
        if (session.status != SessionStatus.ACTIVE || session.deviceId != command.deviceId) {
            return RefreshOutcome.Refused(IdentityError.SessionRevoked)
        }
        val account = accounts.find(session.userId)
        val device = devices.find(session.userId, session.deviceId)
        if (account?.isSupport == true) return RefreshOutcome.Refused(IdentityError.SupportAccountNotOnMobile)
        if (account == null || account.status != AccountStatus.ACTIVE || account.activeDeviceId != session.deviceId) {
            return RefreshOutcome.Refused(IdentityError.SessionRevoked)
        }
        if (!proofs.valid(device, DeviceProofs.refreshPayload(command.refreshToken, command.proof), command.proof)) {
            return RefreshOutcome.Refused(IdentityError.DeviceProofInvalid)
        }

        val presented = sha256Hex(command.refreshToken)
        if (!MessageDigest.isEqual(presented.toByteArray(), session.currentTokenHash.toByteArray())) {
            // An old refresh token: someone holds a copy. End the session.
            store.write(
                Changes(
                    sessions = listOf(session.copy(status = SessionStatus.REVOKED)),
                    securityEvents =
                        listOf(
                            SecurityEvent(session.userId, null, SecurityEventType.SESSION_REUSE, device?.model, null),
                        ),
                ),
            )
            return RefreshOutcome.Reused(session)
        }

        val issued = opener.rotate(account, session)
        store.write(Changes(sessions = listOf(issued.session)))
        markSeen(device, issued.session.rotatedAt)
        return RefreshOutcome.Rotated(issued)
    }

    /**
     * The phone is in use: its `last_seen_at` moves on, at most once an hour, which is what staff count as an active
     * person (decision 2026-11-08). Before, nothing moved it after I6, so every phone looked last seen when registered.
     */
    private suspend fun markSeen(device: Device?, at: Instant) {
        if (device == null || device.lastSeenAt.isAfter(at.minus(SEEN_EVERY))) return
        devices.touch(device.userId, device.deviceId, at)
    }

    private companion object {
        val SEEN_EVERY: Duration = Duration.ofHours(1)
    }
}
