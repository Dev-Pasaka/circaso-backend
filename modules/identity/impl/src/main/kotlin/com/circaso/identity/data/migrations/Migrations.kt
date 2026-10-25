package com.circaso.identity.data.migrations

import com.circaso.identity.data.entities.AccountRow
import com.circaso.identity.data.entities.DeviceRow
import com.circaso.identity.data.entities.IdentityOutboxRow
import com.circaso.identity.data.entities.PhoneClaimRow
import com.circaso.identity.data.entities.ProviderClaimRow
import com.circaso.identity.data.entities.ProviderLinkRow
import com.circaso.identity.data.entities.RecoveryByRequestRow
import com.circaso.identity.data.entities.SecurityEventRow
import com.circaso.identity.data.entities.SessionRow
import com.circaso.identity.data.entities.SupportRecoveryRow
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigration

val identityEntities =
    listOf(
        AccountRow::class,
        PhoneClaimRow::class,
        ProviderClaimRow::class,
        ProviderLinkRow::class,
        DeviceRow::class,
        SessionRow::class,
        SecurityEventRow::class,
        SupportRecoveryRow::class,
        RecoveryByRequestRow::class,
        IdentityOutboxRow::class,
    )

/**
 * The first schema: the tables as Kandra generated them for the entities on the day this was written. Tables name
 * their keyspace and use IF NOT EXISTS. Never edit it once applied (its checksum would stop the deploy); changes go
 * in V002 and later.
 */
object V001_CreateIdentityTables : KandraMigration(version = 1, name = "create identity tables") {
    // Run with a plain loop, never forEach or another inline call: an inline call leaves Kotlin's line table in
    // the class, and the checksum would then change whenever these lines move (docs/kandra-bugs.md #5).
    private val statements =
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_identity.accounts_by_id (user_id UUID, active_device_id UUID, " +
                "created_at TIMESTAMP, phone TEXT, session_version INT, status TEXT, updated_at TIMESTAMP, " +
                "verification " +
                "TEXT, version BIGINT, PRIMARY KEY (user_id))",
            "CREATE TABLE IF NOT EXISTS circaso_identity.phone_claims (phone TEXT, claimed_at TIMESTAMP, user_id " +
                "UUID, PRIMARY KEY (phone))",
            "CREATE TABLE IF NOT EXISTS circaso_identity.provider_claims (provider TEXT, subject TEXT, linked_at " +
                "TIMESTAMP, user_id UUID, PRIMARY KEY ((provider, subject)))",
            "CREATE TABLE IF NOT EXISTS circaso_identity.providers_by_user (user_id UUID, provider TEXT, email " +
                "TEXT, " +
                "linked_at TIMESTAMP, subject TEXT, PRIMARY KEY (user_id, provider)) WITH CLUSTERING ORDER BY " +
                "(provider " +
                "ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_identity.devices_by_user (user_id UUID, device_id UUID, " +
                "app_version " +
                "TEXT, key_algorithm TEXT, last_seen_at TIMESTAMP, model TEXT, platform TEXT, public_key TEXT, " +
                "push_token TEXT, registered_at TIMESTAMP, status TEXT, PRIMARY KEY (user_id, device_id)) WITH " +
                "CLUSTERING ORDER BY (device_id ASC)",
            "CREATE TABLE IF NOT EXISTS circaso_identity.sessions_by_id (session_id UUID, created_at TIMESTAMP, " +
                "current_token_hash TEXT, device_id UUID, rotated_at TIMESTAMP, scope TEXT, status TEXT, user_id " +
                "UUID, " +
                "PRIMARY KEY (session_id)) WITH default_time_to_live = 2592000",
            "CREATE TABLE IF NOT EXISTS circaso_identity.security_log_by_user (user_id UUID, event_id UUID, " +
                "detail " +
                "TEXT, device_model TEXT, type TEXT, PRIMARY KEY (user_id, event_id)) WITH CLUSTERING ORDER BY " +
                "(event_id " +
                "DESC)",
            "CREATE TABLE IF NOT EXISTS circaso_identity.support_recoveries (user_id UUID, request_id UUID, " +
                "agent_id " +
                "TEXT, contact_email TEXT, id_check TEXT, new_provider TEXT, new_subject TEXT, note TEXT, " +
                "notice_sent_at " +
                "TIMESTAMP, relink_after TIMESTAMP, selfie_match TEXT, status TEXT, PRIMARY KEY (user_id, " +
                "request_id)) " +
                "WITH CLUSTERING ORDER BY (request_id DESC)",
            "CREATE TABLE IF NOT EXISTS circaso_identity.support_recoveries_by_request (request_id UUID, user_id " +
                "UUID, PRIMARY KEY (request_id))",
            "CREATE TABLE IF NOT EXISTS circaso_identity.outbox (bucket TEXT, event_id UUID, payload TEXT, " +
                "published_at TIMESTAMP, type TEXT, PRIMARY KEY (bucket, event_id)) WITH CLUSTERING ORDER BY " +
                "(event_id " +
                "ASC) AND default_time_to_live = 604800",
        )

    override fun up(session: CqlSession) {
        for (statement in statements) {
            session.execute(statement)
        }
    }
}
