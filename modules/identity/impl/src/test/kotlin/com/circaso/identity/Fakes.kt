package com.circaso.identity

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.GroupMembership
import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.DeletionFeedback
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.GroupKeyBackup
import com.circaso.identity.domain.models.GroupKeyPosition
import com.circaso.identity.domain.models.KeyBackupRoot
import com.circaso.identity.domain.models.PinVault
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SavedNames
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.ports.Hints
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PendingOtp
import com.circaso.identity.domain.ports.PhoneRelease
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.KeyBackupRepository
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.repositories.RecoveryRepository
import com.circaso.identity.domain.repositories.SecurityLogRepository
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.identity.domain.repositories.VaultRepository
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventSubscription
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One in-memory stand-in for every repository port, so use cases run without a database. The claims behave like the
 * unique indexes: a number or a login another account holds is refused.
 */
class MemoryStore :
    AccountRepository,
    ProviderRepository,
    DeviceRepository,
    SessionRepository,
    SecurityLogRepository,
    RecoveryRepository,
    IdentityStore,
    Transactor {
    val accounts = ConcurrentHashMap<UserId, Account>()
    val links = CopyOnWriteArrayList<ProviderLink>()
    val devices = ConcurrentHashMap<Pair<UserId, DeviceId>, Device>()
    val sessions = ConcurrentHashMap<SessionId, Session>()
    val log = CopyOnWriteArrayList<SecurityEvent>()
    val feedback = CopyOnWriteArrayList<DeletionFeedback>()
    val recoveries = ConcurrentHashMap<UUID, SupportRecovery>()
    val vaults = ConcurrentHashMap<UserId, PinVault>()

    /** Apart from the store: `find(userId)` is already the account's. */
    val vaultRepository =
        object : VaultRepository {
            override suspend fun find(userId: UserId): PinVault? = vaults[userId]
        }
    val keyBackupRoots = ConcurrentHashMap<UserId, KeyBackupRoot>()

    /** Group keys by (account, chat, metaVersion). */
    val groupKeys = ConcurrentHashMap<Triple<UserId, UUID, Int>, GroupKeyBackup>()

    val savedNames = ConcurrentHashMap<UserId, SavedNames>()

    /** Apart from the store, like [vaultRepository]. */
    val keyBackupRepository =
        object : KeyBackupRepository {
            override suspend fun findRoot(userId: UserId) = keyBackupRoots[userId]

            override suspend fun findGroupKey(
                userId: UserId,
                chatId: UUID,
                metaVersion: Int,
            ) = groupKeys[Triple(userId, chatId, metaVersion)]

            override suspend fun hasGroupKeys(userId: UserId, chatId: UUID) =
                groupKeys.keys.any { it.first == userId && it.second == chatId }

            override suspend fun countGroupKeys(userId: UserId) = groupKeys.keys.count { it.first == userId }

            override suspend fun listGroupKeys(
                userId: UserId,
                after: GroupKeyPosition?,
                limit: Int,
            ) = groupKeys.values
                .filter { it.userId == userId }
                .sortedWith(compareBy<GroupKeyBackup> { it.chatId }.thenBy { it.metaVersion })
                .filter {
                    after == null || it.chatId > after.chatId ||
                        (it.chatId == after.chatId && it.metaVersion > after.metaVersion)
                }.take(limit)

            override suspend fun findSavedNames(userId: UserId) = savedNames[userId]
        }
    val events = CopyOnWriteArrayList<EventEnvelope>()
    private val lock = Mutex()

    /** One transaction at a time, like the account lock every write takes. */
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = lock.withLock { block() }

    /** No rollback in memory: the use cases under test refuse before they write. */
    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> =
        lock.withLock { block() }

    override suspend fun find(userId: UserId) = accounts[userId]

    override suspend fun lock(userId: UserId) = accounts[userId]

    override suspend fun findByPhone(phone: PhoneNumber) = accounts.values.firstOrNull { it.phone == phone }

    override suspend fun create(account: Account) {
        accounts[account.userId] = account
    }

    override suspend fun supportAccounts(after: UserId?, limit: Int): List<Account> =
        accounts.values
            .filter { it.isSupport && (after == null || it.userId.value > after.value) }
            .sortedBy { it.userId.value }
            .take(limit)

    override suspend fun claimPhone(account: Account, phone: PhoneNumber): Boolean {
        val holder = findByPhone(phone)
        if (holder != null && holder.userId != account.userId) return false
        accounts[account.userId] = account.copy(phone = phone, version = account.version + 1)
        return true
    }

    override suspend fun deleteAbandoned(createdBefore: Instant, limit: Int): Int {
        val due =
            accounts.values
                .filter { it.status == AccountStatus.PENDING_PHONE && it.createdAt < createdBefore }
                .take(limit)
        due.forEach { account ->
            accounts.remove(account.userId)
            links.removeAll { it.userId == account.userId }
        }
        return due.size
    }

    override suspend fun dueForDeletion(now: Instant, limit: Int) =
        accounts.values
            .filter { it.status == AccountStatus.DELETED && it.deleteAfter?.let { at -> at <= now } == true }
            .take(limit)
            .map { it.userId }

    override suspend fun completeDeletion(userId: UserId, now: Instant): Boolean {
        val account = accounts[userId] ?: return false
        if (account.status != AccountStatus.DELETED || account.deleteAfter?.let { it <= now } != true) return false
        accounts.remove(userId)
        links.removeAll { it.userId == userId }
        vaults.remove(userId)
        removeKeyBackup(userId)
        return true
    }

    override suspend fun ownerOf(provider: SocialProvider, subject: String) =
        links.firstOrNull { it.provider == provider && it.subject == subject }?.userId

    override suspend fun claim(link: ProviderLink): Boolean {
        val owner = ownerOf(link.provider, link.subject)
        if (owner != null) return owner == link.userId
        links.removeAll { it.userId == link.userId && it.provider == link.provider }
        links += link
        return true
    }

    override suspend fun links(userId: UserId) = links.filter { it.userId == userId }

    override suspend fun find(userId: UserId, deviceId: DeviceId) = devices[userId to deviceId]

    override suspend fun touch(
        userId: UserId,
        deviceId: DeviceId,
        at: Instant,
    ): Boolean {
        val device = devices[userId to deviceId] ?: return false
        if (device.status != DeviceStatus.ACTIVE || !device.lastSeenAt.isBefore(at)) return false
        devices[userId to deviceId] = device.copy(lastSeenAt = at)
        return true
    }

    override suspend fun find(sessionId: SessionId) = sessions[sessionId]

    override suspend fun revoke(sessionId: SessionId): Session? {
        val session = sessions[sessionId]?.takeIf { it.status == SessionStatus.ACTIVE } ?: return null
        return session.copy(status = SessionStatus.REVOKED).also { sessions[sessionId] = it }
    }

    override suspend fun revokeAll(userId: UserId): List<SessionId> =
        sessions.values
            .filter { it.userId == userId && it.status == SessionStatus.ACTIVE }
            .map { session ->
                session.also { sessions[it.sessionId] = it.copy(status = SessionStatus.REVOKED) }.sessionId
            }

    override suspend fun list(
        userId: UserId,
        before: UUID?,
        limit: Int,
    ) = log
        .filter {
            it.userId == userId
        }.reversed()
        .take(limit)

    override suspend fun find(requestId: UUID) = recoveries[requestId]

    override suspend fun write(changes: Changes) {
        changes.accounts.forEach { account ->
            val holder = account.phone?.let { phone -> findByPhone(phone) }
            check(holder == null || holder.userId == account.userId) { "a number is set only by claimPhone" }
            accounts[account.userId] = account.copy(version = account.version + 1)
        }
        changes.devices.forEach { devices[it.userId to it.deviceId] = it }
        changes.sessions.forEach { sessions[it.sessionId] = it }
        changes.removedProviderLinks.forEach { gone ->
            links.removeAll {
                it.userId == gone.userId &&
                    it.provider == gone.provider
            }
        }
        log += changes.securityEvents
        feedback += changes.deletionFeedback
        changes.recoveries.forEach { recoveries[it.requestId] = it }
        changes.vaults.forEach { vaults[it.userId] = it }
        changes.removedVaults.forEach { vaults.remove(it) }
        changes.keyBackupRoots.forEach { keyBackupRoots[it.userId] = it }
        changes.groupKeys.forEach { groupKeys[Triple(it.userId, it.chatId, it.metaVersion)] = it }
        changes.savedNames.forEach { savedNames[it.userId] = it }
        changes.removedKeyBackups.forEach { removeKeyBackup(it) }
        events += changes.events
    }

    private fun removeKeyBackup(userId: UserId) {
        keyBackupRoots.remove(userId)
        groupKeys.keys.removeIf { it.first == userId }
        savedNames.remove(userId)
    }

    override suspend fun publish(changes: Changes) = Unit

    override suspend fun commit(changes: Changes) {
        run("test.commit") { write(changes) }
        publish(changes)
    }

    fun eventTypes() = events.map { it.type }

    fun active() = accounts.values.filter { it.status == AccountStatus.ACTIVE }
}

