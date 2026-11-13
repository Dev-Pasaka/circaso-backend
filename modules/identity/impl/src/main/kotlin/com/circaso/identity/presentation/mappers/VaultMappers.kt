package com.circaso.identity.presentation.mappers

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.errors.VaultError
import com.circaso.identity.domain.models.PinVault
import com.circaso.identity.domain.models.SaveVaultCommand
import com.circaso.identity.presentation.dto.SaveVaultRequest
import com.circaso.identity.presentation.dto.VaultResponse
import com.circaso.identity.presentation.dto.VaultStatusResponse
import com.circaso.shared.Resource
import java.util.Base64

private const val MAX_CREDENTIAL_ID_CHARS = 1024
private const val MIN_SALT_BYTES = 16
private const val MAX_SALT_BYTES = 64
private const val MAX_CIPHERTEXT_BYTES = 8 * 1024

/** Base64 text of [MAX_CIPHERTEXT_BYTES] bytes, padded: anything longer is refused before it is decoded. */
private const val MAX_CIPHERTEXT_CHARS = (MAX_CIPHERTEXT_BYTES + 2) / 3 * 4

/** Names like `prf-hkdf-sha256+aes-256-gcm/v1`: letters, digits and `.`, `_`, `+`, `/`, `-`, at most 64. */
private val ALGORITHM = Regex("[A-Za-z0-9._+/-]{1,64}")

/** A WebAuthn credential id in base64url, with or without its padding. */
private val BASE64_URL = Regex("[A-Za-z0-9_-]+={0,2}")

/**
 * V3's shape checks (the server can't look inside the blob, only at its size and encoding): each field that is wrong
 * answers its own `vault.*_invalid` code, so the app can tell a bad passkey from a bad blob.
 */
fun SaveVaultRequest.toCommand(principal: Principal): Resource<SaveVaultCommand> {
    val problem =
        when {
            !isCredentialId(credentialId) -> VaultError.CredentialIdInvalid
            decodedSize(salt, MAX_SALT_BYTES * 2) !in MIN_SALT_BYTES..MAX_SALT_BYTES -> VaultError.SaltInvalid
            decodedSize(ciphertext, MAX_CIPHERTEXT_CHARS) !in 1..MAX_CIPHERTEXT_BYTES -> VaultError.CiphertextInvalid
            !ALGORITHM.matches(algorithm) -> VaultError.AlgorithmInvalid
            expectedVersion != null && expectedVersion < 1 -> VaultError.ExpectedVersionInvalid
            else -> null
        }
    if (problem != null) return Resource.error(problem)
    return Resource.success(SaveVaultCommand(principal, credentialId, salt, ciphertext, algorithm, expectedVersion))
}

private fun isCredentialId(raw: String): Boolean =
    raw.length <= MAX_CREDENTIAL_ID_CHARS &&
        BASE64_URL.matches(raw) &&
        runCatching { Base64.getUrlDecoder().decode(raw) }.getOrNull()?.isNotEmpty() == true

/** The decoded length of standard base64 [raw], or -1 when it isn't base64 or is longer than [maxChars]. */
private fun decodedSize(raw: String, maxChars: Int): Int {
    if (raw.length > maxChars) return -1
    return runCatching { Base64.getDecoder().decode(raw).size }.getOrDefault(-1)
}

fun PinVault.toResponse() = VaultResponse(credentialId, salt, ciphertext, algorithm, version, updatedAt)

fun PinVault?.toStatusResponse() =
    if (this == null) {
        VaultStatusResponse(exists = false)
    } else {
        VaultStatusResponse(exists = true, credentialId = credentialId, version = version, updatedAt = updatedAt)
    }
