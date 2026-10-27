package com.circaso.integrations.impl.circle

import com.circaso.shared.Resource
import io.ktor.http.HttpMethod
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Circle user tokens, kept in memory on this node until [REFRESH_BEFORE] their expiry, so a wallet screen that opens
 * three calls asks Circle for one token. A token lives 60 minutes; its own `exp` claim says exactly when it ends, and
 * 60 minutes from now is assumed if it can't be read. Two calls that miss at once both fetch, which is harmless.
 */
internal class CircleUserTokens(private val client: CircleClient, private val clock: Clock) {
    class UserToken(val userToken: String, val encryptionKey: String, val expiresAt: Instant) {
        override fun toString(): String = "UserToken(***, expiresAt=$expiresAt)"
    }

    private val cache = ConcurrentHashMap<String, UserToken>()

    suspend fun get(circleUserId: String): Resource<UserToken> {
        val now = clock.instant()
        cache[circleUserId]?.takeIf { now.isBefore(it.expiresAt.minus(REFRESH_BEFORE)) }?.let {
            return Resource.success(it)
        }
        val reply =
            client.write<UserTokenBody, UserTokenData>(
                HttpMethod.Post,
                "/v1/w3s/users/token",
                "create_user_token",
                UserTokenBody(circleUserId, UUID.randomUUID().toString()),
            )
        if (reply !is CircleReply.Ok) return CircleErrors.failure(reply, "create_user_token")
        if (reply.data.userToken.isEmpty()) {
            return CircleErrors.failure(CircleReply.Failed(200, null), "create_user_token")
        }

        val token =
            UserToken(
                userToken = reply.data.userToken,
                encryptionKey = reply.data.encryptionKey,
                expiresAt = expiryOf(reply.data.userToken) ?: now.plus(LIFETIME),
            )
        remember(circleUserId, token, now)
        return Resource.success(token)
    }

    /** Circle refused the cached token (revoked or expired early); the next [get] fetches a new one. */
    fun forget(circleUserId: String) {
        cache.remove(circleUserId)
    }

    private fun remember(
        circleUserId: String,
        token: UserToken,
        now: Instant,
    ) {
        if (cache.size >= PRUNE_ABOVE) cache.values.removeIf { !now.isBefore(it.expiresAt) }
        cache[circleUserId] = token
    }

    /** The `exp` claim of a JWT, without checking its signature: Circle issued it, we only want the time. */
    private fun expiryOf(jwt: String): Instant? =
        runCatching {
            val payload = Base64.getUrlDecoder().decode(jwt.split('.')[1]).decodeToString()
            circleJson.decodeFromString<UserTokenClaims>(payload).exp?.let(Instant::ofEpochSecond)
        }.getOrNull()

    companion object {
        val LIFETIME: Duration = Duration.ofMinutes(60)
        val REFRESH_BEFORE: Duration = Duration.ofMinutes(5)

        /** Expired tokens are swept once the map holds this many, so a long-running node doesn't keep them all. */
        private const val PRUNE_ABOVE = 10_000
    }
}
