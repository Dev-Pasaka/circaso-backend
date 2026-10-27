package com.circaso.messaging.data.migrations

import com.circaso.messaging.data.entities.ChatRow
import com.circaso.messaging.data.entities.ClientMessageRow
import com.circaso.messaging.data.entities.DirectChatClaimRow
import com.circaso.messaging.data.entities.GroupMessageRow
import com.circaso.messaging.data.entities.InboxRow
import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.MemberRow
import com.circaso.messaging.data.entities.MessageRecordRow
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.MessagingRealtimeRow
import com.circaso.messaging.data.entities.MessagingSettingsRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.data.entities.UserChatRow
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import io.kandra.migrate.KandraMigration

val messagingEntities =
    listOf(
        MessagingRealtimeRow::class,
        MessagingOutboxRow::class,
        KeyBundleRow::class,
        OneTimePreKeyRow::class,
        ChatRow::class,
        DirectChatClaimRow::class,
        MemberRow::class,
        UserChatRow::class,
        InboxRow::class,
        GroupMessageRow::class,
        MessageRecordRow::class,
        ClientMessageRow::class,
        MessagingSettingsRow::class,
    )

/**
 * The first schema: the outbox and the replay log every module that publishes and sends frames has. The chat, key,
 * inbox, media and history tables come with their slices (MS2 onwards) as V002 and later. Never edit it once
 * applied.
 */
