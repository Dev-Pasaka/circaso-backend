package com.circaso.identity.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind

/**
 * The PIN Vault's codes (2026-11-11). The vault lives in identity, but its codes are `vault.*`: the app keys the vault
 * screens on them, apart from sign-in and account errors.
 */
sealed class VaultError(override val code: String, override val message: String, override val kind: ErrorKind) :
    DomainError {
    data object NotFound : VaultError(
        "vault.not_found",
        "You haven't backed up your PIN yet.",
        ErrorKind.NOT_FOUND,
    )

    /** `expectedVersion` was given and the stored vault is at another version (or there is none). */
    data object VersionConflict : VaultError(
        "vault.version_conflict",
        "Your PIN backup changed on another phone. Open it again before you replace it.",
        ErrorKind.CONFLICT,
    )

    data object CredentialIdInvalid : VaultError(
        "vault.credential_id_invalid",
        "The passkey for this backup isn't valid.",
        ErrorKind.INVALID,
    )

    data object SaltInvalid : VaultError(
        "vault.salt_invalid",
        "This PIN backup couldn't be saved.",
        ErrorKind.INVALID,
    )

    data object CiphertextInvalid : VaultError(
        "vault.ciphertext_invalid",
        "This PIN backup couldn't be saved.",
        ErrorKind.INVALID,
    )

    data object AlgorithmInvalid : VaultError(
        "vault.algorithm_invalid",
        "This PIN backup couldn't be saved.",
        ErrorKind.INVALID,
    )

    data object ExpectedVersionInvalid : VaultError(
        "vault.expected_version_invalid",
        "This PIN backup couldn't be saved.",
        ErrorKind.INVALID,
    )
}
