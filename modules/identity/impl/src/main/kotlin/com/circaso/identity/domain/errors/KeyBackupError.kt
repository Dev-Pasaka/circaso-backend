package com.circaso.identity.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind

/**
 * The group key backup's codes (2026-11-12). It lives in identity beside the PIN Vault, but its codes are
 * `key_backup.*`, so the app keys its backup screens on them.
 */
sealed class KeyBackupError(override val code: String, override val message: String, override val kind: ErrorKind) :
    DomainError {
    /** A group key was sent before the backup was turned on (no root), or after it was turned off. */
    data object NotFound : KeyBackupError(
        "key_backup.not_found",
        "Group key backup is off. Turn it on first.",
        ErrorKind.NOT_FOUND,
    )

    /** `expectedVersion` was given and the stored root is at another version (or there is none). */
    data object VersionConflict : KeyBackupError(
        "key_backup.version_conflict",
        "Your key backup changed on another phone. Read it again before you replace it.",
        ErrorKind.CONFLICT,
    )

    /** The caller isn't in the chat (and never backed up a key of it): it looks the same as no chat. */
    data object ChatNotFound : KeyBackupError(
        "key_backup.chat_not_found",
        "This group isn't one of yours.",
        ErrorKind.NOT_FOUND,
    )

    data object TooMany : KeyBackupError(
        "key_backup.too_many",
        "Your key backup is full.",
        ErrorKind.CONFLICT,
    )

    data object CredentialIdInvalid : KeyBackupError(
        "key_backup.credential_id_invalid",
        "The passkey for this backup isn't valid.",
        ErrorKind.INVALID,
    )

    data object SaltInvalid : KeyBackupError(
        "key_backup.salt_invalid",
        "This key backup couldn't be saved.",
        ErrorKind.INVALID,
    )

    data object SealedRootInvalid : KeyBackupError(
        "key_backup.sealed_root_invalid",
        "This key backup couldn't be saved.",
        ErrorKind.INVALID,
    )

    data object AlgorithmInvalid : KeyBackupError(
        "key_backup.algorithm_invalid",
        "This key backup couldn't be saved.",
        ErrorKind.INVALID,
    )

    data object ExpectedVersionInvalid : KeyBackupError(
        "key_backup.expected_version_invalid",
        "This key backup couldn't be saved.",
        ErrorKind.INVALID,
    )

    data object ChatIdInvalid : KeyBackupError(
        "key_backup.chat_id_invalid",
        "This group key couldn't be saved.",
        ErrorKind.INVALID,
    )

    data object MetaVersionInvalid : KeyBackupError(
        "key_backup.meta_version_invalid",
        "This group key couldn't be saved.",
        ErrorKind.INVALID,
    )

    data object CiphertextInvalid : KeyBackupError(
        "key_backup.ciphertext_invalid",
        "This group key couldn't be saved.",
        ErrorKind.INVALID,
    )
}
