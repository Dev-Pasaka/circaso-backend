package com.circaso.integrations.impl.circle

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The entity secret ciphertext (Circle's developer-controlled wallets), against a key pair made here: what Circle
 * does with its private key, this test does with ours.
 */
class EntitySecretTest {
    private val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val hex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val bytes = ByteArray(32) { it.toByte() }

    private val sha256Everywhere =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    private fun decrypt(
        ciphertext: String,
        key: PrivateKey,
        spec: OAEPParameterSpec = sha256Everywhere,
    ): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPPadding").run {
            init(Cipher.DECRYPT_MODE, key, spec)
            doFinal(Base64.getDecoder().decode(ciphertext))
        }

    private fun pem(): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }

    @Test
    fun `the ciphertext is the 32 secret bytes under RSA-OAEP with SHA-256 for the hash and for MGF1, in base64`() {
        val secret = assertNotNull(EntitySecret.parse(hex))
        val key = assertNotNull(EntitySecret.publicKey(pem()), "Circle's key is a PEM SubjectPublicKeyInfo")

        val ciphertext = secret.ciphertext(key)

        assertContentEquals(bytes, decrypt(ciphertext, pair.private))
        assertEquals(256, Base64.getDecoder().decode(ciphertext).size, "one RSA-2048 block")
        assertFalse('\n' in ciphertext || ' ' in ciphertext, "plain base64, no line breaks")
        // The JDK's "OAEPWithSHA-256AndMGF1Padding" shorthand runs MGF1 with SHA-1; a ciphertext made that way is
        // one Circle can't open, and this one can't be opened that way.
        val sha1Mgf = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        assertFails { decrypt(ciphertext, pair.private, sha1Mgf) }
    }

    @Test
    fun `every ciphertext is new, since Circle refuses one it has seen`() {
        val secret = assertNotNull(EntitySecret.parse(hex))
        val key = assertNotNull(EntitySecret.publicKey(pem()))

        val ciphertexts = List(5) { secret.ciphertext(key) }

        assertEquals(5, ciphertexts.toSet().size)
        ciphertexts.forEach { assertContentEquals(bytes, decrypt(it, pair.private)) }
    }

    @Test
    fun `only 64 hex characters are a secret, and the secret never prints`() {
        assertNull(EntitySecret.parse(null))
        assertNull(EntitySecret.parse(""))
        assertNull(EntitySecret.parse(hex.dropLast(2)), "31 bytes")
        assertNull(EntitySecret.parse(hex + "00"), "33 bytes")
        assertNull(EntitySecret.parse(hex.replaceRange(0, 1, "g")))
        val secret = assertNotNull(EntitySecret.parse("  ${hex.uppercase()}\n"), "spaces around it and any case")
        assertContentEquals(bytes, decrypt(secret.ciphertext(pair.public), pair.private))
        assertEquals("EntitySecret(***)", secret.toString())
        assertNotEquals(hex, secret.toString())
    }

    @Test
    fun `a public key that isn't one is no key`() {
        assertNull(EntitySecret.publicKey(""))
        assertNull(EntitySecret.publicKey("-----BEGIN PUBLIC KEY-----\nnot base64 at all\n-----END PUBLIC KEY-----"))
        assertNotNull(
            EntitySecret.publicKey(Base64.getEncoder().encodeToString(pair.public.encoded)),
            "the bare base64 without the PEM lines reads too",
        )
    }
}
