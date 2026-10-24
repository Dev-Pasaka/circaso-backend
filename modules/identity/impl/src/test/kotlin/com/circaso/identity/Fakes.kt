package com.circaso.identity

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.ports.Hints
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PendingOtp
import com.circaso.identity.domain.ports.PhoneRelease
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.repositories.RecoveryRepository
import com.circaso.identity.domain.repositories.SecurityLogRepository
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventSubscription
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.serialization.KSerializer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One in-memory stand-in for every repository port, so use cases run without a database. */
class MemoryStore :
    AccountRepository,
    ProviderRepository,
    DeviceRepository,
    SessionRepository,
    SecurityLogRepository,
    RecoveryRepository,
    IdentityStore {
    val accounts = ConcurrentHashMap<UserId, Account>()
    val phoneClaims = ConcurrentHashMap<PhoneNumber, UserId>()
    val providerClaims = ConcurrentHashMap<Pair<SocialProvider, String>, UserId>()
    val links = CopyOnWriteArrayList<ProviderLink>()
    val devices = ConcurrentHashMap<Pair<UserId, DeviceId>, Device>()
    val sessions = ConcurrentHashMap<SessionId, Session>()
    val log = CopyOnWriteArrayList<SecurityEvent>()
    val recoveries = ConcurrentHashMap<UUID, SupportRecovery>()
    val events = CopyOnWriteArrayList<EventEnvelope>()

    override suspend fun find(userId: UserId) = accounts[userId]

    override suspend fun findByPhone(phone: PhoneNumber) = phoneClaims[phone]?.let { accounts[it] }

    override suspend fun claimPhone(phone: PhoneNumber, userId: UserId) =
        phoneClaims.putIfAbsent(phone, userId).let {
            it ==
                null ||
                it == userId
        }

    override suspend fun releasePhone(phone: PhoneNumber) {
        phoneClaims.remove(phone)
    }

    override suspend fun ownerOf(provider: SocialProvider, subject: String) = providerClaims[provider to subject]

    override suspend fun claim(
        provider: SocialProvider,
        subject: String,
        userId: UserId,
    ) = providerClaims.putIfAbsent(provider to subject, userId).let { it == null || it == userId }

    override suspend fun links(userId: UserId) = links.filter { it.userId == userId }

    override suspend fun find(userId: UserId, deviceId: DeviceId) = devices[userId to deviceId]

    override suspend fun find(sessionId: SessionId) = sessions[sessionId]

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

    override suspend fun commit(changes: Changes) {
        changes.accounts.forEach { accounts[it.userId] = it }
        changes.devices.forEach { devices[it.userId to it.deviceId] = it }
        changes.sessions.forEach { sessions[it.sessionId] = it }
        changes.providerLinks.forEach { link ->
            links.removeAll {
                it.userId == link.userId &&
                    it.provider == link.provider
            }
            ; links += link
        }
        changes.removedProviderLinks.forEach { gone ->
            links.removeAll {
                it.userId == gone.userId &&
                    it.provider == gone.provider
            }
        }
        log += changes.securityEvents
        changes.recoveries.forEach { recoveries[it.requestId] = it }
        events += changes.events
    }

    override suspend fun releaseProvider(provider: SocialProvider, subject: String) {
        providerClaims.remove(provider to subject)
    }

    fun eventTypes() = events.map { it.type }

    fun active() = accounts.values.filter { it.status == AccountStatus.ACTIVE }
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
