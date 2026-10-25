package com.circaso.messaging.presentation.mappers

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.AddOneTimeKeysCommand
import com.circaso.messaging.domain.models.FetchedBundle
import com.circaso.messaging.domain.models.KeyStatus
import com.circaso.messaging.domain.models.KeyUploadProof
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.PublishKeysCommand
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.messaging.presentation.dto.AddOneTimeKeysRequest
import com.circaso.messaging.presentation.dto.KeyBundleResponse
import com.circaso.messaging.presentation.dto.KeyStatusResponse
import com.circaso.messaging.presentation.dto.OneTimePreKeyDto
import com.circaso.messaging.presentation.dto.PublishKeysRequest
import com.circaso.messaging.presentation.dto.SignedPreKeyDto
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.web.CallPrincipal
import kotlinx.serialization.DeserializationStrategy
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

// ---- request → command: shape checks live here and answer `request.malformed` ----

/** Generous for a Curve25519 key (33 bytes), an XEdDSA or a DER ECDSA signature; only stops oversized junk. */
private const val MAX_KEY_BYTES = 256

/** At most 100 one-time prekeys per upload (Messaging → M1); the use case checks the total kept. */
private const val MAX_ONE_TIME_PREKEYS = 100

private fun malformed(field: String, why: String = "is not valid") =
    Resource.error(
        SharedError.Malformed(
            mapOf(field to why),
        ),
    )

/** A key upload as it arrived: the exact body bytes and the two proof headers (Messaging → Encryption). */
class SignedUpload(val body: ByteArray, val timestamp: String?, val signature: String?)

/**
 * The proof from the headers: the phone signs `X-Device-Timestamp + "\n" + hex(SHA-256(body))`, over the bytes as
 * received, so nothing is re-encoded and the timestamp is covered. A missing or unreadable header is a failed proof.
 */
private fun SignedUpload.toProof(): KeyUploadProof? {
    val sentAt = timestamp ?: return null
    val instant = runCatching { Instant.parse(sentAt) }.getOrNull() ?: return null
    val signed = signature?.let(::base64) ?: return null
    val payload = sentAt + "\n" + hex(MessageDigest.getInstance("SHA-256").digest(body))
    return KeyUploadProof(payload.toByteArray(), instant, signed)
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private fun <T> SignedUpload.decode(strategy: DeserializationStrategy<T>): T? =
    runCatching { CircasoJson.decodeFromString(strategy, body.decodeToString(throwOnInvalidSequence = true)) }
        .getOrNull()

private fun base64(raw: String): ByteArray? =
    runCatching { Base64.getDecoder().decode(raw) }.getOrNull()?.takeIf { it.size in 1..MAX_KEY_BYTES }

private fun isKey(raw: String) = base64(raw) != null

private fun SignedPreKeyDto.toModel(): SignedPreKey? =
    takeIf { keyId >= 0 && isKey(publicKey) && isKey(signature) }?.let { SignedPreKey(keyId, publicKey, signature) }

/** The keys, or null when any is not a key or two share an id. The count is checked first, with its own code. */
private fun List<OneTimePreKeyDto>.toModels(): List<OneTimePreKey>? {
    if (any { it.keyId < 0 || !isKey(it.publicKey) }) return null
    if (map { it.keyId }.toSet().size != size) return null
    return map { OneTimePreKey(it.keyId, it.publicKey) }
}

private const val ONE_TIME_WHY = "each key needs its own keyId and a base64 key"

/** M1 `PUT /keys`: missing or unreadable proof headers are refused before the body is read. */
fun publishKeysCommand(caller: CallPrincipal, upload: SignedUpload): Resource<PublishKeysCommand> {
    val proof = upload.toProof() ?: return Resource.error(MessagingError.KeysProofInvalid)
    val request = upload.decode(PublishKeysRequest.serializer()) ?: return malformed("body")
    if (request.oneTimePreKeys.size > MAX_ONE_TIME_PREKEYS) return Resource.error(MessagingError.TooManyPrekeys)
    if (!isKey(request.identityKey)) return malformed("identityKey")
    val signedPreKey = request.signedPreKey.toModel() ?: return malformed("signedPreKey")
    val oneTimePreKeys = request.oneTimePreKeys.toModels() ?: return malformed("oneTimePreKeys", ONE_TIME_WHY)
    return Resource.success(
        PublishKeysCommand(
            userId = caller.userId,
            deviceId = caller.deviceId,
            identityKey = request.identityKey,
            signedPreKey = signedPreKey,
            oneTimePreKeys = oneTimePreKeys,
            proof = proof,
        ),
    )
}

/** M1 `POST /keys/one-time`: as `PUT /keys`, the proof headers are read before the body. */
fun addOneTimeKeysCommand(caller: CallPrincipal, upload: SignedUpload): Resource<AddOneTimeKeysCommand> {
    val proof = upload.toProof() ?: return Resource.error(MessagingError.KeysProofInvalid)
    val request = upload.decode(AddOneTimeKeysRequest.serializer()) ?: return malformed("body")
    if (request.oneTimePreKeys.size > MAX_ONE_TIME_PREKEYS) return Resource.error(MessagingError.TooManyPrekeys)
    val keys = request.oneTimePreKeys.toModels() ?: return malformed("oneTimePreKeys", ONE_TIME_WHY)
    if (keys.isEmpty()) return malformed("oneTimePreKeys", "is required")
    return Resource.success(AddOneTimeKeysCommand(caller.userId, caller.deviceId, keys, proof))
}

fun parseUserId(raw: String?): Resource<UserId> =
    raw?.let(UserId::parse)?.let { Resource.success(it) } ?: malformed("userId")

// ---- model → response ----

fun KeyStatus.toResponse() = KeyStatusResponse(oneTimePreKeysLeft, signedPreKeyAt)

fun FetchedBundle.toResponse() =
    KeyBundleResponse(
        userId = userId.toString(),
        identityKey = identityKey,
        signedPreKey = SignedPreKeyDto(signedPreKey.keyId, signedPreKey.publicKey, signedPreKey.signature),
        oneTimePreKey = oneTimePreKey?.let { OneTimePreKeyDto(it.keyId, it.publicKey) },
    )
