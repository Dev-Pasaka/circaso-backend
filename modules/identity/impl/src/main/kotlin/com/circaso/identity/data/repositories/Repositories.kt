package com.circaso.identity.data.repositories

import com.circaso.identity.api.SessionId
import com.circaso.identity.data.entities.AccountRow
import com.circaso.identity.data.entities.DeviceRow
import com.circaso.identity.data.entities.IdentityOutboxRow
import com.circaso.identity.data.entities.PhoneClaimRow
import com.circaso.identity.data.entities.ProviderClaimRow
import com.circaso.identity.data.entities.ProviderLinkRow
import com.circaso.identity.data.entities.RecoveryByRequestRow
import com.circaso.identity.data.entities.SecurityEventRow
import com.circaso.identity.data.entities.SessionRow
import com.circaso.identity.data.entities.SupportRecoveryRow
import com.circaso.identity.data.mappers.toModel
import com.circaso.identity.data.mappers.toRow
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.repositories.RecoveryRepository
import com.circaso.identity.domain.repositories.SecurityLogRepository
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.infrastructure.api.BucketedOutboxScan
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import io.kandra.core.ExperimentalKandraApi
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.repository.KandraSuspendRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

// Column names Kandra derives from the entity properties (camelCase to snake_case).
private val USER_ID = KandraColumnRef<UUID>("user_id")
private val EVENT_ID = KandraColumnRef<UUID>("event_id")
private val BUCKET = KandraColumnRef<String>("bucket")

class AccountRepositoryImpl(
    private val accounts: KandraSuspendRepository<AccountRow>,
    private val claims: KandraSuspendRepository<PhoneClaimRow>,
) : AccountRepository {
    override suspend fun find(userId: UserId): Account? = accounts.findById(userId.value)?.toModel()

    override suspend fun findByPhone(phone: PhoneNumber): Account? =
        claims.findById(phone.e164)?.let { find(UserId(it.userId)) }

    override suspend fun claimPhone(phone: PhoneNumber, userId: UserId): Boolean =
        claims.saveIfNotExists(PhoneClaimRow(phone.e164, userId.value, Instant.now())) ||
            claims.findById(phone.e164)?.userId == userId.value

    override suspend fun releasePhone(phone: PhoneNumber) = claims.deleteById(phone.e164)
}

class ProviderRepositoryImpl(
    private val claims: KandraSuspendRepository<ProviderClaimRow>,
    private val links: KandraSuspendRepository<ProviderLinkRow>,
) : ProviderRepository {
    override suspend fun ownerOf(provider: SocialProvider, subject: String): UserId? =
        claims.findById(provider.name.lowercase(), subject)?.let { UserId(it.userId) }

    override suspend fun claim(
        provider: SocialProvider,
        subject: String,
        userId: UserId,
    ): Boolean =
        claims.saveIfNotExists(ProviderClaimRow(provider.name.lowercase(), subject, userId.value, Instant.now())) ||
            ownerOf(provider, subject) == userId

    override suspend fun links(userId: UserId): List<ProviderLink> =
        links.findAll { USER_ID eq userId.value }.map { it.toModel() }
}

class DeviceRepositoryImpl(private val devices: KandraSuspendRepository<DeviceRow>) : DeviceRepository {
    override suspend fun find(userId: UserId, deviceId: DeviceId): Device? =
        devices.findById(userId.value, deviceId.value)?.toModel()
}

class SessionRepositoryImpl(private val sessions: KandraSuspendRepository<SessionRow>) : SessionRepository {
    override suspend fun find(sessionId: SessionId): Session? = sessions.findById(sessionId.value)?.toModel()
}

class SecurityLogRepositoryImpl(private val log: KandraSuspendRepository<SecurityEventRow>) : SecurityLogRepository {
    override suspend fun list(
        userId: UserId,
        before: UUID?,
        limit: Int,
    ): List<SecurityEvent> =
        log
            .findAll(limit) {
                USER_ID eq userId.value
                if (before != null) EVENT_ID lt before
            }.map { it.toModel() }
}

