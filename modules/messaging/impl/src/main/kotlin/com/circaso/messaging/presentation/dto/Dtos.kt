package com.circaso.messaging.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Keys are standard base64. Uploads carry no proof in the body: it travels in `X-Device-Timestamp` and
 * `X-Device-Signature`, over the exact body bytes. The signed prekey's signature is made by the identity key, on
 * the phone.
 */
@Serializable
data class SignedPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)

@Serializable
data class OneTimePreKeyDto(val keyId: Int, val publicKey: String)

/** The signed Kyber (ML-KEM 1024) prekey; its public key is about 1.5 KB. Required: PQXDH does not work without it. */
@Serializable
data class KyberPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)

@Serializable
data class PublishKeysRequest(
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList(),
)

@Serializable
data class AddOneTimeKeysRequest(val oneTimePreKeys: List<OneTimePreKeyDto>)

@Serializable
data class OneTimePreKeysLeftResponse(val oneTimePreKeysLeft: Int)

@Serializable
data class KeyStatusResponse(
    val oneTimePreKeysLeft: Int,
    @Serializable(with = InstantSerializer::class) val signedPreKeyAt: Instant,
)

@Serializable
data class KeyBundleResponse(
    val userId: String,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    /** Null once the person's one-time prekeys are used up; PQXDH works without one. */
    val oneTimePreKey: OneTimePreKeyDto? = null,
)
