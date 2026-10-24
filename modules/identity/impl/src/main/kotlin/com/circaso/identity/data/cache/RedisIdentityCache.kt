package com.circaso.identity.data.cache

import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.ports.Hints
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PendingOtp
import com.circaso.identity.domain.ports.PhoneRelease
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.DeviceId
import com.circaso.shared.UserId
import java.time.Duration

/** Identity's Redis keys (`idn:` prefix, set by the scope); every key has an expiry. */
class RedisIdentityCache(private val redis: RedisScope) : IdentityCache {
    override suspend fun saveNonce(nonce: String) = redis.set("nonce:$nonce", "1", NONCE_TTL)

    override suspend fun consumeNonce(nonce: String): Boolean = redis.getAndDelete("nonce:$nonce") != null

    override suspend fun saveOtp(verificationId: String, otp: PendingOtp) =
        redis.hashSet(
            "otp:$verificationId",
            mapOf(
                "phoneHmac" to otp.phoneHmac,
                "sealedPhone" to otp.sealedPhone,
                "codeHash" to otp.codeHash,
                "userId" to otp.userId.toString(),
                "deviceId" to otp.deviceId.toString(),
                "proven" to if (otp.proven) "1" else "0",
                "attempts" to otp.attempts.toString(),
            ),
            OTP_TTL,
        )

    override suspend fun otp(verificationId: String): PendingOtp? {
        val h = redis.hashGetAll("otp:$verificationId")
        if (h.isEmpty()) return null
        return PendingOtp(
            phoneHmac = h["phoneHmac"] ?: return null,
            sealedPhone = h["sealedPhone"] ?: return null,
            codeHash = h["codeHash"] ?: return null,
            userId = UserId.parse(h["userId"] ?: return null) ?: return null,
            deviceId = DeviceId.parse(h["deviceId"] ?: return null) ?: return null,
            proven = h["proven"] == "1",
            attempts = h["attempts"]?.toLongOrNull() ?: 0,
        )
    }

    override suspend fun recordWrongAttempt(verificationId: String): Long? =
        redis.hashIncrement(
            "otp:$verificationId",
            "attempts",
        )

    override suspend fun markOtpProven(verificationId: String) {
        val left = redis.ttl("otp:$verificationId") ?: return
        redis.hashSet("otp:$verificationId", mapOf("proven" to "1"), left)
    }

    override suspend fun deleteOtp(verificationId: String) {
        redis.delete("otp:$verificationId")
    }

    override suspend fun countCodeRequestForPhone(phoneHmac: String): Long =
        redis.increment(
            "otp-rate:phone:$phoneHmac",
            PHONE_RATE_TTL,
        )

    override suspend fun countCodeRequestForDevice(deviceId: DeviceId): Long =
        redis.increment(
            "otp-rate:device:$deviceId",
            DEVICE_RATE_TTL,
        )

    override suspend fun codeRequestRetryAfter(phoneHmac: String): Duration? = redis.ttl("otp-rate:phone:$phoneHmac")

    override suspend fun lockAccountCodes(userId: UserId) = redis.set("otp-lock:$userId", "1", LOCK_TTL)

    override suspend fun codeLockRemaining(userId: UserId): Duration? = redis.ttl("otp-lock:$userId")

    override suspend fun markPhoneVerified(userId: UserId, deviceId: DeviceId) =
        redis.set(
            "phone-ok:$userId:$deviceId",
            "1",
            PHONE_OK_TTL,
        )

    override suspend fun isPhoneVerified(userId: UserId, deviceId: DeviceId): Boolean =
        redis.exists(
            "phone-ok:$userId:$deviceId",
        )

    override suspend fun revoke(sessionId: SessionId) = redis.set("revoked:$sessionId", "1", REVOKED_TTL)

    override suspend fun isRevoked(sessionId: SessionId): Boolean = redis.exists("revoked:$sessionId")

    override suspend fun rememberDeviceSession(
        userId: UserId,
        deviceId: DeviceId,
        sessionId: SessionId,
    ) = redis.set("dev-session:$userId:$deviceId", sessionId.toString(), SESSION_TTL)

    override suspend fun deviceSession(userId: UserId, deviceId: DeviceId): SessionId? =
        redis.get("dev-session:$userId:$deviceId")?.let(SessionId::parse)

    override suspend fun saveHints(
        userId: UserId,
        displayName: String?,
        avatarUrl: String?,
    ) = redis.hashSet(
        "hints:$userId",
        mapOf("displayName" to displayName.orEmpty(), "avatarUrl" to avatarUrl.orEmpty()),
        HINTS_TTL,
    )

    override suspend fun hints(userId: UserId): Hints? {
        val h = redis.hashGetAll("hints:$userId")
        if (h.isEmpty()) return null
        return Hints(h["displayName"]?.takeIf { it.isNotEmpty() }, h["avatarUrl"]?.takeIf { it.isNotEmpty() })
    }

    override suspend fun countRecoveryRequest(phoneHmac: String): Long =
        redis.increment(
            "recovery-rate:$phoneHmac",
            Duration.ofDays(1),
        )

    override suspend fun savePhoneRelease(phoneHmac: String, release: PhoneRelease) =
        redis.hashSet(
            "phone-release:$phoneHmac",
            mapOf(
                "owner" to release.ownerUserId.toString(),
                "requester" to release.requesterUserId.toString(),
                "at" to release.releaseAtEpochMillis.toString(),
            ),
            RELEASE_TTL,
        )

    override suspend fun phoneRelease(phoneHmac: String): PhoneRelease? {
        val h = redis.hashGetAll("phone-release:$phoneHmac")
        if (h.isEmpty()) return null
        return PhoneRelease(
            UserId.parse(h["owner"] ?: return null) ?: return null,
            UserId.parse(h["requester"] ?: return null) ?: return null,
            h["at"]?.toLongOrNull() ?: return null,
        )
    }

    override suspend fun countPhoneRelease(phoneHmac: String): Boolean =
        redis.setIfAbsent(
            "phone-release-rate:$phoneHmac",
            "1",
            RELEASE_RATE_TTL,
        )

    private companion object {
        val NONCE_TTL: Duration = Duration.ofMinutes(5)
        val OTP_TTL: Duration = Duration.ofMinutes(5)
        val PHONE_RATE_TTL: Duration = Duration.ofMinutes(10)
        val DEVICE_RATE_TTL: Duration = Duration.ofHours(24)
        val LOCK_TTL: Duration = Duration.ofMinutes(10)
        val PHONE_OK_TTL: Duration = Duration.ofMinutes(30)
        val REVOKED_TTL: Duration = Duration.ofMinutes(15)
        val SESSION_TTL: Duration = Duration.ofDays(30)
        val HINTS_TTL: Duration = Duration.ofDays(30)
        val RELEASE_TTL: Duration = Duration.ofDays(14)
        val RELEASE_RATE_TTL: Duration = Duration.ofDays(7)
    }
}