class RecoveryRepositoryImpl(
    private val recoveries: KandraSuspendRepository<SupportRecoveryRow>,
    private val byRequest: KandraSuspendRepository<RecoveryByRequestRow>,
) : RecoveryRepository {
    override suspend fun find(requestId: UUID): SupportRecovery? {
        val owner = byRequest.findById(requestId) ?: return null
        return recoveries.findById(owner.userId, requestId)?.toModel()
    }
}

/**
 * Writes one operation's rows and its outbox rows in a single LOGGED batch, so both land or neither does, then
 * hands the events to the bus. A claim (`saveIfNotExists`) always happens before, because an LWT can't join a batch.
 */
@OptIn(ExperimentalKandraApi::class)
class IdentityStoreImpl(
    private val runtime: KandraRuntime,
    private val accounts: KandraSuspendRepository<AccountRow>,
    private val devices: KandraSuspendRepository<DeviceRow>,
    private val sessions: KandraSuspendRepository<SessionRow>,
    private val links: KandraSuspendRepository<ProviderLinkRow>,
    private val log: KandraSuspendRepository<SecurityEventRow>,
    private val recoveries: KandraSuspendRepository<SupportRecoveryRow>,
    private val recoveriesByRequest: KandraSuspendRepository<RecoveryByRequestRow>,
    private val outbox: KandraSuspendRepository<IdentityOutboxRow>,
    private val providerClaims: KandraSuspendRepository<ProviderClaimRow>,
    private val bus: EventBus,
) : IdentityStore {
    override suspend fun commit(changes: Changes) {
        runtime.batch {
            changes.accounts.forEach { accounts.saveInBatch(it.toRow()) }
            changes.devices.forEach { devices.saveInBatch(it.toRow()) }
            changes.sessions.forEach { sessions.saveInBatch(it.toRow()) }
            changes.providerLinks.forEach { links.saveInBatch(it.toRow()) }
            changes.removedProviderLinks.forEach { links.deleteInBatch(it.toRow()) }
            changes.securityEvents.forEach { log.saveInBatch(it.toRow()) }
            changes.recoveries.forEach {
                recoveries.saveInBatch(it.toRow())
                recoveriesByRequest.saveInBatch(RecoveryByRequestRow(it.requestId, it.userId.value))
            }
            changes.events.forEach { outbox.saveInBatch(it.toOutboxRow()) }
        }
        changes.events.forEach { bus.publish(it) }
    }

    override suspend fun releaseProvider(provider: SocialProvider, subject: String) {
        providerClaims.deleteById(provider.name.lowercase(), subject)
    }
}

private fun EventEnvelope.toOutboxRow() = IdentityOutboxRow(bucket, eventId.value, type, toJson())

/** The relay's view of identity's outbox: events written but not yet on the stream. */
class IdentityOutboxStore(
    private val outbox: KandraSuspendRepository<IdentityOutboxRow>,
    clock: Clock = Clock.systemUTC(),
) : OutboxStore {
    override val producer = "identity"

    // The rows are keyed by minute-bucket; the bucket-less relay walks them through this until the outbox moves to
    // PostgreSQL.
    private val scan =
        BucketedOutboxScan(clock) { bucket ->
            outbox.findAll { BUCKET eq bucket }.filter { it.publishedAt == null }.map {
                EventEnvelope.fromJson(
                    it.payload,
                )
            }
        }

    override suspend fun unpublished(limit: Int): List<EventEnvelope> = scan.unpublished(limit)

    override suspend fun markPublished(envelope: EventEnvelope, at: Instant) {
        val row = outbox.findById(envelope.bucket, envelope.eventId.value) ?: return
        outbox.save(row.copy(publishedAt = at))
    }
}
