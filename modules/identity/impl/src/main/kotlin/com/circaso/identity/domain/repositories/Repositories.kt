package com.circaso.identity.domain.repositories

import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import java.util.UUID

interface AccountRepository {
    suspend fun find(userId: UserId): Account?

    /** The account that has claimed [phone], if any. */
    suspend fun findByPhone(phone: PhoneNumber): Account?

    /** `saveIfNotExists` on the claim table: true if [phone] is now claimed by [userId] (including already was). */
    suspend fun claimPhone(phone: PhoneNumber, userId: UserId): Boolean

    suspend fun releasePhone(phone: PhoneNumber)
}

interface ProviderRepository {
    /** The account that owns a Google/Apple login. */
    suspend fun ownerOf(provider: SocialProvider, subject: String): UserId?

    /** True if the login is now claimed by [userId] (including already was). */
    suspend fun claim(provider: SocialProvider, subject: String, userId: UserId): Boolean

    suspend fun links(userId: UserId): List<ProviderLink>
}

interface DeviceRepository {
    suspend fun find(userId: UserId, deviceId: DeviceId): Device?
}

interface SessionRepository {
    suspend fun find(sessionId: SessionId): Session?
}

interface SecurityLogRepository {
    /** Newest first; [before] is the last event id of the previous page. */
    suspend fun list(userId: UserId, before: UUID?, limit: Int): List<SecurityEvent>
}

interface RecoveryRepository {
    suspend fun find(requestId: UUID): SupportRecovery?
}

/** Everything one operation changes, written in one batch together with its outbox rows. */
data class Changes(
    val accounts: List<Account> = emptyList(),
    val devices: List<Device> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val providerLinks: List<ProviderLink> = emptyList(),
    val removedProviderLinks: List<ProviderLink> = emptyList(),
    val securityEvents: List<SecurityEvent> = emptyList(),
    val recoveries: List<SupportRecovery> = emptyList(),
    /** Written to the outbox in the same batch, then published once it succeeds. */
    val events: List<EventEnvelope> = emptyList(),
)

interface IdentityStore {
    /** Writes [changes] atomically, then publishes their events. A claim (`claimPhone`, `claim`) happens before this. */
    suspend fun commit(changes: Changes)

    /** Removes a provider's claim after its link was deleted. */
    suspend fun releaseProvider(provider: SocialProvider, subject: String)
}
