package com.circaso.integrations.impl.circle

import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The public keys Circle signs notifications with, by key id. A key id always names the same key, so each is fetched
 * once (`GET /v2/notifications/publicKey/{keyId}`, base64 DER SubjectPublicKeyInfo) and kept for good.
 *
 * Anyone can post to `/v1/hooks/circle` with a made-up key id, so an id Circle refused is remembered as unknown for
 * [UNKNOWN_FOR] (at most [UNKNOWN_LIMIT] of them) instead of asking Circle again on every post. An id that couldn't be
 * checked because Circle was unavailable is not remembered: its next delivery asks again. On top of that, a node
 * fetches at most [fetchesPerMinute] keys a minute: unauthenticated traffic must not spend our Circle rate limit, and a
 * delivery over the cap is unverifiable for now (a 4xx, which Circle retries later).
 */
internal class CircleNotificationKeys(
    private val client: CircleClient,
    private val clock: Clock,
    private val fetchesPerMinute: Int = FETCHES_PER_MINUTE,
) {
    private val log = LoggerFactory.getLogger(CircleNotificationKeys::class.java)
    private val keys = ConcurrentHashMap<String, PublicKey>()
    private val unknownUntil = ConcurrentHashMap<String, Instant>()
    private val recentFetches = ArrayDeque<Instant>()

    /** The key for [keyId], or null if Circle doesn't know it or can't be asked right now. */
    suspend fun get(keyId: String): PublicKey? {
        keys[keyId]?.let { return it }
        if (!KEY_ID.matches(keyId) || isKnownUnknown(keyId)) return null
        if (!mayFetch()) {
            log.warn("circle notification key not fetched: over {} fetches a minute", fetchesPerMinute)
            return null
        }

        val reply = client.get<NotificationKeyData>("/v2/notifications/publicKey/$keyId", "get_notification_key")
        if (reply !is CircleReply.Ok) {
            log.warn("circle notification key not fetched: status={}", (reply as? CircleReply.Failed)?.status)
            if (reply.isRefusal()) rememberUnknown(keyId)
            return null
        }
        val key = parse(reply.data.publicKey)
        if (key == null) {
            rememberUnknown(keyId)
            return null
        }
        keys[keyId] = key
        return key
    }

    /** Circle looked at the id and said no. A 401 or 403 is our key being wrong, not the id. */
    private fun CircleReply<*>.isRefusal(): Boolean = this is CircleReply.Failed && status in REFUSALS

    /** A sliding minute: the fetches made in the last minute, oldest first. */
    private fun mayFetch(): Boolean =
        synchronized(recentFetches) {
            val now = clock.instant()
            val windowStart = now.minus(FETCH_WINDOW)
            while (recentFetches.isNotEmpty() && !recentFetches.first().isAfter(windowStart)) {
                recentFetches.removeFirst()
            }
            if (recentFetches.size >= fetchesPerMinute) return false
            recentFetches.addLast(now)
            true
        }

    private fun isKnownUnknown(keyId: String): Boolean {
        val until = unknownUntil[keyId] ?: return false
        if (clock.instant().isBefore(until)) return true
        unknownUntil.remove(keyId, until)
        return false
    }

    private fun rememberUnknown(keyId: String) {
        val now = clock.instant()
        if (unknownUntil.size >= UNKNOWN_LIMIT) unknownUntil.values.removeIf { !now.isBefore(it) }
        if (unknownUntil.size >= UNKNOWN_LIMIT) unknownUntil.clear()
        unknownUntil[keyId] = now.plus(UNKNOWN_FOR)
    }

    private fun parse(base64: String): PublicKey? =
        runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))
        }.onFailure { log.warn("circle notification key unreadable: {}", it::class.simpleName) }.getOrNull()

    companion object {
        /** Key ids are UUIDs; anything else never reaches Circle's URL. */
        private val KEY_ID = Regex("^[0-9a-fA-F-]{36}$")

        val UNKNOWN_FOR: Duration = Duration.ofMinutes(5)

        /** When full, expired entries go first, then all of them: a flood of junk ids can't grow the map. */
        const val UNKNOWN_LIMIT = 1_000

        const val FETCHES_PER_MINUTE = 10
        private val FETCH_WINDOW: Duration = Duration.ofMinutes(1)
        private val REFUSALS = setOf(400, 404, 422)
    }
}
