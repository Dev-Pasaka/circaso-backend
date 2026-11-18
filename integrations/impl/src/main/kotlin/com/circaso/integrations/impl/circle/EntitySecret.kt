package com.circaso.integrations.impl.circle

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * The entity secret of one Circle account: the 32 bytes that sign for Circaso's developer-controlled wallets (the fee
 * wallets). Circle never sees it in the clear: every request that needs it carries a fresh [ciphertext], the secret
 * encrypted with the account's entity public key (`GET /v1/w3s/config/entity/publicKey`) with RSA-OAEP, SHA-256 for
 * both the OAEP hash and MGF1, no label, then base64 (Circle's `w3s-entity-secret-sample-code`). OAEP is randomised,
 * so two ciphertexts of the same secret never match, which is what Circle demands: one it has seen is refused.
 *
 * The secret never leaves this class: it is not printed, logged or handed out.
 */
internal class EntitySecret private constructor(private val secret: ByteArray) {
    /** A new ciphertext of the secret under [publicKey], base64. Never log it. */
    fun ciphertext(publicKey: PublicKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256)
        return Base64.getEncoder().encodeToString(cipher.doFinal(secret))
    }

    override fun toString(): String = "EntitySecret(***)"

    companion object {
        private const val TRANSFORMATION = "RSA/ECB/OAEPPadding"
        private const val BYTES = 32
        private val HEX = Regex("^[0-9a-fA-F]{${BYTES * 2}}$")
        private val PEM_LINE = Regex("-----[A-Z ]+-----")

        /**
         * Spelled out: the JDK's `OAEPWithSHA-256AndMGF1Padding` shorthand hashes with SHA-256 but runs MGF1 with SHA-1,
         * which Circle can't decrypt.
         */
        private val OAEP_SHA256 =
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

        /** The secret from its 64 hex characters (as Circle's console and SDKs give it); null for anything else. */
        fun parse(hex: String?): EntitySecret? {
            val text = hex?.trim().orEmpty()
            if (!HEX.matches(text)) return null
            return EntitySecret(ByteArray(BYTES) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }

        /** Circle's entity public key: an RSA key as PEM (`-----BEGIN PUBLIC KEY-----`, SubjectPublicKeyInfo). */
        fun publicKey(pem: String): PublicKey? =
            runCatching {
                val der = Base64.getMimeDecoder().decode(pem.replace(PEM_LINE, "").trim())
                KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
            }.getOrNull()
    }
}
