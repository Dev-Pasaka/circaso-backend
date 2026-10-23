package com.circaso.users.data.entities

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.Column
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Sensitive
import io.kandra.core.annotations.Ttl
import io.kandra.core.annotations.UpdatedAt
import io.kandra.core.annotations.Version
import java.time.Instant
import java.util.UUID

const val KS = "circaso_users"

@ScyllaTable(name = "profiles", keyspace = KS)
data class ProfileRow(
    @PartitionKey val userId: UUID,
    val displayName: String,
    val about: String? = null,
    val handle: String? = null,
    val handleChangedAt: Instant? = null,
    val avatarKey: String? = null,
    /** JSON: avatar, about, handleLookup. */
    val privacy: String,
    val createdAt: Instant,
    @UpdatedAt val updatedAt: Instant? = null,
    @Version val version: Long = 0,
)

@ScyllaTable(name = "handle_claims", keyspace = KS)
data class HandleClaimRow(
    @PartitionKey val handle: String,
    val userId: UUID,
    val claimedAt: Instant,
    val releasedAt: Instant? = null,
)

@ScyllaTable(name = "contacts_by_owner", keyspace = KS)
data class ContactRow(
    @PartitionKey val ownerId: UUID,
    @ClusteringKey val contactId: UUID,
    val source: String,
    val status: String = "ACTIVE",
    val removedAt: Instant? = null,
    val addedAt: Instant,
)

@ScyllaTable(name = "contacts_by_target", keyspace = KS)
data class ContactReverseRow(
    @PartitionKey val contactId: UUID,
    @ClusteringKey val ownerId: UUID,
    val addedAt: Instant,
)

@ScyllaTable(name = "waiting_contacts", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 180)
data class WaitingContactRow(
    @PartitionKey @Sensitive val phoneHmac: String,
    @ClusteringKey val ownerId: UUID,
    val addedAt: Instant,
)

@ScyllaTable(name = "blocks_by_user", keyspace = KS)
data class BlockRow(@PartitionKey val userId: UUID, @ClusteringKey val blockedId: UUID, val blockedAt: Instant)

@ScyllaTable(name = "blocked_by_user", keyspace = KS)
data class BlockedByRow(@PartitionKey val blockedId: UUID, @ClusteringKey val userId: UUID, val blockedAt: Instant)

@ScyllaTable(name = "invites", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 7)
data class InviteRow(
    // `token` is a reserved word in CQL, so the column is called invite_token.
    @PartitionKey @Column(name = "invite_token") val token: String,
    val ownerId: UUID,
    val kind: String,
    val createdAt: Instant,
)

/** The frames `users` sent, kept 7 days for [RealtimeReplay]: newest first per user. */
@ScyllaTable(name = "realtime_by_user", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 7)
data class UsersRealtimeRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val seq: Long,
    val frame: String,
)

@ScyllaTable(name = "outbox", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 7)
data class UsersOutboxRow(
    @PartitionKey val bucket: String,
    @ClusteringKey val eventId: UUID,
    val type: String,
    val payload: String,
    val publishedAt: Instant? = null,
)
