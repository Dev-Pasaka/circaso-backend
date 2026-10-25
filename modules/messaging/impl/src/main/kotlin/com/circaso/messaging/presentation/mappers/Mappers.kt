package com.circaso.messaging.presentation.mappers

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
import com.circaso.messaging.presentation.dto.KeyUploadProofDto
import com.circaso.messaging.presentation.dto.OneTimePreKeyDto
import com.circaso.messaging.presentation.dto.PublishKeysRequest
import com.circaso.messaging.presentation.dto.SignedPreKeyDto
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.web.CallPrincipal
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.Base64

// ---- request → command: shape checks live here and answer `request.malformed` ----

/** The field the device signature travels in; the signed digest covers everything else in the body. */
private const val PROOF_FIELD = "deviceProof"

/** Generous for a Curve25519 key (33 bytes) or an XEdDSA signature (64 bytes); only stops oversized junk. */
private const val MAX_KEY_BYTES = 256

/** At most 100 one-time prekeys per upload (Messaging → M1); the use case checks the total kept. */
private const val MAX_ONE_TIME_PREKEYS = 100

private fun malformed(field: String, why: String = "is not valid") =
    Resource.error(
        SharedError.Malformed(
            mapOf(field to why),
        ),
    )

/** A request body with the digest the phone signed: the SHA-256 of the body without [PROOF_FIELD]. */
private class SignedBody(val json: JsonObject, val digest: ByteArray)

/**
 * The bytes signed are the body without `deviceProof`, written as compact JSON with its fields in the order they
 * arrived (kotlinx-serialization's encoding of the parsed object). The phone builds that JSON, signs its SHA-256,
 * then adds `deviceProof`.
 */
// TODO(decision: the spec says "the SHA-256 of the body without this field" but not how that body is written
//  down; compact JSON in arrival order is assumed here and documented in docs/mobile-integration.md section 5)
private fun signedBody(text: String): SignedBody? {
    val json = runCatching { CircasoJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val unsigned = JsonObject(json - PROOF_FIELD)
    val canonical = CircasoJson.encodeToString(JsonObject.serializer(), unsigned)
    return SignedBody(json, MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
}

private fun <T> SignedBody.decode(strategy: DeserializationStrategy<T>): T? =
    runCatching { CircasoJson.decodeFromJsonElement(strategy, json) }.getOrNull()

private fun base64(raw: String): ByteArray? =
    runCatching { Base64.getDecoder().decode(raw) }.getOrNull()?.takeIf { it.size in 1..MAX_KEY_BYTES }

private fun isKey(raw: String) = base64(raw) != null

private fun KeyUploadProofDto.toProof(digest: ByteArray): KeyUploadProof? =
    base64(signature)?.let { KeyUploadProof(digest, timestamp, it) }

private fun SignedPreKeyDto.toModel(): SignedPreKey? =
    takeIf { keyId >= 0 && isKey(publicKey) && isKey(signature) }?.let { SignedPreKey(keyId, publicKey, signature) }

/** The keys, or null when any is not a key, there are too many, or two share an id. */
private fun List<OneTimePreKeyDto>.toModels(): List<OneTimePreKey>? {
    if (size > MAX_ONE_TIME_PREKEYS) return null
    if (any { it.keyId < 0 || !isKey(it.publicKey) }) return null
    if (map { it.keyId }.toSet().size != size) return null
    return map { OneTimePreKey(it.keyId, it.publicKey) }
}

private const val ONE_TIME_WHY = "up to $MAX_ONE_TIME_PREKEYS keys, each with its own keyId and a base64 key"

/** M1 `PUT /keys` from the raw body, which the signature is checked against. */
fun publishKeysCommand(caller: CallPrincipal, body: String): Resource<PublishKeysCommand> {
    val signed = signedBody(body) ?: return malformed("body", "is not a JSON object")
    val request = signed.decode(PublishKeysRequest.serializer()) ?: return malformed("body")
    if (!isKey(request.identityKey)) return malformed("identityKey")
    val signedPreKey = request.signedPreKey.toModel() ?: return malformed("signedPreKey")
    val oneTimePreKeys = request.oneTimePreKeys.toModels() ?: return malformed("oneTimePreKeys", ONE_TIME_WHY)
    val proof = request.deviceProof.toProof(signed.digest) ?: return malformed("deviceProof.signature")
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

/** M1 `POST /keys/one-time` from the raw body. */
fun addOneTimeKeysCommand(caller: CallPrincipal, body: String): Resource<AddOneTimeKeysCommand> {
    val signed = signedBody(body) ?: return malformed("body", "is not a JSON object")
    val request = signed.decode(AddOneTimeKeysRequest.serializer()) ?: return malformed("body")
    val keys = request.oneTimePreKeys.toModels() ?: return malformed("oneTimePreKeys", ONE_TIME_WHY)
    if (keys.isEmpty()) return malformed("oneTimePreKeys", "is required")
    val proof = request.deviceProof.toProof(signed.digest) ?: return malformed("deviceProof.signature")
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
