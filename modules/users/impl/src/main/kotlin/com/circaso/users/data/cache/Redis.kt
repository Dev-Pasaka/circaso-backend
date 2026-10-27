package com.circaso.users.data.cache

import com.circaso.infrastructure.api.ObjectStore
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.users.domain.ports.AvatarStorage
import com.circaso.users.domain.ports.PhoneHasher
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.StoredAvatar
import com.circaso.users.domain.ports.UploadedKey
import com.circaso.users.domain.ports.UsersCache
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val log = LoggerFactory.getLogger("com.circaso.users.data")

/**
 * Cached reach decisions. Each target has an epoch (`usr:reach-epoch:{target}`) that is part of the key of every
 * decision towards it, so a block, a new connection or a status change clears all of them by bumping one counter,
 * without scanning for keys.
 */
class RedisReachCache(private val redis: RedisScope) : ReachCache {
    private suspend fun epoch(to: UserId) = redis.get("reach-epoch:$to") ?: "0"

    override suspend fun get(from: UserId, to: UserId): Boolean? =
        redis.get("reach:$from:$to:${epoch(to)}")?.let { it == "1" }

    override suspend fun put(
        from: UserId,
        to: UserId,
        reachable: Boolean,
    ) = redis.set("reach:$from:$to:${epoch(to)}", if (reachable) "1" else "0", DECISION_TTL)

    override suspend fun invalidateTarget(target: UserId) {
        redis.incrementRenewing("reach-epoch:$target", EPOCH_TTL)
    }

    private companion object {
        val DECISION_TTL: Duration = Duration.ofMinutes(10)
        val EPOCH_TTL: Duration = Duration.ofHours(1)
    }
}

class RedisUsersCache(private val redis: RedisScope) : UsersCache {
    override suspend fun countHandleLookup(userId: UserId): Long =
        redis.increment("handle-lookup:$userId", Duration.ofHours(1))

    override suspend fun handleLookupRetryAfter(userId: UserId): Duration? = redis.ttl("handle-lookup:$userId")

    override suspend fun recordSyncBatch(
        userId: UserId,
        syncId: UUID,
        final: Boolean,
    ) {
        val key = "sync:$userId:$syncId"
        if (final) redis.delete(key) else redis.increment(key, Duration.ofHours(1))
    }
}

/** The phone-HMAC key is one secret shared with identity, so a number's hash here equals `phoneHmac` in its events. */
class KeyedPhoneHasher(secrets: Secrets, local: Boolean) : PhoneHasher {
    private val secret: ByteArray =
        (
            secrets.get("phone-hmac-key") ?: run {
                check(local) { "Missing secret 'phone-hmac-key'" }
                log.warn("No phone-hmac-key set; using a development key")
                "circaso-development-phone-hmac-key"
            }
        ).toByteArray()

    override fun hmac(phone: PhoneNumber): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(phone.e164.toByteArray()))
    }
}

/** Avatars in the module's file prefix: `avatars/{userId}/{id}.{ext}`. */
class ObjectStoreAvatars(private val store: ObjectStore) : AvatarStorage {
    override suspend fun prepareUpload(
        userId: UserId,
        contentType: String,
        expiresIn: Duration,
    ): Resource<UploadedKey> {
        val extension =
            when (contentType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
        val key = "avatars/$userId/${Uuid7.generate()}.$extension"
        return when (val url = store.presignUpload(key, contentType, expiresIn)) {
            is Resource.Success -> Resource.success(UploadedKey(url.data, key))
            is Resource.Error -> url
        }
    }

    override suspend fun describe(key: String): StoredAvatar? =
        store.describe(key)?.let {
            StoredAvatar(it.sizeBytes, it.contentType)
        }

    override suspend fun delete(key: String) = store.delete(key)

    /** A link to the picture that works for an hour; null if storage is down, so a profile still loads without its picture. */
    override suspend fun urlOf(key: String): String? =
        (store.presignDownload(key, Duration.ofHours(1)) as? Resource.Success)?.data
}

@Suppress("unused")
private val keep = SecureRandom::class
