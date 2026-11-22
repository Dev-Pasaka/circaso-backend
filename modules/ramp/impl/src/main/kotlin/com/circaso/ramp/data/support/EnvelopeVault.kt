package com.circaso.ramp.data.support

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Rewrapped
import com.circaso.ramp.domain.models.SealedCredentials
import com.circaso.ramp.domain.models.Unsealed
import com.circaso.ramp.domain.ports.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** How hard the PIN is stretched: Argon2id's memory in KiB, its passes and its lanes. */
data class Argon2Cost(val memoryKb: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        /** OWASP's second recommended Argon2id setting: 19 MiB, two passes, one lane. */
        val DEFAULT = Argon2Cost(memoryKb = 19_456, iterations = 2, parallelism = 1)
    }
}

/**
 * The credential envelope (Ramp → Merchant credentials and the trading session):
 *
 * - the credentials are sealed with AES-256-GCM under a random 256-bit **data key**;
 * - the data key is wrapped with AES-256-GCM under a **key-encryption key** derived from two secrets:
 *   `HMAC-SHA256(master key, "ramp:kek:v1" ‖ Argon2id(PIN, per-merchant salt))`.
 *
 * So a copy of the database alone, or the master key alone, opens nothing; changing the PIN re-wraps the data key and
 * leaves the sealed credentials as they are. Both ciphertexts are bound to the merchant (the id is the additional
 * data), so a row copied onto another merchant doesn't open. A wrong PIN fails the wrap's GCM tag.
 *
 * [masterKey] is asked on each use (`RAMP_CREDENTIALS_MASTER_KEY`, 64 hex characters); null means the server has none,
 * and then nothing is sealed or opened. Nothing here logs a key, a PIN or a credential.
 */
