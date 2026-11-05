package com.circaso.identity.presentation.warmup

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.SessionId
import com.circaso.identity.api.TokenVerifier
import com.circaso.identity.domain.ports.TokenIssuer
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.infrastructure.api.WarmUp
import com.circaso.shared.DeviceId
import com.circaso.shared.Scope
import com.circaso.shared.UserId

/**
 * What the gateway asks on every request, read-only: an access token's signature and claims, then the session's
 * deny-list entry in Redis (`EXISTS`), for a token signed here for random ids that no session holds (and one with a
 * broken signature, the refusal path). Then the account, session and device reads behind sign-in and refresh, for the
 * same random ids. The token is never handed out, and no id it names exists.
 */
class IdentityWarmUp(
    private val issuer: TokenIssuer,
    private val verifier: TokenVerifier,
    private val accounts: AccountDirectory,
    private val sessions: SessionRepository,
    private val devices: DeviceRepository,
) : WarmUp {
    override val name = "identity"

    override suspend fun run() {
        val userId = UserId.new()
        val deviceId = DeviceId.new()
        val sessionId = SessionId.new()
        val token = issuer.issue(userId, deviceId, sessionId, Scope.APP, sessionVersion = 1).token
        verifier.verify(token)
        verifier.verify(token.dropLast(2) + if (token.endsWith("AA")) "BB" else "AA")
        accounts.status(userId)
        sessions.find(sessionId)
        devices.find(userId, deviceId)
    }
}
