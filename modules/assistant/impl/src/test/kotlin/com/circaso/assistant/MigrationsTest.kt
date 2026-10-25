package com.circaso.assistant

import com.circaso.assistant.data.migrations.V001_CreateAssistantTables
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import io.kandra.test.FakeKandraSession
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the CQL each applied migration runs. An applied migration must never change (its checksum would stop the
 * deploy, see docs/kandra-bugs.md #5), so a change here has to be made on purpose, in both places.
 */
class MigrationsTest {
    private val v001 =
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_assistant.asha_messages_by_user (user_id UUID, message_id UUID, " +
                "answered_by UUID, asked_from TEXT, body TEXT, created_at TIMESTAMP, read_note TEXT, reply_to " +
                "UUID, role " +
                "TEXT, PRIMARY KEY (user_id, message_id)) WITH CLUSTERING ORDER BY (message_id DESC)",
            "CREATE TABLE IF NOT EXISTS circaso_assistant.usage_daily_by_user (user_id UUID, usage_day TEXT, " +
                "input_tokens BIGINT, output_tokens BIGINT, text_turns INT, updated_at TIMESTAMP, voice_seconds " +
                "INT, " +
                "PRIMARY KEY (user_id, usage_day)) WITH CLUSTERING ORDER BY (usage_day DESC)",
            "CREATE TABLE IF NOT EXISTS circaso_assistant.entitlements_by_user (user_id UUID, expires_at " +
                "TIMESTAMP, " +
                "plan TEXT, PRIMARY KEY (user_id))",
            "CREATE TABLE IF NOT EXISTS circaso_assistant.audit_by_user (user_id UUID, event_id UUID, detail " +
                "TEXT, " +
                "kind TEXT, request_id TEXT, PRIMARY KEY (user_id, event_id)) WITH CLUSTERING ORDER BY (event_id " +
                "DESC)",
            "CREATE TABLE IF NOT EXISTS circaso_assistant.outbox (bucket TEXT, event_id UUID, payload TEXT, " +
                "published_at TIMESTAMP, type TEXT, PRIMARY KEY (bucket, event_id)) WITH CLUSTERING ORDER BY " +
                "(event_id " +
                "ASC) AND default_time_to_live = 604800",
        )

    @Test
    fun `V001 runs exactly the statements it was applied with`() {
        val session = FakeKandraSession()

        V001_CreateAssistantTables.up(session)

        assertEquals(v001, session.capturedStatements().map { (it as SimpleStatement).query })
    }
}
