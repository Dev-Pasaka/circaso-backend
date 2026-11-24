package com.circaso.ramp.data.support

import com.circaso.ramp.domain.ports.LockLinks
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The "This wasn't me" token (2026-11-24): `base64url(alert id ‖ HMAC-SHA256(lock key, "lock" ‖ alert id))`, 48 bytes.
 * The lock key is `HMAC-SHA256(RAMP_CREDENTIALS_MASTER_KEY, "ramp:lock-link:v1")`, used for nothing else, so a token
 * can't be forged, can't name another alert, and means only "lock this merchant's withdrawals". The same alert always
 * gives the same token, so a retried email carries the link the first one did. Expiry and single use are the alert
 * row's (`link_expires_at`, `link_used_at`). [masterKey] is asked on each use; without it no link is made.
 */
class HmacLockLinks(private val masterKey: () -> ByteArray?) : LockLinks {
    override fun token(alertId: UUID): String? {
        val key = key() ?: return null
        val id = bytes(alertId)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id + mac(key, id))
    }

    override fun alertOf(token: String): UUID? {
        val key = key() ?: return null
        val raw = runCatching { Base64.getUrlDecoder().decode(token.trim()) }.getOrNull() ?: return null
        if (raw.size != ID_BYTES + MAC_BYTES) return null
        val id = raw.copyOfRange(0, ID_BYTES)
        if (!MessageDigest.isEqual(mac(key, id), raw.copyOfRange(ID_BYTES, raw.size))) return null
        val buffer = ByteBuffer.wrap(id)
        return UUID(buffer.long, buffer.long)
    }

    private fun key(): ByteArray? = masterKey()?.let { hmac(it, LABEL.toByteArray()) }

    private fun mac(key: ByteArray, id: ByteArray): ByteArray = hmac(key, PURPOSE.toByteArray() + id)

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac
            .getInstance(HMAC)
            .apply { init(SecretKeySpec(key, HMAC)) }
            .doFinal(message)

    private fun bytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(ID_BYTES)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private companion object {
        const val HMAC = "HmacSHA256"
        const val LABEL = "ramp:lock-link:v1"
        const val PURPOSE = "lock"
        const val ID_BYTES = 16
        const val MAC_BYTES = 32
    }
}
