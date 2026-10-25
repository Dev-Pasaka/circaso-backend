package com.circaso.messaging.data.entities

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
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
