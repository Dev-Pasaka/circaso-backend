package com.circaso.messaging

import com.circaso.identity.api.DeviceSignatures
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.ports.BundleRateLimit
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.shared.DeviceId
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy
import java.security.PublicKey
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/** The key directory in memory; each one-time prekey remembers which phone uploaded it and when. */
class MemoryKeys : KeyRepository {
    private data class Stored(val key: OneTimePreKey, val deviceId: DeviceId, val at: Instant)

    val bundles = ConcurrentHashMap<UserId, KeyBundle>()
    private val prekeys = ConcurrentHashMap<UserId, TreeMap<Int, Stored>>()

    private fun of(userId: UserId) = prekeys.getOrPut(userId) { TreeMap() }

    override suspend fun bundle(userId: UserId) = bundles[userId]

    override suspend fun lockBundle(userId: UserId) = bundles[userId]

    override suspend fun oneTimeKeyIds(userId: UserId, deviceId: DeviceId): Set<Int> =
        synchronized(this) { of(userId).filterValues { it.deviceId == deviceId }.keys.toSet() }

    override suspend fun publish(
        bundle: KeyBundle,
        oneTimePreKeys: List<OneTimePreKey>,
        at: Instant,
    ) = synchronized(this) {
        bundles[bundle.userId] = bundle
        val keys = of(bundle.userId)
        keys.clear()
        for (key in oneTimePreKeys) keys[key.keyId] = Stored(key, bundle.deviceId, at)
    }

    override suspend fun addOneTimeKeys(
        userId: UserId,
        deviceId: DeviceId,
        keys: List<OneTimePreKey>,
        at: Instant,
    ) = synchronized(this) {
        val stored = of(userId)
        check(keys.none { it.keyId in stored }) { "a resent id would break the primary key" }
        for (key in keys) stored[key.keyId] = Stored(key, deviceId, at)
    }

    override suspend fun claimOneTimeKey(userId: UserId, deviceId: DeviceId): OneTimePreKey? =
        synchronized(this) {
            val keys = of(userId)
            val first = keys.entries.firstOrNull { it.value.deviceId == deviceId } ?: return null
            keys.remove(first.key)?.key
        }

    override suspend fun removeBundle(
        userId: UserId,
        deviceId: DeviceId,
        replacedAt: Instant,
    ): Boolean =
        synchronized(this) {
            of(userId).entries.removeIf { it.value.deviceId == deviceId && it.value.at.isBefore(replacedAt) }
            if (bundles[userId]?.deviceId != deviceId) return false
            bundles.remove(userId)
            true
        }

    fun prekeyCount(userId: UserId) = synchronized(this) { prekeys[userId]?.size ?: 0 }
}

class MemoryRateLimit : BundleRateLimit {
    private val counts = ConcurrentHashMap<UserId, Long>()

    override suspend fun count(userId: UserId) = counts.merge(userId, 1L, Long::plus)!!

    override suspend fun retryAfter(userId: UserId): Duration? = Duration.ofMinutes(42)
}

/** Reach as users would answer it: only the pairs added here, and the blocks (blocker to blocked). */
class FakeReach : ReachPolicy {
    val pairs = ConcurrentHashMap.newKeySet<Pair<UserId, UserId>>()
    val blocks = ConcurrentHashMap.newKeySet<Pair<UserId, UserId>>()

    override suspend fun canReach(from: UserId, to: UserId) = (from to to) in pairs

    override suspend fun reachable(from: UserId, to: Set<UserId>) = to.filter { canReach(from, it) }.toSet()

    override suspend fun blockedEitherWay(a: UserId, b: UserId) = (a to b) in blocks || (b to a) in blocks
}

/** Identity's `DeviceSignatures` with real ES256 checks against the keys registered here, one active phone each. */
class FakeDeviceSignatures : DeviceSignatures {
    private val devices = ConcurrentHashMap<Pair<UserId, DeviceId>, PublicKey>()

    fun register(
        userId: UserId,
        deviceId: DeviceId,
        key: PublicKey,
    ) {
        devices[userId to deviceId] = key
    }

    override suspend fun verify(
        userId: UserId,
        deviceId: DeviceId,
        payload: ByteArray,
        signature: ByteArray,
    ): Resource<Unit> {
        val key = devices[userId to deviceId] ?: return Resource.error(ProofInvalid)
        val valid =
            runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(payload)
                    verify(signature)
                }
            }.getOrDefault(false)
        return if (valid) Resource.success(Unit) else Resource.error(ProofInvalid)
    }

    private object ProofInvalid : DomainError {
        override val code = "identity.device_proof_invalid"
        override val message = "invalid"
        override val kind = ErrorKind.UNAUTHORIZED
    }
}
