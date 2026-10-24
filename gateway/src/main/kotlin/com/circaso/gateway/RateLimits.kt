package com.circaso.gateway

import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.DomainError
import com.circaso.shared.SharedError
import java.time.Duration

/** Who a limit counts: the caller's IP, or the signed-in user. */
enum class LimitBy { IP, USER }

class Limit(
    val name: String,
    val max: Long,
    val window: Duration,
    val by: LimitBy,
)

/**
 * The gateway's share of Gateway → Rate limits. Limits that depend on the body (SMS per number, code attempts,
 * phone release, handle lookups) are enforced by the module that owns that data, with its own Redis keys.
 * Counted in fixed windows; the first request of a window starts it.
 */
object RateLimitTable {
    private val minute = Duration.ofMinutes(1)

    /** The limit for a request, or null when it has none (webhooks, which carry their own guards). */
    fun limitFor(
        method: String,
        path: String,
        signedIn: Boolean,
    ): Limit? =
        when {
            path.startsWith("/v1/hooks/") -> {
                null
            }

            path.startsWith("/v1/agent/") -> {
                Limit("agent", 60, minute, LimitBy.USER)
            }

            method == "POST" && (path == "/v1/auth/nonce" || path == "/v1/auth/social") -> {
                Limit(
                    "social",
                    20,
                    minute,
                    LimitBy.IP,
                )
            }

            method == "POST" && path == "/v1/recovery/requests" -> {
                Limit("recovery", 5, Duration.ofDays(1), LimitBy.IP)
            }

            method == "POST" && path == "/v1/contacts/sync" -> {
                Limit("sync", 5, Duration.ofHours(1), LimitBy.USER)
            }

            signedIn -> {
                Limit("user", 120, minute, LimitBy.USER)
            }

            else -> {
                Limit("ip", 120, minute, LimitBy.IP)
            }
        }
}

class RateLimiter(private val redis: RedisScope) {
    /** Counts the request; an error when [limit] is used up, carrying how long to wait. */
    suspend fun check(limit: Limit, subject: String): DomainError? {
        val key = "rl:${limit.name}:$subject"
        val count = redis.increment(key, limit.window)
        if (count <= limit.max) return null
        val wait = redis.ttl(key)?.let { (it.toMillis() + 999) / 1000 }?.coerceAtLeast(1) ?: limit.window.seconds
        return SharedError.RateLimited(wait)
    }
}
