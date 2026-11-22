package com.circaso.ramp.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.CodeCheck
import com.circaso.ramp.domain.ports.PinAttempts
import com.circaso.ramp.domain.ports.RampCounters
import com.circaso.ramp.domain.ports.SignInCodes
import com.circaso.ramp.domain.support.MerchantSignIn
import com.circaso.ramp.domain.support.PinGuard
import com.circaso.shared.Network
import com.circaso.shared.UserId
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate

/**
 * The ramp's counters under `rmp:`: `prompts:{userId}` (M-Pesa prompts this hour, so one user can't burn a merchant's
 * account at the provider) and `daily:{network}:{userId}:{date}` (shillings traded that UTC day, reserved with the
 * order and given back when it ends unpaid).
 */
class RedisCounters(private val redis: RedisScope) : RampCounters {
    override suspend fun countPrompt(userId: UserId): Long = redis.increment("prompts:$userId", PROMPT_WINDOW)

    override suspend fun reserveDaily(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
        limitMinor: Long,
    ): Boolean {
        val total = redis.incrementBy(daily(userId, network, day), fiatMinor, DAY_TTL)
        if (total <= limitMinor) return true
        redis.incrementBy(daily(userId, network, day), -fiatMinor, DAY_TTL)
        return false
    }

    override suspend fun giveBackDaily(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
    ) {
        redis.incrementBy(daily(userId, network, day), -fiatMinor, DAY_TTL)
    }

    private fun daily(
        userId: UserId,
        network: Network,
        day: LocalDate,
    ) = "daily:${network.name.lowercase()}:$userId:$day"

    private companion object {
        val PROMPT_WINDOW: Duration = Duration.ofHours(1)
        val DAY_TTL: Duration = Duration.ofDays(2)
    }
}

/** `rmp:pin:{merchantId}`: wrong PINs in the last thirty minutes. Never the PIN. */
class RedisPinAttempts(private val redis: RedisScope) : PinAttempts {
    override suspend fun lockedFor(merchantId: MerchantId): Duration? {
        val tries = redis.get(key(merchantId))?.toLongOrNull() ?: return null
        if (tries < PinGuard.MAX_TRIES) return null
        return redis.ttl(key(merchantId)) ?: LOCK
    }

    override suspend fun failed(merchantId: MerchantId): Long = redis.increment(key(merchantId), LOCK)

    override suspend fun clear(merchantId: MerchantId) {
        redis.delete(key(merchantId))
    }

    private fun key(merchantId: MerchantId) = "pin:$merchantId"

    private companion object {
        val LOCK: Duration = Duration.ofSeconds(PinGuard.LOCK_SECONDS)
    }
}

/**
 * Portal sign-in codes under `rmp:`, all by a digest of the email, never the address: `code:{email}` (the digest of
 * the code, ten minutes), `code-tries:{email}` (five), `code-wait:{email}` (one request a minute), `code-sent:{email}`
 * (three in ten minutes) and `code-from:{address}` (ten an hour from one client address).
 */
class RedisSignInCodes(private val redis: RedisScope) : SignInCodes {
    override suspend fun admit(emailKey: String, address: String): Duration? {
        if (redis.increment("code-from:$address", ADDRESS_WINDOW) > PER_ADDRESS) {
            return redis.ttl("code-from:$address") ?: ADDRESS_WINDOW
        }
        if (!redis.setIfAbsent("code-wait:$emailKey", "1", RESEND)) return redis.ttl("code-wait:$emailKey") ?: RESEND
        if (redis.increment("code-sent:$emailKey", CODE_LIFETIME) > PER_EMAIL) {
            return redis.ttl("code-sent:$emailKey") ?: CODE_LIFETIME
        }
        return null
    }

    override suspend fun keep(emailKey: String, codeDigest: String) {
        redis.set("code:$emailKey", codeDigest, CODE_LIFETIME)
        redis.delete("code-tries:$emailKey")
    }

    override suspend fun check(emailKey: String, codeDigest: String): CodeCheck {
        val kept = redis.get("code:$emailKey") ?: return CodeCheck.WRONG
        if (redis.increment("code-tries:$emailKey", CODE_LIFETIME) > MAX_TRIES) {
            redis.delete("code:$emailKey")
            return CodeCheck.LOCKED
        }
        if (!MessageDigest.isEqual(kept.toByteArray(), codeDigest.toByteArray())) return CodeCheck.WRONG
        // Taking the code is what uses it up: of two requests with the right code, one signs in.
        return if (redis.getAndDelete("code:$emailKey") != null) CodeCheck.MATCH else CodeCheck.WRONG
    }

    private companion object {
        val CODE_LIFETIME: Duration = Duration.ofMinutes(MerchantSignIn.CODE_MINUTES.toLong())
        val RESEND: Duration = Duration.ofSeconds(MerchantSignIn.RESEND_SECONDS)
        val ADDRESS_WINDOW: Duration = Duration.ofHours(1)
        const val PER_EMAIL = 3
        const val PER_ADDRESS = 10
        const val MAX_TRIES = 5
    }
}
