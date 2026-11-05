package com.circaso.gateway

import com.circaso.infrastructure.api.RedisScope
import java.security.MessageDigest
import java.time.Duration

/** What Redis holds for one `Idempotency-Key`: a marker while the first request runs, then its response. */
sealed interface IdempotencyState {
    data object InProgress : IdempotencyState

    data class Done(val status: Int, val json: String) : IdempotencyState
}

/**
 * Gateway → Idempotency: the first response to a key is stored for 24 hours and replayed for a repeat. While the
 * first request runs, a marker holds the key so a repeat gets `request.in_progress` instead of running twice.
 * Keys are scoped to the caller and the route. Server errors are not stored, so a retry runs again.
 */
class Idempotency(private val redis: RedisScope) {
    /** True if the caller may run the request; false if the key is already taken (see [state]). */
    suspend fun begin(key: String): Boolean = redis.setIfAbsent(key, MARKER, MARKER_TTL)

    /** Gives back a key [begin] took for a request that was then refused before its route ran. */
    suspend fun abandon(key: String) {
        redis.delete(key)
    }

    suspend fun state(key: String): IdempotencyState? {
        val raw = redis.get(key) ?: return null
        if (raw == MARKER) return IdempotencyState.InProgress
        val status = raw.substringBefore(':').toIntOrNull() ?: return IdempotencyState.InProgress
        return IdempotencyState.Done(status, raw.substringAfter(':'))
    }

    suspend fun finish(
        key: String,
        status: Int,
        json: String,
    ) {
        if (status >= 500) redis.delete(key) else redis.set(key, "$status:$json", STORED_FOR)
    }

    companion object {
        const val HEADER = "Idempotency-Key"
        private const val MARKER = "P"
        private val MARKER_TTL = Duration.ofSeconds(60)
        private val STORED_FOR = Duration.ofHours(24)

        /** `idem:<hash>`: the key never appears in Redis as the client sent it. */
        fun redisKey(
            subject: String,
            method: String,
            path: String,
            clientKey: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$subject|$method|$path|$clientKey".toByteArray())
            return "idem:" + digest.joinToString("") { "%02x".format(it) }
        }

        val mutating = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}
