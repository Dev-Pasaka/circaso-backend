package com.circaso.assistant.data.entities

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.GeneratedUuid
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Sensitive
import io.kandra.core.annotations.Ttl
import java.time.Instant
import java.util.UUID

const val KS = "circaso_assistant"

/** The Asha chat, newest first. The text is sealed (AES-GCM) before it is stored. */
@ScyllaTable(name = "asha_messages_by_user", keyspace = KS)
data class AshaMessageRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val messageId: UUID,
    val role: String,
    @Sensitive val body: String,
    val replyTo: UUID? = null,
    val answeredBy: UUID? = null,
    val askedFrom: String? = null,
    /** JSON: the chat ids and the number of messages Asha read, never the messages. */
    val readNote: String? = null,
    val createdAt: Instant,
)

@ScyllaTable(name = "usage_daily_by_user", keyspace = KS)
data class UsageDailyRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val usageDay: String,
    val textTurns: Int,
    val voiceSeconds: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val updatedAt: Instant,
)

@ScyllaTable(name = "entitlements_by_user", keyspace = KS)
data class EntitlementRow(
    @PartitionKey val userId: UUID,
    val plan: String,
    val expiresAt: Instant? = null,
)

/** Append-only. Says what happened (the turn, its tools, its size), never what was said. */
@ScyllaTable(name = "audit_by_user", keyspace = KS)
data class AssistantAuditRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) @GeneratedUuid val eventId: UUID? = null,
    val kind: String,
    val detail: String,
    val requestId: String? = null,
)

@ScyllaTable(name = "outbox", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 7)
data class AssistantOutboxRow(
    @PartitionKey val bucket: String,
    @ClusteringKey val eventId: UUID,
    val type: String,
    val payload: String,
    val publishedAt: Instant? = null,
)