/** Messaging's answer to `GroupMembership`, kept in memory: a test adds who is in which chat. */
class MemoryMemberships : GroupMembership {
    val members = CopyOnWriteArrayList<Pair<UUID, UserId>>()
    var asked = 0

    override suspend fun isMember(chatId: UUID, userId: UserId): Boolean {
        asked++
        return (chatId to userId) in members
    }
}

class MemoryCache : IdentityCache {
    val nonces = ConcurrentHashMap.newKeySet<String>()
    val otps = ConcurrentHashMap<String, PendingOtp>()
    val verified = ConcurrentHashMap.newKeySet<Pair<UserId, DeviceId>>()
    val revoked = ConcurrentHashMap.newKeySet<SessionId>()
    val deviceSessions = ConcurrentHashMap<Pair<UserId, DeviceId>, SessionId>()
    val hints = ConcurrentHashMap<UserId, Hints>()
    val locked = ConcurrentHashMap.newKeySet<UserId>()
    val releases = ConcurrentHashMap<String, PhoneRelease>()
    private val counters = ConcurrentHashMap<String, Long>()

    private fun count(key: String) = counters.merge(key, 1L, Long::plus)!!

    override suspend fun saveNonce(nonce: String) {
        nonces += nonce
    }

    override suspend fun consumeNonce(nonce: String) = nonces.remove(nonce)

