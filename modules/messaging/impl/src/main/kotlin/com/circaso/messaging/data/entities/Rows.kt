package com.circaso.messaging.data.entities

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Sensitive
import io.kandra.core.annotations.Ttl
import java.time.Instant
import java.util.UUID

const val KS = "circaso_messaging"

/** The frames `messaging` sent, kept 7 days for its `RealtimeReplay` (MS4): newest first per user. */
@ScyllaTable(name = "realtime_by_user", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 7)
data class MessagingRealtimeRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val seq: Long,
    val frame: String,
)

@ScyllaTable(name = "outbox", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 7)
data class MessagingOutboxRow(
    @PartitionKey val bucket: String,
    @ClusteringKey val eventId: UUID,
    val type: String,
    val payload: String,
    val publishedAt: Instant? = null,
)

/**
 * One key set per account (one phone per account): the identity key and the current signed prekey. Public keys
 * only, but marked sensitive so no log line ever carries them. [deviceId] is the phone that published the set.
 */
@ScyllaTable(name = "key_bundles", keyspace = KS)
data class KeyBundleRow(
    @PartitionKey val userId: UUID,
    val deviceId: UUID,
    @Sensitive val identityKey: String,
    val signedPreKeyId: Int,
    @Sensitive val signedPreKey: String,
    @Sensitive val signedPreKeySignature: String,
    val signedPreKeyAt: Instant,
)

/** M3 takes the first row and deletes it with a lightweight transaction, so two fetches never get the same key. */
@ScyllaTable(name = "one_time_prekeys", keyspace = KS)
data class OneTimePreKeyRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey val keyId: Int,
    @Sensitive val publicKey: String,
)