class EnvelopeVault(
    private val masterKey: () -> ByteArray?,
    private val cost: Argon2Cost = Argon2Cost.DEFAULT,
    private val random: SecureRandom = SecureRandom(),
) : CredentialVault {
    override suspend fun seal(
        merchantId: MerchantId,
        credentials: CollectionCredentials,
        pin: String,
    ): SealedCredentials? {
        val master = masterKey() ?: return null
        return withContext(Dispatchers.Default) {
            val dataKey = bytes(KEY_BYTES)
            val salt = bytes(SALT_BYTES)
            SealedCredentials(
                provider = PROVIDER,
                channelId = credentials.collectionChannelId,
                sealed = encrypt(dataKey, encode(credentials), aad(merchantId, SEALED)),
                wrappedKey = encrypt(kek(master, pin, salt, cost), dataKey, aad(merchantId, WRAPPED)),
                pinSalt = salt,
                kdfParams = paramsOf(cost),
            )
        }
    }

    override suspend fun open(
        merchantId: MerchantId,
        sealed: SealedCredentials,
        pin: String,
    ): Unsealed {
        val master = masterKey() ?: return Unsealed.Unavailable
        return withContext(Dispatchers.Default) {
            val dataKey = unwrap(master, merchantId, sealed, pin) ?: return@withContext Unsealed.WrongPin
            val plain =
                decrypt(dataKey, sealed.sealed, aad(merchantId, SEALED)) ?: return@withContext Unsealed.WrongPin
            Unsealed.Opened(decode(plain))
        }
    }

    override suspend fun rewrap(
        merchantId: MerchantId,
        sealed: SealedCredentials,
        currentPin: String,
        newPin: String,
    ): Rewrapped {
        val master = masterKey() ?: return Rewrapped.Unavailable
        return withContext(Dispatchers.Default) {
            val dataKey = unwrap(master, merchantId, sealed, currentPin) ?: return@withContext Rewrapped.WrongPin
            val salt = bytes(SALT_BYTES)
            Rewrapped.Done(
                SealedCredentials(
                    provider = sealed.provider,
                    channelId = sealed.channelId,
                    sealed = sealed.sealed,
                    wrappedKey = encrypt(kek(master, newPin, salt, cost), dataKey, aad(merchantId, WRAPPED)),
                    pinSalt = salt,
                    kdfParams = paramsOf(cost),
                ),
            )
        }
    }

    private fun unwrap(
        master: ByteArray,
        merchantId: MerchantId,
        sealed: SealedCredentials,
        pin: String,
    ): ByteArray? {
        val stored = costOf(sealed.kdfParams) ?: return null
        return decrypt(kek(master, pin, sealed.pinSalt, stored), sealed.wrappedKey, aad(merchantId, WRAPPED))
    }

    private fun kek(
        master: ByteArray,
        pin: String,
        salt: ByteArray,
        cost: Argon2Cost,
    ): ByteArray {
        val stretched = ByteArray(KEY_BYTES)
        val parameters =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(cost.memoryKb)
                .withIterations(cost.iterations)
                .withParallelism(cost.parallelism)
                .build()
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(pin.toByteArray(), stretched)
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(master, HMAC)) }
        mac.update(KEK_LABEL.toByteArray())
        return mac.doFinal(stretched)
    }

    private fun encrypt(
        key: ByteArray,
        plain: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val nonce = bytes(NONCE_BYTES)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plain)
    }

    /** Null when the tag doesn't verify: the wrong key (a wrong PIN, another master key) or a changed row. */
    private fun decrypt(
        key: ByteArray,
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray? {
        if (sealed.size <= NONCE_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            val nonce = GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), nonce)
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private fun bytes(count: Int) = ByteArray(count).also(random::nextBytes)

    private fun aad(merchantId: MerchantId, part: String) = "ramp:credentials:v1:$part:$merchantId".toByteArray()

    private fun encode(credentials: CollectionCredentials): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeUTF(credentials.username)
            it.writeUTF(credentials.password)
            it.writeUTF(credentials.collectionChannelId)
        }
        return out.toByteArray()
    }

    private fun decode(plain: ByteArray): CollectionCredentials =
        DataInputStream(ByteArrayInputStream(plain)).use {
            CollectionCredentials(username = it.readUTF(), password = it.readUTF(), collectionChannelId = it.readUTF())
        }

    override fun toString(): String = "EnvelopeVault(***)"

    companion object {
        const val PROVIDER = "payhero"
        const val MASTER_KEY_SECRET = "ramp.credentials-master-key"
        private const val MASTER_KEY_VERSION = 1
        private const val KEY_BYTES = 32
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AES = "AES"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val HMAC = "HmacSHA256"
        private const val KEK_LABEL = "ramp:kek:v1"
        private const val SEALED = "sealed"
        private const val WRAPPED = "wrapped"
        private const val HEX_LENGTH = 64
        private val PARAMS = Regex("""argon2id;m=(\d+);t=(\d+);p=(\d+);mk=\d+""")

        fun paramsOf(cost: Argon2Cost): String =
            "argon2id;m=${cost.memoryKb};t=${cost.iterations};p=${cost.parallelism};mk=$MASTER_KEY_VERSION"

        fun costOf(params: String): Argon2Cost? =
            PARAMS.matchEntire(params)?.destructured?.let { (memory, iterations, parallelism) ->
                Argon2Cost(memory.toInt(), iterations.toInt(), parallelism.toInt())
            }

        /**
         * The master key from its secret: 64 hex characters, or null (said in the log, by name, never by value)
         * when it is absent or malformed. The server starts all the same; payment accounts just can't be stored or
         * unlocked on it.
         */
        fun masterKey(raw: String?): ByteArray? {
            val hex = raw?.trim().orEmpty()
            val valid = hex.length == HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            if (!valid) {
                LoggerFactory
                    .getLogger(EnvelopeVault::class.java)
                    .warn("secret 'RAMP_CREDENTIALS_MASTER_KEY' is {}", if (hex.isEmpty()) "not set" else "not 64 hex")
                return null
            }
            return ByteArray(KEY_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
        }

        private const val HEX_RADIX = 16
    }
}
