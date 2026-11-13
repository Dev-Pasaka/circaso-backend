package com.circaso.identity.domain.models

import com.circaso.identity.api.Principal
import com.circaso.shared.UserId
import java.time.Instant

/**
 * One person's PIN Vault (2026-11-11): their Circle wallet PIN, and optionally their security-question answers,
 * encrypted on the phone under a key derived from a passkey's WebAuthn PRF output. The server keeps the blob exactly as
 * the phone sent it and can never read it: [salt] and [ciphertext] are base64, [credentialId] base64url (the passkey
 * the phone must ask for), [algorithm] names the scheme so the phone knows how to open it. [version] starts at 1 and
 * goes up by one on every replace.
 */
data class PinVault(
    val userId: UserId,
    val credentialId: String,
    val salt: String,
    val ciphertext: String,
    val algorithm: String,
    val version: Int,
    val updatedAt: Instant,
)

/** `PUT /vault`, checked for shape: [expectedVersion], when given, must be the stored vault's version. */
data class SaveVaultCommand(
    val principal: Principal,
    val credentialId: String,
    val salt: String,
    val ciphertext: String,
    val algorithm: String,
    val expectedVersion: Int?,
)
