package com.circaso.ramp.data.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.ports.WebhookSecretVault
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The webhook signing secret's seal (2026-11-23): AES-256-GCM under
 * `HMAC-SHA256(RAMP_CREDENTIALS_MASTER_KEY, "ramp:webhook-secret:v1")`, the merchant's id as the additional data.
 *
 * **Why the master key alone, and not the PIN envelope:** FinoQ calls back while the merchant is offline, when nothing
 * sealed under their PIN can be opened, and the secret is only needed to tell FinoQ's callbacks from forgeries. A
 * callback is a doorbell (the ramp re-reads every outcome from FinoQ), so the secret can't move money: a leak of it
 * lets someone ring a doorbell, which the unguessable callback address already guards. The label keeps this key apart
 * from the credential envelope's and the identity keys', which come from the same master key.
 *
 * [masterKey] is asked on each use; null means the server has none, and then nothing is sealed or opened. Nothing here
 * logs the secret.
 */
class MasterKeyWebhookSecrets(
    private val masterKey: () -> ByteArray?,
    private val random: SecureRandom = SecureRandom(),
) : WebhookSecretVault {
    override fun seal(merchantId: MerchantId, secret: String): ByteArray? {
        val key = keyOf(masterKey() ?: return null)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(merchantId))
        return nonce + cipher.doFinal(secret.toByteArray())
    }

    override fun open(merchantId: MerchantId, sealed: ByteArray): String? {
        val key = keyOf(masterKey() ?: return null)
        if (sealed.size <= NONCE_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, AES),
                GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
            )
            cipher.updateAAD(aad(merchantId))
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES).decodeToString()
        } catch (e: GeneralSecurityException) {
            // The merchant's callbacks go unchecked until they enter it again; said by id, never by value.
            LoggerFactory
                .getLogger(MasterKeyWebhookSecrets::class.java)
                .warn("ramp webhook secret does not open: merchant={} ({})", merchantId, e.javaClass.simpleName)
            null
        }
    }

    private fun keyOf(master: ByteArray): ByteArray =
        Mac
            .getInstance(HMAC)
            .apply { init(SecretKeySpec(master, HMAC)) }
            .doFinal(LABEL.toByteArray())

    private fun aad(merchantId: MerchantId) = "$LABEL:$merchantId".toByteArray()

    override fun toString(): String = "MasterKeyWebhookSecrets(***)"

    private companion object {
        const val LABEL = "ramp:webhook-secret:v1"
        const val HMAC = "HmacSHA256"
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
