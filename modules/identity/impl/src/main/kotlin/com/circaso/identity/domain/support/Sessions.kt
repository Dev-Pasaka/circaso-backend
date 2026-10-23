package com.circaso.identity.domain.support

import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.SessionTokens
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.IssuedToken
import com.circaso.identity.domain.ports.TokenIssuer
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.shared.DeviceId
import com.circaso.shared.Scope
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private val random = SecureRandom()
private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

internal fun randomToken(bytes: Int = 32): String = urlEncoder.encodeToString(ByteArray(bytes).also(random::nextBytes))

/** An app session just opened (or rotated), with the refresh token to hand to the phone once. */
data class IssuedSession(val session: Session, val refreshToken: String, val app: IssuedToken, val agent: IssuedToken) {
    fun appTokens() = SessionTokens(app.token, refreshToken, app.expiresInSeconds, Scope.APP.wire)
    fun agentTokens() = SessionTokens(agent.token, null, agent.expiresInSeconds, Scope.AGENT.wire)
}

/** A new session and the older session of the same phone it replaces, which must end with it. */
data class OpenedSession(val issued: IssuedSession, val ended: List<Session>)

/**
 * The rule every way of getting an app session shares: the user token and the agent token are issued together
 * and share one session id; a phone has one live session, so opening another ends the previous one; a refresh
 * token is `rt_<sessionId>.<secret>` and only its hash is kept.
 */
class SessionOpener(
    private val tokens: TokenIssuer,
    private val sessions: SessionRepository,
    private val cache: IdentityCache,
    private val clock: Clock,
) {
    suspend fun open(account: Account, deviceId: DeviceId): OpenedSession {
        val ended = cache.deviceSession(account.userId, deviceId)
            ?.let { sessions.find(it) }
            ?.takeIf { it.status == SessionStatus.ACTIVE }
            ?.copy(status = SessionStatus.REVOKED)
            ?.let { listOf(it) }
            .orEmpty()
        val id = SessionId.new()
        val secret = randomToken()
        val now = clock.instant()
        val session = Session(id, account.userId, deviceId, sha256Hex(refreshString(id, secret)), Scope.APP, SessionStatus.ACTIVE, now, now)
        return OpenedSession(issue(account, session, refreshString(id, secret)), ended)
    }

    /** Same session, new refresh secret and new token pair; the old refresh token stops working. */
    fun rotate(account: Account, session: Session): IssuedSession {
        val secret = randomToken()
        val refresh = refreshString(session.sessionId, secret)
        return issue(account, session.copy(currentTokenHash = sha256Hex(refresh), rotatedAt = clock.instant()), refresh)
    }

    /** After the commit: remember the live session and deny the ended ones to the gateway. */
    suspend fun afterCommit(account: Account, opened: OpenedSession) {
        opened.ended.forEach { cache.revoke(it.sessionId) }
        cache.rememberDeviceSession(account.userId, opened.issued.session.deviceId, opened.issued.session.sessionId)
    }

    private fun issue(account: Account, session: Session, refresh: String): IssuedSession = IssuedSession(
        session = session,
        refreshToken = refresh,
        app = tokens.issue(account.userId, session.deviceId, session.sessionId, Scope.APP, account.sessionVersion),
        agent = tokens.issue(account.userId, session.deviceId, session.sessionId, Scope.AGENT, account.sessionVersion),
    )

    companion object {
        fun refreshString(id: SessionId, secret: String) = "rt_$id.$secret"

        /** The session id inside `rt_<sessionId>.<secret>`, or null for anything else. */
        fun sessionIdOf(refreshToken: String): SessionId? =
            refreshToken.takeIf { it.startsWith("rt_") && it.contains('.') }
                ?.removePrefix("rt_")?.substringBefore('.')?.let(SessionId::parse)
    }
}
