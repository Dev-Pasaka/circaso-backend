package com.circaso.messaging.data.migrations

import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.MessagingRealtimeRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigration

val messagingEntities =
    listOf(
        MessagingRealtimeRow::class,
        MessagingOutboxRow::class,
        KeyBundleRow::class,
        OneTimePreKeyRow::class,
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
