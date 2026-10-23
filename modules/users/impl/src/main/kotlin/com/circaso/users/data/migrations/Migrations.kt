package com.circaso.users.data.migrations

import com.circaso.users.data.entities.BlockRow
import com.circaso.users.data.entities.BlockedByRow
import com.circaso.users.data.entities.ContactReverseRow
import com.circaso.users.data.entities.ContactRow
import com.circaso.users.data.entities.HandleClaimRow
import com.circaso.users.data.entities.InviteRow
import com.circaso.users.data.entities.ProfileRow
import com.circaso.users.data.entities.UsersOutboxRow
import com.circaso.users.data.entities.UsersRealtimeRow
import com.circaso.users.data.entities.WaitingContactRow
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigration

val usersEntities = listOf(
    ProfileRow::class, HandleClaimRow::class, ContactRow::class, ContactReverseRow::class, WaitingContactRow::class,
    BlockRow::class, BlockedByRow::class, InviteRow::class, UsersRealtimeRow::class, UsersOutboxRow::class,
)

/**
 * The first schema, as Kandra generated it for the entities on the day this was written. Tables name their
 * keyspace and use IF NOT EXISTS. Never edit it once applied; changes go in V002 and later.
 */
object V001_CreateUsersTables : KandraMigration(version = 1, name = "create users tables") {
    override fun up(session: CqlSession) {
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_users.profiles (user_id UUID, about TEXT, avatar_key TEXT, created_at TIMESTAMP, display_name TEXT, handle TEXT, handle_changed_at TIMESTAMP, privacy TEXT, updated_at TIMESTAMP, version BIGINT, PRIMARY KEY (user_id))",
            "CREATE TABLE IF NOT EXISTS circaso_users.handle_claims (handle TEXT, claimed_at TIMESTAMP, released_at TIMESTAMP, user_id UUID, PRIMARY KEY (handle))",
            "CREATE TABLE IF NOT EXISTS circaso_users.contacts_by_owner (owner_id UUID, contact_id UUID, added_at TIMESTAMP, removed_at TIMESTAMP, source TEXT, status TEXT, PRIMARY KEY (owner_id, contact_id)) WITH CLUSTERING ORDER BY (contact_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_users.contacts_by_target (contact_id UUID, owner_id UUID, added_at TIMESTAMP, PRIMARY KEY (contact_id, owner_id)) WITH CLUSTERING ORDER BY (owner_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_users.waiting_contacts (phone_hmac TEXT, owner_id UUID, added_at TIMESTAMP, PRIMARY KEY (phone_hmac, owner_id)) WITH CLUSTERING ORDER BY (owner_id ASC) AND default_time_to_live = 15552000",
            "CREATE TABLE IF NOT EXISTS circaso_users.blocks_by_user (user_id UUID, blocked_id UUID, blocked_at TIMESTAMP, PRIMARY KEY (user_id, blocked_id)) WITH CLUSTERING ORDER BY (blocked_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_users.blocked_by_user (blocked_id UUID, user_id UUID, blocked_at TIMESTAMP, PRIMARY KEY (blocked_id, user_id)) WITH CLUSTERING ORDER BY (user_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_users.invites (invite_token TEXT, created_at TIMESTAMP, kind TEXT, owner_id UUID, PRIMARY KEY (invite_token)) WITH default_time_to_live = 604800",
            "CREATE TABLE IF NOT EXISTS circaso_users.realtime_by_user (user_id UUID, seq BIGINT, frame TEXT, PRIMARY KEY (user_id, seq)) WITH CLUSTERING ORDER BY (seq DESC) AND default_time_to_live = 604800",
            "CREATE TABLE IF NOT EXISTS circaso_users.outbox (bucket TEXT, event_id UUID, payload TEXT, published_at TIMESTAMP, type TEXT, PRIMARY KEY (bucket, event_id)) WITH CLUSTERING ORDER BY (event_id ASC) AND default_time_to_live = 604800",
        ).forEach { session.execute(it) }
    }
}
