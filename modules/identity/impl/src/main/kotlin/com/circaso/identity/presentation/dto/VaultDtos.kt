package com.circaso.identity.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * V3: the PIN Vault as the phone sealed it. [credentialId] is the passkey's WebAuthn credential id (base64url), [salt]
 * and [ciphertext] are standard base64, [algorithm] names the scheme (`prf-hkdf-sha256+aes-256-gcm/v1`).
 * [expectedVersion], when sent, must be the stored version, or the save is refused (`vault.version_conflict`).
 */
@Serializable
data class SaveVaultRequest(
    val credentialId: String,
    val salt: String,
    val ciphertext: String,
    val algorithm: String,
    val expectedVersion: Int? = null,
)

/** V2: the blob to decrypt, exactly as it was saved. */
@Serializable
data class VaultResponse(
    val credentialId: String,
    val salt: String,
    val ciphertext: String,
    val algorithm: String,
    val version: Int,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
)

/** V1 and V3: whether there is a vault and which passkey and version, never the blob. */
@Serializable
data class VaultStatusResponse(
    val exists: Boolean,
    val credentialId: String? = null,
    val version: Int? = null,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant? = null,
)
