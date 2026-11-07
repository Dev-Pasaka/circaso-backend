package com.circaso.share.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.share.domain.ports.ViewThrottle
import java.security.MessageDigest
import java.time.Duration

/**
 * `shr:views:{hash of the address}`, a fixed window counter. The address is hashed so the key never holds it; the
 * window's first view starts it.
 */
class RedisViewThrottle(private val redis: RedisScope) : ViewThrottle {
    override suspend fun take(
        address: String,
        max: Long,
        window: Duration,
    ): Long? {
        val key = "views:${digest(address)}"
        val count = redis.increment(key, window)
        if (count <= max) return null
        return redis.ttl(key)?.let { (it.toMillis() + MILLIS_IN_SECOND - 1) / MILLIS_IN_SECOND }?.coerceAtLeast(1)
            ?: window.seconds
    }

    private fun digest(address: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(address.toByteArray())
            .take(DIGEST_BYTES)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MILLIS_IN_SECOND = 1_000
        const val DIGEST_BYTES = 12
    }
}