object V001_CreateMessagingTables : KandraMigration(version = 1, name = "create messaging tables") {
    // Run with a plain loop, never forEach or another inline call: an inline call leaves Kotlin's line table in
    // the class, and the checksum would then change whenever these lines move (docs/kandra-bugs.md #5).
    private val statements =
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_messaging.realtime_by_user (user_id UUID, seq BIGINT, frame TEXT, " +
                "PRIMARY KEY (user_id, seq)) WITH CLUSTERING ORDER BY (seq DESC) AND default_time_to_live = 604800",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.outbox (bucket TEXT, event_id UUID, payload TEXT, " +
                "published_at TIMESTAMP, type TEXT, PRIMARY KEY (bucket, event_id)) WITH CLUSTERING ORDER BY " +
                "(event_id ASC) AND default_time_to_live = 604800",
        )

    override fun up(session: CqlSession) {
        for (statement in statements) {
            session.execute(statement)
        }
    }
}

/** The key directory (MS2): one bundle per account and its one-time prekeys. Never edit it once applied. */
object V002_CreateKeyDirectoryTables : KandraMigration(version = 2, name = "create key directory tables") {
    // A plain loop, as in V001 (docs/kandra-bugs.md #5).
    private val statements =
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_messaging.key_bundles (user_id UUID, device_id UUID, identity_key " +
                "TEXT, signed_pre_key TEXT, signed_pre_key_at TIMESTAMP, signed_pre_key_id INT, " +
                "signed_pre_key_signature TEXT, PRIMARY KEY (user_id))",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.one_time_prekeys (user_id UUID, key_id INT, public_key " +
                "TEXT, PRIMARY KEY (user_id, key_id)) WITH CLUSTERING ORDER BY (key_id ASC)",
        )

    override fun up(session: CqlSession) {
        for (statement in statements) {
            session.execute(statement)
        }
    }
}

/** Chats and membership (MS3): the chat, the direct-chat pair claim, members and each user's chat list. */
object V003_CreateChatTables : KandraMigration(version = 3, name = "create chat tables") {
    // A plain loop, as in V001 (docs/kandra-bugs.md #5).
    private val statements =
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_messaging.chats (chat_id UUID, avatar_media_id UUID, created_at " +
                "TIMESTAMP, created_by UUID, kind TEXT, member_count INT, meta_version INT, sealed_meta TEXT, " +
                "settings TEXT, version BIGINT, PRIMARY KEY (chat_id))",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.direct_chats_by_pair (pair TEXT, chat_id UUID, " +
                "PRIMARY KEY (pair))",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.members_by_chat (chat_id UUID, user_id UUID, joined_at " +
                "TIMESTAMP, role TEXT, PRIMARY KEY (chat_id, user_id)) WITH CLUSTERING ORDER BY (user_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.chats_by_user (user_id UUID, chat_id UUID, joined_at " +
                "TIMESTAMP, kind TEXT, last_message_id UUID, read_up_to UUID, PRIMARY KEY (user_id, chat_id)) " +
                "WITH CLUSTERING ORDER BY (chat_id ASC)",
        )

    override fun up(session: CqlSession) {
        for (statement in statements) {
            session.execute(statement)
        }
    }
}

/**
 * The group owner on the chat row (decision 2026-10-26), filled for groups made before it from their OWNER member
 * row. A one-off read of every chat: tables are designed around queries, but a migration runs once. The column is
 * added only if missing, because a local run may have created the table from the entity already. Never edit it once
 * applied.
 */
object V004_AddChatOwner : KandraMigration(version = 4, name = "add chat owner") {
    private const val HAS_OWNER_COLUMN =
        "SELECT column_name FROM system_schema.columns WHERE keyspace_name = 'circaso_messaging' AND " +
            "table_name = 'chats' AND column_name = 'owner_id'"
    private const val ADD_OWNER_COLUMN = "ALTER TABLE circaso_messaging.chats ADD owner_id UUID"
    private const val CHATS = "SELECT chat_id, kind FROM circaso_messaging.chats"
    private const val MEMBERS = "SELECT user_id, role FROM circaso_messaging.members_by_chat WHERE chat_id = ?"
    private const val SET_OWNER = "UPDATE circaso_messaging.chats SET owner_id = ? WHERE chat_id = ?"

    // Plain loops and ifs only, no inline calls, as in V001 (docs/kandra-bugs.md #5).
    override fun up(session: CqlSession) {
        if (session.execute(HAS_OWNER_COLUMN).one() == null) {
            session.execute(ADD_OWNER_COLUMN)
        }
        for (chat in session.execute(CHATS)) {
            if (chat.getString("kind") != "GROUP") {
                continue
            }
            val chatId = chat.getUuid("chat_id")
            for (member in session.execute(SimpleStatement.newInstance(MEMBERS, chatId))) {
                if (member.getString("role") == "OWNER") {
                    val owner =
                        SimpleStatement.newInstance(
                            SET_OWNER,
                            member.getUuid("user_id"),
                            chatId,
                        )
                    session.execute(owner)
                }
            }
        }
    }
}

/**
 * Sending and delivery (MS4): each recipient's inbox, group message bodies, who sent each message, the
 * idempotent-send claims and the receipt and typing settings. The inbox, bodies and records live 30 days and the
 * claims 24 hours. Never edit it once applied.
 */
object V005_CreateDeliveryTables : KandraMigration(version = 5, name = "create delivery tables") {
    // A plain loop, as in V001 (docs/kandra-bugs.md #5).
    private val statements =
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_messaging.inbox_by_user (user_id UUID, message_id UUID, chat_id " +
                "UUID, ciphertext TEXT, group_body BOOLEAN, kind TEXT, media_ids TEXT, sender_id UUID, sent_at " +
                "TIMESTAMP, PRIMARY KEY (user_id, message_id)) WITH CLUSTERING ORDER BY (message_id ASC) AND " +
                "default_time_to_live = 2592000",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.group_messages (chat_id UUID, message_id UUID, " +
                "ciphertext TEXT, sender_id UUID, sent_at TIMESTAMP, PRIMARY KEY (chat_id, message_id)) WITH " +
                "CLUSTERING ORDER BY (message_id ASC) AND default_time_to_live = 2592000",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.messages_by_id (message_id UUID, chat_id UUID, kind " +
                "TEXT, sender_id UUID, sent_at TIMESTAMP, PRIMARY KEY (message_id)) WITH default_time_to_live = " +
                "2592000",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.sent_by_client_id (sender_id UUID, client_message_id " +
                "UUID, complete BOOLEAN, message_id UUID, PRIMARY KEY (sender_id, client_message_id)) WITH " +
                "CLUSTERING ORDER BY (client_message_id ASC) AND default_time_to_live = 86400",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.settings (user_id UUID, read_receipts BOOLEAN, typing " +
                "BOOLEAN, PRIMARY KEY (user_id))",
        )

    override fun up(session: CqlSession) {
        for (statement in statements) {
            session.execute(statement)
        }
    }
}
