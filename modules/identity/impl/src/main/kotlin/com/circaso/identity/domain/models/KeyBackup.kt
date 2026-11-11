package com.circaso.identity.domain.models

import com.circaso.identity.api.Principal
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * One person's group key backup root (2026-11-12): a random 32-byte backup root key, sealed on the phone under a key
 * derived from the same circaso.com passkey's WebAuthn PRF output as the PIN Vault. The server keeps it exactly as
 * sent and can never read it: [salt] and [sealedRoot] are base64, [credentialId] base64url, [algorithm] names the
 * scheme. [version] starts at 1 and goes up by one on every replace.
 */
data class KeyBackupRoot(
    val userId: UserId,
    val credentialId: String,
    val salt: String,
    val sealedRoot: String,
    val algorithm: String,
    val version: Int,
    val updatedAt: Instant,
)

/** `PUT /key-backup/root`, checked for shape: [expectedVersion], when given, must be the stored root's version. */
data class SaveKeyBackupRootCommand(
    val principal: Principal,
    val credentialId: String,
    val salt: String,
    val sealedRoot: String,
    val algorithm: String,
    val expectedVersion: Int?,
)

/**
 * One group's meta key at one `metaVersion`, encrypted on the phone under the backup root key: base64 [ciphertext],
 * opaque to the server. One per (person, chat, metaVersion).
 */
data class GroupKeyBackup(
    val userId: UserId,
    val chatId: UUID,
    val metaVersion: Int,
    val ciphertext: String,
    val updatedAt: Instant,
)

/** `PUT /key-backup/group-keys/{chatId}/{metaVersion}`, checked for shape. */
data class SaveGroupKeyCommand(
    val principal: Principal,
    val chatId: UUID,
    val metaVersion: Int,
    val ciphertext: String,
)

/** Where a page of group keys ends: the last entry's key, in `(chatId, metaVersion)` order. */
data class GroupKeyPosition(val chatId: UUID, val metaVersion: Int)

data class GroupKeyPage(val items: List<GroupKeyBackup>, val nextCursor: String?)
