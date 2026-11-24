package com.circaso.ramp

import com.circaso.ramp.domain.models.AssertionChallenge
import com.circaso.ramp.domain.models.PasskeyAssertion
import com.circaso.ramp.domain.models.RegistrationChallenge
import com.circaso.ramp.presentation.dto.PasskeyCredentialDto
import com.circaso.ramp.presentation.dto.PasskeyResponseDto
import com.circaso.ramp.presentation.mappers.toJson
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.random.Random

/**
 * A software WebAuthn authenticator for the tests (a platform passkey: ES256, user verified, "none" attestation),
 * producing exactly what a browser hands the portal. [origin] and [rpId] can be wrong on purpose.
 */
class TestAuthenticator(
    private val origin: String = "https://merchants.circaso.com",
    private val rpId: String = "merchants.circaso.com",
) {
    private val keys: KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
    val credentialId: ByteArray = Random.nextBytes(16)
    var userHandle: ByteArray? = null
    var signCount = 0L

    /** `navigator.credentials.create`: the registration credential's JSON. */
    fun create(challenge: RegistrationChallenge, userVerified: Boolean = true): String =
        create(challenge.options.challenge, challenge.options.userId, userVerified)

    /** The same from the options' JSON values: `publicKey.challenge` and `publicKey.user.id`. */
    fun create(
        challenge: String,
        userId: String,
        userVerified: Boolean = true,
    ): String {
        userHandle = b64d(userId)
        val clientData = clientData("webauthn.create", challenge)
        val authData = authData(userVerified, attested = true)
        val attestation =
            Cbor.map(
                listOf(
                    Cbor.text("fmt") to Cbor.text("none"),
                    Cbor.text("attStmt") to Cbor.map(emptyList()),
                    Cbor.text("authData") to Cbor.bytes(authData),
                ),
            )
        return PasskeyCredentialDto(
            id = b64(credentialId),
            rawId = b64(credentialId),
            response =
                PasskeyResponseDto(
                    clientDataJSON = b64(clientData),
                    attestationObject = b64(attestation),
                    transports = listOf("internal"),
                ),
            authenticatorAttachment = "platform",
        ).toJson()
    }

    /** `navigator.credentials.get`: the assertion's JSON over [challenge] (base64url, as the options carry it). */
    fun get(challenge: String, counter: Long = ++signCount): String {
        val clientData = clientData("webauthn.get", challenge)
        val authData = authData(userVerified = true, attested = false, counter = counter)
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(authData + sha256(clientData))
                sign()
            }
        return PasskeyCredentialDto(
            id = b64(credentialId),
            rawId = b64(credentialId),
            response =
                PasskeyResponseDto(
                    clientDataJSON = b64(clientData),
                    authenticatorData = b64(authData),
                    signature = b64(signature),
                    userHandle = userHandle?.let(::b64),
                ),
            authenticatorAttachment = "platform",
        ).toJson()
    }

    /** The proof a sensitive request carries, answering [challenge]. */
    fun answer(challenge: AssertionChallenge): PasskeyAssertion =
        PasskeyAssertion(challenge.challengeId, get(challenge.options.challenge))

    private fun clientData(type: String, challenge: String): ByteArray =
        """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()

    private fun authData(
        userVerified: Boolean,
        attested: Boolean,
        counter: Long = signCount,
    ): ByteArray {
        val flags = (UP or (if (userVerified) UV else 0) or (if (attested) AT else 0)).toByte()
        val out = ByteArrayOutputStream()
        out.write(sha256(rpId.toByteArray()))
        out.write(byteArrayOf(flags))
        out.write(ByteBuffer.allocate(4).putInt(counter.toInt()).array())
        if (attested) {
            out.write(ByteArray(16)) // AAGUID: none
            out.write(ByteBuffer.allocate(2).putShort(credentialId.size.toShort()).array())
            out.write(credentialId)
            out.write(coseKey())
        }
        return out.toByteArray()
    }

    private fun coseKey(): ByteArray {
        val point = (keys.public as ECPublicKey).w
        return Cbor.map(
            listOf(
                Cbor.int(1) to Cbor.int(2), // kty: EC2
                Cbor.int(3) to Cbor.int(-7), // alg: ES256
                Cbor.int(-1) to Cbor.int(1), // crv: P-256
                Cbor.int(-2) to Cbor.bytes(fixed(point.affineX)),
                Cbor.int(-3) to Cbor.bytes(fixed(point.affineY)),
            ),
        )
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - raw.size) + raw
    }

    companion object {
        private const val UP = 0x01
        private const val UV = 0x04
        private const val AT = 0x40

        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun b64d(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** Just enough CBOR for an attestation object and a COSE key. */
object Cbor {
    private fun head(major: Int, value: Long): ByteArray =
        when {
            value < 24 -> byteArrayOf(((major shl 5) or value.toInt()).toByte())
            value < 256 -> byteArrayOf(((major shl 5) or 24).toByte(), value.toByte())
            value < 65_536 -> byteArrayOf(((major shl 5) or 25).toByte(), (value shr 8).toByte(), value.toByte())
            else -> byteArrayOf(((major shl 5) or 26).toByte()) + ByteBuffer.allocate(4).putInt(value.toInt()).array()
        }

    fun int(value: Long): ByteArray = if (value >= 0) head(0, value) else head(1, -1 - value)

    fun int(value: Int): ByteArray = int(value.toLong())

    fun bytes(value: ByteArray): ByteArray = head(2, value.size.toLong()) + value

    fun text(value: String): ByteArray = value.toByteArray().let { head(3, it.size.toLong()) + it }

    fun map(entries: List<Pair<ByteArray, ByteArray>>): ByteArray =
        entries.fold(head(5, entries.size.toLong())) { acc, (k, v) -> acc + k + v }
}
