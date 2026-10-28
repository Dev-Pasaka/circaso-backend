package com.circaso.messaging.domain.models

import com.circaso.shared.DeviceId
import com.circaso.shared.UserId
import java.time.Instant

/** A Curve25519 prekey signed by the identity key; the phone replaces it every 7 days. Keys are base64 text. */
data class SignedPreKey(val keyId: Int, val publicKey: String, val signature: String)

data class OneTimePreKey(val keyId: Int, val publicKey: String)

/**
 * A Kyber (ML-KEM 1024) prekey signed by the identity key, replaced together with the signed prekey. PQXDH needs it:
 * libsignal 0.106 refuses a bundle without one (decision 2026-10-28). Kept as the "last resort" key, never used up.
 */
data class KyberPreKey(val keyId: Int, val publicKey: String, val signature: String)

/** One account's published key set: one phone per account, so one bundle. */
data class KeyBundle(
    val userId: UserId,
    val deviceId: DeviceId,
    val identityKey: String,
    val signedPreKey: SignedPreKey,
    val kyberPreKey: KyberPreKey,
    val signedPreKeyAt: Instant,
)

/**
 * The phone's device-key signature over an upload (Messaging → Encryption): [signedPayload] is the UTF-8 of
 * `X-Device-Timestamp + "\n" + hex(SHA-256(body bytes))`, built by the presentation mapper from the bytes that
 * arrived; [timestamp] is that header parsed.
 */
class KeyUploadProof(val signedPayload: ByteArray, val timestamp: Instant, val signature: ByteArray)

/** M1 `PUT /keys`: the whole key set, replacing the one before. */
data class PublishKeysCommand(
    val userId: UserId,
    val deviceId: DeviceId,
    val identityKey: String,
    val signedPreKey: SignedPreKey,
    val kyberPreKey: KyberPreKey,
    val oneTimePreKeys: List<OneTimePreKey>,
    val proof: KeyUploadProof,
)

/** M1 `POST /keys/one-time`: more one-time prekeys for the bundle already published. */
data class AddOneTimeKeysCommand(
    val userId: UserId,
    val deviceId: DeviceId,
    val oneTimePreKeys: List<OneTimePreKey>,
    val proof: KeyUploadProof,
)

/** M2: what the phone needs to decide to top up (fewer than 20 left) or rotate (older than 7 days). */
data class KeyStatus(val oneTimePreKeysLeft: Int, val signedPreKeyAt: Instant)

/** M3: what a sender needs for PQXDH. [oneTimePreKey] is null once they are used up, which PQXDH allows. */
data class FetchedBundle(
    val userId: UserId,
    val identityKey: String,
    val signedPreKey: SignedPreKey,
    val kyberPreKey: KyberPreKey,
    val oneTimePreKey: OneTimePreKey?,
)
