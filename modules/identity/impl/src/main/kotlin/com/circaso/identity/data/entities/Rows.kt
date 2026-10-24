package com.circaso.identity.data.entities

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.GeneratedUuid
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Sensitive
import io.kandra.core.annotations.Ttl
import io.kandra.core.annotations.UpdatedAt
import io.kandra.core.annotations.Version
import java.time.Instant
import java.util.UUID

const val KS = "circaso_identity"

@ScyllaTable(name = "accounts_by_id", keyspace = KS)
data class AccountRow(
    @PartitionKey val userId: UUID,
    @Sensitive val phone: String? = null,
    val status: String,
    val activeDeviceId: UUID? = null,
    val verification: String = "NONE",
    val sessionVersion: Int = 1,
    val createdAt: Instant,
    @UpdatedAt val updatedAt: Instant? = null,
    @Version val version: Long = 0,
)

@ScyllaTable(name = "phone_claims", keyspace = KS)
data class PhoneClaimRow(
    @PartitionKey @Sensitive val phone: String,
    val userId: UUID,
    val claimedAt: Instant,
)

@ScyllaTable(name = "provider_claims", keyspace = KS)
data class ProviderClaimRow(
    @PartitionKey(index = 0) val provider: String,
    @PartitionKey(index = 1) @Sensitive val subject: String,
    val userId: UUID,
    val linkedAt: Instant,
)

@ScyllaTable(name = "providers_by_user", keyspace = KS)
data class ProviderLinkRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey val provider: String,
    @Sensitive val subject: String,
    @Sensitive val email: String?,
    val linkedAt: Instant,
)

@ScyllaTable(name = "devices_by_user", keyspace = KS)
data class DeviceRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey val deviceId: UUID,
    val platform: String,
    val model: String,
    val appVersion: String,
    val publicKey: String,
    val keyAlgorithm: String,
    @Sensitive val pushToken: String? = null,
    val status: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant,
)

@ScyllaTable(name = "sessions_by_id", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 30)
data class SessionRow(
    @PartitionKey val sessionId: UUID,
    val userId: UUID,
    val deviceId: UUID,
    @Sensitive val currentTokenHash: String,
    val scope: String,
    val status: String,
    val createdAt: Instant,
    val rotatedAt: Instant,
)

@ScyllaTable(name = "security_log_by_user", keyspace = KS)
data class SecurityEventRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) @GeneratedUuid val eventId: UUID? = null,
    val type: String,
    val deviceModel: String? = null,
    val detail: String? = null,
)

@ScyllaTable(name = "support_recoveries", keyspace = KS)
data class SupportRecoveryRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val requestId: UUID,
    val status: String,
    @Sensitive val contactEmail: String,
    val note: String? = null,
    val idCheck: String? = null,
    val selfieMatch: String? = null,
    val agentId: String? = null,
    val noticeSentAt: Instant? = null,
    val relinkAfter: Instant? = null,
    val newProvider: String? = null,
    @Sensitive val newSubject: String? = null,
)

// Agents open a case by request id. Kandra's @LookupIndex can't sit on a clustering key (its lookup table would
// repeat the column), so this is a table of our own, written in the same batch as the case.
@ScyllaTable(name = "support_recoveries_by_request", keyspace = KS)
data class RecoveryByRequestRow(
    @PartitionKey val requestId: UUID,
    val userId: UUID,
)

@ScyllaTable(name = "outbox", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 7)
data class IdentityOutboxRow(
    @PartitionKey val bucket: String,
    @ClusteringKey val eventId: UUID,
    val type: String,
    val payload: String,
    val publishedAt: Instant? = null,
)
