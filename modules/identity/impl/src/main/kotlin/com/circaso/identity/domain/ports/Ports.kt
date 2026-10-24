package com.circaso.identity.domain.ports

import com.circaso.identity.api.SessionId
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import java.time.Duration

/** Signs and publishes access tokens. */
interface TokenIssuer {
    fun issue(userId: UserId, deviceId: DeviceId, sessionId: SessionId, scope: Scope, sessionVersion: Int): IssuedToken

    /** The public keys behind `/.well-known/jwks.json`, with a key id each. */
    fun publicKeys(): List<PublicKey>
}

data class IssuedToken(val token: String, val expiresInSeconds: Long)

/** An Ed25519 public key: [x] is the base64url raw key, as a JWK carries it. */
data class PublicKey(val keyId: String, val x: String)

/** Phone numbers as keyed hashes (events) and sealed text (the one place a number must wait in Redis). */
interface PhoneProtection {
    /** HMAC-SHA256 of the E.164 number under the phone-HMAC key shared with users. */
    fun hmac(phone: PhoneNumber): String

    fun seal(phone: PhoneNumber): String
    fun unseal(sealed: String): PhoneNumber?
}

/** Checks a signature made by a phone's hardware-backed key. */
interface DeviceKeyVerifier {
    fun verify(publicKey: String, algorithm: String, payload: ByteArray, signature: ByteArray): Boolean

    /** True if [publicKey] is a key of [algorithm] we can verify with. */
    fun isValid(publicKey: String, algorithm: String): Boolean
}

enum class AttestationMode { LOG, ENFORCE }

/** Play Integrity (Android) and App Attest (iOS). */
interface AttestationVerifier {
    /** True if the token proves a genuine app on a genuine phone. */
    suspend fun verify(platform: String, deviceId: DeviceId, attestation: String?): Boolean
}

/** The short-lived state identity keeps in Redis (`idn:` prefix). */
interface IdentityCache {
    // I1/I2: single-use nonce
    suspend fun saveNonce(nonce: String)
    suspend fun consumeNonce(nonce: String): Boolean

    // I3-I5: pending SMS code
    suspend fun saveOtp(verificationId: String, otp: PendingOtp)
    suspend fun otp(verificationId: String): PendingOtp?

    /** Adds one wrong attempt and returns the count, or null when the code is gone. */
    suspend fun recordWrongAttempt(verificationId: String): Long?
    suspend fun markOtpProven(verificationId: String)
    suspend fun deleteOtp(verificationId: String)

    /** Counts one code request; returns how many have been requested in the window. */
    suspend fun countCodeRequestForPhone(phoneHmac: String): Long
    suspend fun countCodeRequestForDevice(deviceId: DeviceId): Long
    suspend fun codeRequestRetryAfter(phoneHmac: String): Duration?

    suspend fun lockAccountCodes(userId: UserId)
    suspend fun codeLockRemaining(userId: UserId): Duration?

    // phone verified on this device, waiting for the device key (I6)
    suspend fun markPhoneVerified(userId: UserId, deviceId: DeviceId)
    suspend fun isPhoneVerified(userId: UserId, deviceId: DeviceId): Boolean

    // Gateway deny-list for access tokens of a revoked session
    suspend fun revoke(sessionId: SessionId)
    suspend fun isRevoked(sessionId: SessionId): Boolean

    /** The live session of a device, so replacing the phone can revoke it. */
    suspend fun rememberDeviceSession(userId: UserId, deviceId: DeviceId, sessionId: SessionId)
    suspend fun deviceSession(userId: UserId, deviceId: DeviceId): SessionId?

    // Name and photo from the provider, kept until the account activates
    suspend fun saveHints(userId: UserId, displayName: String?, avatarUrl: String?)
    suspend fun hints(userId: UserId): Hints?

    // I15 rate limit and I16 release notice
    suspend fun countRecoveryRequest(phoneHmac: String): Long
    suspend fun savePhoneRelease(phoneHmac: String, release: PhoneRelease)
    suspend fun phoneRelease(phoneHmac: String): PhoneRelease?
    suspend fun countPhoneRelease(phoneHmac: String): Boolean
}

/** What [PendingOtp.codeHash] holds when a vendor (Twilio Verify) makes and checks the code: there is no hash of ours to compare. */
const val VENDOR_CHECKED_CODE = "vendor"

data class PendingOtp(
    val phoneHmac: String,
    val sealedPhone: String,
    val codeHash: String,
    val userId: UserId,
    val deviceId: DeviceId,
    val proven: Boolean = false,
    val attempts: Long = 0,
)

data class Hints(val displayName: String?, val avatarUrl: String?)

data class PhoneRelease(val ownerUserId: UserId, val requesterUserId: UserId, val releaseAtEpochMillis: Long)

/** What a verified access token says. */
data class AccessClaims(
    val userId: UserId,
    val deviceId: DeviceId,
    val sessionId: SessionId,
    val scope: Scope,
    val sessionVersion: Int,
)

/** Checks an access token's signature (current and previous keys) and expiry. Null for anything that fails. */
interface AccessTokenVerifier {
    fun verify(token: String): AccessClaims?
}
