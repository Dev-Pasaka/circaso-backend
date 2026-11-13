package com.circaso.identity.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * KB2: the backup root as the phone sealed it. [credentialId] is the passkey's WebAuthn credential id (base64url),
 * [salt] and [sealedRoot] are standard base64, [algorithm] names the scheme. [expectedVersion], when sent, must be the
 * stored version, or the save is refused (`key_backup.version_conflict`).
 */
@Serializable
data class SaveKeyBackupRootRequest(
    val credentialId: String,
    val salt: String,
    val sealedRoot: String,
    val algorithm: String,
    val expectedVersion: Int? = null,
)

/** KB1 and KB2: whether backup is on and, when it is, the sealed root exactly as it was saved. */
@Serializable
data class KeyBackupRootResponse(
    val exists: Boolean,
    val credentialId: String? = null,
    val salt: String? = null,
    val sealedRoot: String? = null,
    val algorithm: String? = null,
    val version: Int? = null,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant? = null,
)

/** KB4: one group meta key, encrypted on the phone under the backup root key (standard base64). */
@Serializable
data class SaveGroupKeyRequest(val ciphertext: String)

@Serializable
data class GroupKeyDto(val chatId: String, val metaVersion: Int, val ciphertext: String)

/** KB5: one page of backed-up group keys; [nextCursor] is null on the last page. */
@Serializable
data class GroupKeysResponse(val items: List<GroupKeyDto>, val nextCursor: String? = null)

/**
 * KB6: the sealed saved names as last saved. With none saved: `exists: false`, `version: 0`, the rest null. [version] is
 * what KB7's `baseVersion` must be.
 */
@Serializable
data class SavedNamesResponse(
    val exists: Boolean,
    val version: Long,
    val ciphertext: String? = null,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant? = null,
)

/** KB7: the whole saved names blob (standard base64, AES-256-GCM under the backup root) and the version it replaces. */
@Serializable
data class SaveSavedNamesRequest(val ciphertext: String, val baseVersion: Long)

/** KB7: the version the blob was saved at, `baseVersion + 1`. */
@Serializable
data class SavedNamesVersionResponse(val version: Long)
