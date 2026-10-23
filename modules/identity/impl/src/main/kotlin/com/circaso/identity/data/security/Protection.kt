package com.circaso.identity.data.security

import com.circaso.identity.domain.ports.AttestationVerifier
import com.circaso.identity.domain.ports.DeviceKeyVerifier
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val log = LoggerFactory.getLogger("com.circaso.identity.data.security.Protection")

/**
 * The phone-HMAC key is one secret shared with users (`phone-hmac-key`), so identity's events match users'
 * waiting contacts directly. A number must wait in Redis between the SMS and the code check; there it is sealed
 * with AES-GCM under a key derived from the same secret, so Redis and its backups never hold a raw number.
 */
class KeyedPhoneProtection(secrets: Secrets, local: Boolean) : PhoneProtection {
    private val secret: ByteArray = (secrets.get("phone-hmac-key") ?: run {
        check(local) { "Missing secret 'phone-hmac-key'" }
        log.warn("No phone-hmac-key set; using a development key")
        "circaso-development-phone-hmac-key"
    }).toByteArray()

    private val sealKey = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("otp-seal:".toByteArray() + secret), "AES")
    private val random = SecureRandom()

    override fun hmac(phone: PhoneNumber): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(phone.e164.toByteArray()))
    }

    override fun seal(phone: PhoneNumber): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, sealKey, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(phone.e164.toByteArray()))
    }

    override fun unseal(sealed: String): PhoneNumber? = runCatching {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, sealKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        PhoneNumber.parse(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size))))
    }.getOrNull()
}

/** Phones sign with a hardware-backed ES256 key (Android Keystore, Secure Enclave): an SPKI public key, DER signatures. */
class EcdsaDeviceKeyVerifier : DeviceKeyVerifier {
    override fun isValid(publicKey: String, algorithm: String): Boolean = algorithm == "ES256" && parse(publicKey) != null

    override fun verify(publicKey: String, algorithm: String, payload: ByteArray, signature: ByteArray): Boolean {
        if (algorithm != "ES256") return false
        val key = parse(publicKey) ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    private fun parse(base64: String) = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))
    }.getOrNull()
}

/**
 * Play Integrity and App Attest. Not wired to Google or Apple yet, so it refuses everything: in `LOG` mode
 * (development, staging) that is only recorded, and in `ENFORCE` mode (production) nobody registers a device
 * until the real check exists. Failing closed is deliberate.
 */
// TODO(vendor: Play Integrity API credentials and App Attest key ids)
class UnconfiguredAttestation : AttestationVerifier {
    private val log = LoggerFactory.getLogger(UnconfiguredAttestation::class.java)

    override suspend fun verify(platform: String, deviceId: DeviceId, attestation: String?): Boolean {
        log.info("attestation not checked: no verifier is configured (platform={}, present={})", platform, !attestation.isNullOrBlank())
        return false
    }
}
