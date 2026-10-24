package com.circaso.assistant.data.cache

import com.circaso.assistant.domain.ports.TextVault
import com.circaso.infrastructure.api.Secrets
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val log = LoggerFactory.getLogger("com.circaso.assistant.data.Vault")

/**
 * Encrypts conversation text before it is stored (`assistant.vault-key`, any secret string; a 256-bit key is derived
 * from it). The text is not end-to-end encrypted, because Asha has to read it to answer; this keeps it unreadable in
 * the database and its backups.
 *
 * TODO(decision: key storage and rotation; a stored message names no key id yet)
 */
class AesTextVault(secrets: Secrets, local: Boolean) : TextVault {
    private val key =
        SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(
                (
                    secrets.get("assistant.vault-key") ?: run {
                        check(local) { "Missing secret 'assistant.vault-key'" }
                        log.warn("No assistant.vault-key set; using a development key")
                        "circaso-development-assistant-vault-key"
                    }
                ).toByteArray(),
            ),
            "AES",
        )
    private val random = SecureRandom()

    override fun seal(text: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher =
            Cipher
                .getInstance(
                    "AES/GCM/NoPadding",
                ).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray()))
    }

    override fun open(sealed: String): String =
        runCatching {
            val bytes = Base64.getDecoder().decode(sealed)
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        }.getOrDefault("")
}
