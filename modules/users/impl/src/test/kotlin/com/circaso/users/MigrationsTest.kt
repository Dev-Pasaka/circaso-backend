package com.circaso.users

import com.circaso.users.data.migrations.V001_CreateUsersTables
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
            "CREATE TABLE IF NOT EXISTS circaso_users.profiles (user_id UUID, about TEXT, avatar_key TEXT, " +
                "created_at TIMESTAMP, display_name TEXT, handle TEXT, handle_changed_at TIMESTAMP, privacy TEXT, " +
                "updated_at TIMESTAMP, version BIGINT, PRIMARY KEY (user_id))",
            "CREATE TABLE IF NOT EXISTS circaso_users.handle_claims (handle TEXT, claimed_at TIMESTAMP, released_at " +
                "TIMESTAMP, user_id UUID, PRIMARY KEY (handle))",
            "CREATE TABLE IF NOT EXISTS circaso_users.contacts_by_owner (owner_id UUID, contact_id UUID, added_at " +
                "TIMESTAMP, removed_at TIMESTAMP, source TEXT, status TEXT, PRIMARY KEY (owner_id, contact_id)) WITH " +
                "CLUSTERING ORDER BY (contact_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_users.contacts_by_target (contact_id UUID, owner_id UUID, added_at " +
                "TIMESTAMP, PRIMARY KEY (contact_id, owner_id)) WITH CLUSTERING ORDER BY (owner_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_users.waiting_contacts (phone_hmac TEXT, owner_id UUID, added_at " +
                "TIMESTAMP, PRIMARY KEY (phone_hmac, owner_id)) WITH CLUSTERING ORDER BY (owner_id ASC) AND " +
                "default_time_to_live = 15552000",
            "CREATE TABLE IF NOT EXISTS circaso_users.blocks_by_user (user_id UUID, blocked_id UUID, blocked_at " +
                "TIMESTAMP, PRIMARY KEY (user_id, blocked_id)) WITH CLUSTERING ORDER BY (blocked_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_users.blocked_by_user (blocked_id UUID, user_id UUID, blocked_at " +
                "TIMESTAMP, PRIMARY KEY (blocked_id, user_id)) WITH CLUSTERING ORDER BY (user_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_users.invites (invite_token TEXT, created_at TIMESTAMP, kind TEXT, " +
                "owner_id UUID, PRIMARY KEY (invite_token)) WITH default_time_to_live = 604800",
            "CREATE TABLE IF NOT EXISTS circaso_users.realtime_by_user (user_id UUID, seq BIGINT, frame TEXT, " +
                "PRIMARY KEY (user_id, seq)) WITH CLUSTERING ORDER BY (seq DESC) AND default_time_to_live = 604800",
            "CREATE TABLE IF NOT EXISTS circaso_users.outbox (bucket TEXT, event_id UUID, payload TEXT, published_at " +
                "TIMESTAMP, type TEXT, PRIMARY KEY (bucket, event_id)) WITH CLUSTERING ORDER BY (event_id ASC) AND " +
                "default_time_to_live = 604800",
        )

    @Test
    fun `V001 runs exactly the statements it was applied with`() {
        val session = FakeKandraSession()

        V001_CreateUsersTables.up(session)

        assertEquals(v001, session.capturedStatements().map { (it as SimpleStatement).query })
    }
}
