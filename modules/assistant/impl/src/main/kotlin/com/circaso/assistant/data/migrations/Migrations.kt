package com.circaso.assistant.data.migrations

import com.circaso.assistant.data.entities.AshaMessageRow
import com.circaso.assistant.data.entities.AssistantAuditRow
import com.circaso.assistant.data.entities.AssistantOutboxRow
import com.circaso.assistant.data.entities.EntitlementRow
import com.circaso.assistant.data.entities.UsageDailyRow
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigration

val assistantEntities = listOf(AshaMessageRow::class, UsageDailyRow::class, EntitlementRow::class, AssistantAuditRow::class, AssistantOutboxRow::class)

/**
 * The first schema. Tables name their keyspace and use IF NOT EXISTS. Never edit it once applied; changes go in V002
 * and later. Phase B adds the proposals table.
 */
object V001_CreateAssistantTables : KandraMigration(version = 1, name = "create assistant tables") {
    override fun up(session: CqlSession) {
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_assistant.asha_messages_by_user (user_id UUID, message_id UUID, answered_by UUID, asked_from TEXT, body TEXT, created_at TIMESTAMP, read_note TEXT, reply_to UUID, role TEXT, PRIMARY KEY (user_id, message_id)) WITH CLUSTERING ORDER BY (message_id DESC)",
            "CREATE TABLE IF NOT EXISTS circaso_assistant.usage_daily_by_user (user_id UUID, usage_day TEXT, input_tokens BIGINT, output_tokens BIGINT, text_turns INT, updated_at TIMESTAMP, voice_seconds INT, PRIMARY KEY (user_id, usage_day)) WITH CLUSTERING ORDER BY (usage_day DESC)",
            "CREATE TABLE IF NOT EXISTS circaso_assistant.entitlements_by_user (user_id UUID, expires_at TIMESTAMP, plan TEXT, PRIMARY KEY (user_id))",
            "CREATE TABLE IF NOT EXISTS circaso_assistant.audit_by_user (user_id UUID, event_id UUID, detail TEXT, kind TEXT, request_id TEXT, PRIMARY KEY (user_id, event_id)) WITH CLUSTERING ORDER BY (event_id DESC)",
            "CREATE TABLE IF NOT EXISTS circaso_assistant.outbox (bucket TEXT, event_id UUID, payload TEXT, published_at TIMESTAMP, type TEXT, PRIMARY KEY (bucket, event_id)) WITH CLUSTERING ORDER BY (event_id ASC) AND default_time_to_live = 604800",
        ).forEach { session.execute(it) }
    }
}
