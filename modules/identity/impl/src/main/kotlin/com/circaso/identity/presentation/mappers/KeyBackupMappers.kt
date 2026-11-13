package com.circaso.identity.presentation.mappers

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.errors.KeyBackupError
import com.circaso.identity.domain.models.GroupKeyPage
import com.circaso.identity.domain.models.KeyBackupRoot
import com.circaso.identity.domain.models.SaveGroupKeyCommand
import com.circaso.identity.domain.models.SaveKeyBackupRootCommand
import com.circaso.identity.domain.models.SaveSavedNamesCommand
import com.circaso.identity.domain.models.SavedNames
import com.circaso.identity.presentation.dto.GroupKeyDto
import com.circaso.identity.presentation.dto.GroupKeysResponse
import com.circaso.identity.presentation.dto.KeyBackupRootResponse
import com.circaso.identity.presentation.dto.SaveGroupKeyRequest
import com.circaso.identity.presentation.dto.SaveKeyBackupRootRequest
import com.circaso.identity.presentation.dto.SaveSavedNamesRequest
import com.circaso.identity.presentation.dto.SavedNamesResponse
import com.circaso.identity.presentation.dto.SavedNamesVersionResponse
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import java.util.Base64

private const val MAX_CREDENTIAL_ID_CHARS = 1024
private const val MIN_SALT_BYTES = 16
private const val MAX_SALT_BYTES = 64
private const val MAX_SEALED_ROOT_BYTES = 1024
private const val MAX_GROUP_KEY_BYTES = 512
private const val MAX_SAVED_NAMES_BYTES = 512 * 1024

/** Base64 text of [bytes] bytes, padded: anything longer is refused before it is decoded. */
private fun base64Chars(bytes: Int) = (bytes + 2) / 3 * 4

/** Names like `prf-hkdf-sha256+aes-256-gcm/v1`: letters, digits and `.`, `_`, `+`, `/`, `-`, at most 64. */
private val ALGORITHM = Regex("[A-Za-z0-9._+/-]{1,64}")

/** A WebAuthn credential id in base64url, with or without its padding. */
private val BASE64_URL = Regex("[A-Za-z0-9_-]+={0,2}")

/**
 * KB2's shape checks (the server can't look inside the sealed root, only at its size and encoding): each field that is
 * wrong answers its own `key_backup.*_invalid` code.
 */
fun SaveKeyBackupRootRequest.toCommand(principal: Principal): Resource<SaveKeyBackupRootCommand> {
    val problem =
        when {
            !isCredentialId(credentialId) -> {
                KeyBackupError.CredentialIdInvalid
            }

            decodedSize(salt, MAX_SALT_BYTES * 2) !in MIN_SALT_BYTES..MAX_SALT_BYTES -> {
                KeyBackupError.SaltInvalid
            }

            decodedSize(sealedRoot, base64Chars(MAX_SEALED_ROOT_BYTES)) !in 1..MAX_SEALED_ROOT_BYTES -> {
                KeyBackupError.SealedRootInvalid
            }

            !ALGORITHM.matches(algorithm) -> {
                KeyBackupError.AlgorithmInvalid
            }

            expectedVersion != null && expectedVersion < 1 -> {
                KeyBackupError.ExpectedVersionInvalid
            }

            else -> {
                null
            }
        }
    if (problem != null) return Resource.error(problem)
    return Resource.success(
        SaveKeyBackupRootCommand(principal, credentialId, salt, sealedRoot, algorithm, expectedVersion),
    )
}

/** KB4's checks: the path's chat id (canonical UUID) and `metaVersion` (an int, 0 or more), and the blob's size. */
fun SaveGroupKeyRequest.toCommand(
    principal: Principal,
    chatId: String?,
    metaVersion: String?,
): Resource<SaveGroupKeyCommand> {
    val chat = chatId?.let(Uuid7::parse) ?: return Resource.error(KeyBackupError.ChatIdInvalid)
    val version =
        metaVersion?.toIntOrNull()?.takeIf { it >= 0 } ?: return Resource.error(KeyBackupError.MetaVersionInvalid)
    if (decodedSize(ciphertext, base64Chars(MAX_GROUP_KEY_BYTES)) !in 1..MAX_GROUP_KEY_BYTES) {
        return Resource.error(KeyBackupError.CiphertextInvalid)
    }
    return Resource.success(SaveGroupKeyCommand(principal, chat, version, ciphertext))
}

/** KB7's checks: the blob is base64 of 1 byte to 512 KiB, and `baseVersion` is 0 or more. */
fun SaveSavedNamesRequest.toCommand(principal: Principal): Resource<SaveSavedNamesCommand> {
    if (decodedSize(ciphertext, base64Chars(MAX_SAVED_NAMES_BYTES)) !in 1..MAX_SAVED_NAMES_BYTES) {
        return Resource.error(IdentityError.SavedNamesCiphertextInvalid)
    }
    if (baseVersion < 0) return Resource.error(IdentityError.SavedNamesBaseVersionInvalid)
    return Resource.success(SaveSavedNamesCommand(principal, ciphertext, baseVersion))
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

fun KeyBackupRoot?.toResponse() =
    if (this == null) {
        KeyBackupRootResponse(exists = false)
    } else {
        KeyBackupRootResponse(
            exists = true,
            credentialId = credentialId,
            salt = salt,
            sealedRoot = sealedRoot,
            algorithm = algorithm,
            version = version,
            updatedAt = updatedAt,
        )
    }

fun GroupKeyPage.toResponse() =
    GroupKeysResponse(items.map { GroupKeyDto(it.chatId.toString(), it.metaVersion, it.ciphertext) }, nextCursor)

fun SavedNames?.toResponse() =
    if (this == null) {
        SavedNamesResponse(exists = false, version = 0)
    } else {
        SavedNamesResponse(exists = true, version = version, ciphertext = ciphertext, updatedAt = updatedAt)
    }

fun SavedNames.toVersionResponse() = SavedNamesVersionResponse(version)
