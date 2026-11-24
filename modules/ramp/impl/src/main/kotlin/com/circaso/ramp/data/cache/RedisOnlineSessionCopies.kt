package com.circaso.ramp.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.OnlineSessionCopy
import com.circaso.ramp.domain.models.StoredSession
import com.circaso.ramp.domain.models.UnlockedKeys
import com.circaso.ramp.domain.ports.OnlineSessionCopies
import com.circaso.shared.Network
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The online sessions' encrypted working copies, in Redis at `rmp:online:{merchantId}` (decision 2026-11-24).
 *
 * The value is `v1.{network}.{since in epoch microseconds}.{base64(nonce ‖ AES-256-GCM ciphertext)}`. The key is
 * `HKDF-SHA256(master key, info = "ramp/online-session/v1")`, the master key being `RAMP_CREDENTIALS_MASTER_KEY`, so
 * the copy opens only on a server with that key, and a copy of Redis alone opens nothing. The additional data binds
 * it to the merchant, the network and the session: a value moved onto another merchant, or edited to name another
 * session, fails the tag. The plaintext (the provider's name and the account's credentials) never leaves this class,
 * and nothing here logs a key, a value or a credential.
 *
 * Every write has the session's own expiry as its TTL, so Redis drops a copy when its window ends whatever happens.
 */
class RedisOnlineSessionCopies(
    private val redis: RedisScope,
    private val masterKey: () -> ByteArray?,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) : OnlineSessionCopies {
    override suspend fun save(copy: OnlineSessionCopy, until: Instant) {
        val ttl = Duration.between(clock.instant(), until).takeIf { !it.isNegative && !it.isZero } ?: return
        val key = sessionKey() ?: return
        redis.set(key(copy.merchantId), seal(key, copy), ttl)
    }

    override suspend fun load(merchantId: MerchantId): StoredSession {
        val value = redis.get(key(merchantId)) ?: return StoredSession.Missing
        val key = sessionKey() ?: return StoredSession.Unreadable
        return open(key, merchantId, value)?.let { StoredSession.Found(it) } ?: StoredSession.Unreadable
    }

    override suspend fun rekey(merchantId: MerchantId, keys: UnlockedKeys) {
        val found = load(merchantId) as? StoredSession.Found ?: return
        val left = redis.ttl(key(merchantId)) ?: return
        val copy = found.copy
        save(OnlineSessionCopy(copy.merchantId, copy.network, copy.since, keys), clock.instant().plus(left))
    }

    override suspend fun delete(merchantId: MerchantId) {
        redis.delete(key(merchantId))
    }

    private fun key(merchantId: MerchantId) = "online:$merchantId"

    private fun sessionKey(): ByteArray? {
        val master = masterKey() ?: return null
        val out = ByteArray(KEY_BYTES)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(master, null, INFO.toByteArray()))
            generateBytes(out, 0, out.size)
        }
        return out
    }

    private fun seal(key: ByteArray, copy: OnlineSessionCopy): String {
        val since = micros(copy.since)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(copy.merchantId, copy.network, since))
        val sealed = nonce + cipher.doFinal(encode(copy.keys))
        return listOf(VERSION, copy.network.name, since.toString(), Base64.getEncoder().encodeToString(sealed))
            .joinToString(SEPARATOR)
    }

    /** Null when the value isn't one of ours or its tag doesn't verify (another key, a changed value). */
    private fun open(
        key: ByteArray,
        merchantId: MerchantId,
        value: String,
    ): OnlineSessionCopy? {
        val parts = value.split(SEPARATOR)
        if (parts.size != PARTS || parts[0] != VERSION) return null
        val network = Network.entries.firstOrNull { it.name == parts[1] } ?: return null
        val since = parts[2].toLongOrNull() ?: return null
        val sealed = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return null
        if (sealed.size <= NONCE_BYTES) return null
        val plain =
            try {
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, AES),
                    GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
                )
                cipher.updateAAD(aad(merchantId, network, since))
                cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
            } catch (e: GeneralSecurityException) {
                return null
            }
        val keys = decode(plain) ?: return null
        return OnlineSessionCopy(merchantId, network, instantOf(since), keys)
    }

    private fun aad(
        merchantId: MerchantId,
        network: Network,
        since: Long,
    ) = "$INFO:$merchantId:${network.name}:$since".toByteArray()

    private fun encode(keys: UnlockedKeys): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeUTF(keys.provider)
            it.writeUTF(keys.credentials.username)
            it.writeUTF(keys.credentials.password)
            it.writeUTF(keys.credentials.collectionChannelId)
        }
        return out.toByteArray()
    }

    private fun decode(plain: ByteArray): UnlockedKeys? =
        try {
            DataInputStream(ByteArrayInputStream(plain)).use {
                val provider = it.readUTF()
                UnlockedKeys(provider, CollectionCredentials(it.readUTF(), it.readUTF(), it.readUTF()))
            }
        } catch (e: IOException) {
            null
        }

    override fun toString(): String = "RedisOnlineSessionCopies(***)"

    private companion object {
        const val INFO = "ramp/online-session/v1"
        const val VERSION = "v1"
        const val SEPARATOR = "."
        const val PARTS = 4
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val MICROS = 1_000_000L
        const val NANOS_PER_MICRO = 1_000L

        fun micros(at: Instant): Long = at.epochSecond * MICROS + at.nano / NANOS_PER_MICRO

        fun instantOf(micros: Long): Instant =
            Instant.ofEpochSecond(Math.floorDiv(micros, MICROS), Math.floorMod(micros, MICROS) * NANOS_PER_MICRO)
    }
}