    override suspend fun saveOtp(verificationId: String, otp: PendingOtp) {
        otps[verificationId] = otp
    }

    override suspend fun otp(verificationId: String) = otps[verificationId]

    override suspend fun recordWrongAttempt(verificationId: String): Long? =
        otps[verificationId]?.let {
            val next = it.copy(attempts = it.attempts + 1)
            otps[verificationId] = next
            next.attempts
        }

    override suspend fun markOtpProven(verificationId: String) {
        otps.computeIfPresent(verificationId) { _, v -> v.copy(proven = true) }
    }

    override suspend fun deleteOtp(verificationId: String) {
        otps.remove(verificationId)
    }

    override suspend fun countCodeRequestForPhone(phoneHmac: String) = count("phone:$phoneHmac")

    override suspend fun countCodeRequestForDevice(deviceId: DeviceId) = count("device:$deviceId")

    override suspend fun codeRequestRetryAfter(phoneHmac: String): Duration? = Duration.ofMinutes(10)

    override suspend fun lockAccountCodes(userId: UserId) {
        locked += userId
    }

    override suspend fun codeLockRemaining(userId: UserId): Duration? =
        if (userId in
            locked
        ) {
            Duration.ofMinutes(10)
        } else {
            null
        }

    override suspend fun markPhoneVerified(userId: UserId, deviceId: DeviceId) {
        verified += userId to deviceId
    }

    override suspend fun isPhoneVerified(userId: UserId, deviceId: DeviceId) = (userId to deviceId) in verified

    override suspend fun revoke(sessionId: SessionId) {
        revoked += sessionId
    }

    override suspend fun isRevoked(sessionId: SessionId) = sessionId in revoked

    override suspend fun rememberDeviceSession(
        userId: UserId,
        deviceId: DeviceId,
        sessionId: SessionId,
    ) {
        deviceSessions[
            userId to
                deviceId,
        ] =
            sessionId
    }

    override suspend fun deviceSession(userId: UserId, deviceId: DeviceId) = deviceSessions[userId to deviceId]

    override suspend fun saveHints(
        userId: UserId,
        displayName: String?,
        avatarUrl: String?,
    ) {
        hints[userId] =
            Hints(displayName, avatarUrl)
    }

    override suspend fun hints(userId: UserId) = hints[userId]

    override suspend fun countRecoveryRequest(phoneHmac: String) = count("recovery:$phoneHmac")

    override suspend fun savePhoneRelease(phoneHmac: String, release: PhoneRelease) {
        releases[phoneHmac] = release
    }

    override suspend fun phoneRelease(phoneHmac: String) = releases[phoneHmac]

    override suspend fun countPhoneRelease(phoneHmac: String) = counters.putIfAbsent("release:$phoneHmac", 1L) == null

    override suspend fun countVaultOpening(userId: UserId) = count("vault-open:$userId")

    override suspend fun vaultOpeningRetryAfter(userId: UserId): Duration? = Duration.ofMinutes(42)
}

class MemoryBus : EventBus {
    val published = CopyOnWriteArrayList<EventEnvelope>()

    override fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>) =
        EventEnvelope.of(
            event,
            eventSerializer,
        )

    override suspend fun publish(envelope: EventEnvelope) {
        published += envelope
    }

    override fun subscribe(subscription: EventSubscription) = Unit
}

@Suppress("unused")
private val keep = Uuid7
