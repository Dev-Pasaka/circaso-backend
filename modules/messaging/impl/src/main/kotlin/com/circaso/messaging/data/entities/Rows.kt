package com.circaso.messaging.data.entities

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Sensitive
import io.kandra.core.annotations.Ttl
import io.kandra.core.annotations.Version
import java.time.Instant
import java.util.UUID

const val KS = "circaso_messaging"

/** Stands for "none" in a UUID column: a null through Kandra's save leaves the old value (docs/kandra-bugs.md). */
val NIL_UUID: UUID = UUID(0, 0)

/** The frames `messaging` sent that are replayed, kept 7 days for its `RealtimeReplay`: newest first per user. */
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

/**
 * One chat, direct or group. Every write after the first goes through [version] (Kandra's `IF version = ?`), the
 * member count included, so a settings change can never overwrite a count written in between, and the 1,000 cap
 * holds under concurrent adds.
 */
@ScyllaTable(name = "chats", keyspace = KS)
data class ChatRow(
    @PartitionKey val chatId: UUID,
    /** `DIRECT` or `GROUP`. */
    val kind: String,
    /** Groups only: name and avatar sealed on the phone under the group metadata key; empty for direct. */
    val sealedMeta: String = "",
    val metaVersion: Int = 0,
    /** The encrypted avatar file (chat media, MS5); the nil UUID until one is set. */
    val avatarMediaId: UUID = NIL_UUID,
    /** JSON: `membersCanAdd`. */
    val settings: String,
    val memberCount: Int,
    val createdBy: UUID,
    val createdAt: Instant,
    @Version val version: Long = 0,
    /** Groups only: the owner, so a change of owner goes through [version] (V004); the nil UUID for direct. */
    val ownerId: UUID = NIL_UUID,
)

/** One direct chat per pair, claimed with `saveIfNotExists`, so two people opening it at once get the same one. */
@ScyllaTable(name = "direct_chats_by_pair", keyspace = KS)
data class DirectChatClaimRow(
    /** `<smaller userId>:<larger userId>`. */
    @PartitionKey val pair: String,
    val chatId: UUID,
)

@ScyllaTable(name = "members_by_chat", keyspace = KS)
data class MemberRow(
    @PartitionKey val chatId: UUID,
    @ClusteringKey val userId: UUID,
    /** `OWNER`, `ADMIN` or `MEMBER`. */
    val role: String,
    val joinedAt: Instant,
)

/** The chat list; written with its [MemberRow] in one batch. */
@ScyllaTable(name = "chats_by_user", keyspace = KS)
data class UserChatRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey val chatId: UUID,
    val kind: String,
    /** Sentinel, not null: a null through Kandra's save doesn't clear (docs/kandra-bugs.md). */
    val lastMessageId: UUID = NIL_UUID,
    val readUpTo: UUID = NIL_UUID,
    val joinedAt: Instant,
)

/**
 * A message waiting for one recipient, deleted on their ack and after 30 days at the latest. Ascending ids: oldest
 * first. A direct or `SENDER_KEY` row holds this recipient's own [ciphertext]; a group message's row is a pointer
 * ([groupBody], empty [ciphertext]) to its [GroupMessageRow]. [mediaIds] are comma-separated, empty for none.
 */
@ScyllaTable(name = "inbox_by_user", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 30)
data class InboxRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey val messageId: UUID,
    val chatId: UUID,
    val senderId: UUID,
    /** `MESSAGE`, `SENDER_KEY` or `CONTROL`. */
    val kind: String,
    @Sensitive val ciphertext: String = "",
    val groupBody: Boolean = false,
    val mediaIds: String = "",
    val sentAt: Instant,
)

/** One ciphertext per group message, not one per member; deleted with the last member's ack, or after 30 days. */
@ScyllaTable(name = "group_messages", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 30)
data class GroupMessageRow(
    @PartitionKey val chatId: UUID,
    @ClusteringKey val messageId: UUID,
    val senderId: UUID,
    @Sensitive val ciphertext: String,
    val sentAt: Instant,
)

/**
 * Who sent what, written with every send and kept 30 days, so M14 can check the sender and a group read receipt can
 * find them after every copy has been delivered and deleted (decision 2026-10-27).
 */
@ScyllaTable(name = "messages_by_id", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 30)
data class MessageRecordRow(
    @PartitionKey val messageId: UUID,
    val chatId: UUID,
    val senderId: UUID,
    /** `MESSAGE`, `SENDER_KEY` or `CONTROL`. */
    val kind: String,
    val sentAt: Instant,
)

/**
 * Idempotent send: the message id a sender's `clientMessageId` was given, claimed with `saveIfNotExists`, for 24 h,
 * and whether the send finished writing (decision 2026-10-27).
 */
@ScyllaTable(name = "sent_by_client_id", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24)
data class ClientMessageRow(
    @PartitionKey val senderId: UUID,
    @ClusteringKey val clientMessageId: UUID,
    val messageId: UUID,
    /** Set once every row of the send is written; a retry that finds it unset writes them again under [messageId]. */
    val complete: Boolean = false,
)

/** M22: read receipts and typing indicators; no row means both on. */
@ScyllaTable(name = "settings", keyspace = KS)
data class MessagingSettingsRow(
    @PartitionKey val userId: UUID,
    val readReceipts: Boolean = true,
    val typing: Boolean = true,
)
