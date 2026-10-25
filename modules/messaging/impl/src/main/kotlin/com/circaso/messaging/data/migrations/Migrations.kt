package com.circaso.messaging.data.migrations

import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.MessagingRealtimeRow
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigration

val messagingEntities =
    listOf(
        MessagingRealtimeRow::class,
        MessagingOutboxRow::class,
    )

/**
 * The first schema: the outbox and the replay log every module that publishes and sends frames has. The chat, key,
 * inbox, media and history tables come with their slices (MS2 onwards) as V002 and later. Never edit it once
 * applied.
 */
object V001_CreateMessagingTables : KandraMigration(version = 1, name = "create messaging tables") {
    override fun up(session: CqlSession) {
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_messaging.realtime_by_user (user_id UUID, seq BIGINT, frame TEXT, " +
                "PRIMARY KEY (user_id, seq)) WITH CLUSTERING ORDER BY (seq DESC) AND default_time_to_live = 604800",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.outbox (bucket TEXT, event_id UUID, payload TEXT, " +
                "published_at TIMESTAMP, type TEXT, PRIMARY KEY (bucket, event_id)) WITH CLUSTERING ORDER BY " +
                "(event_id ASC) AND default_time_to_live = 604800",
        ).forEach { session.execute(it) }
    }
}
