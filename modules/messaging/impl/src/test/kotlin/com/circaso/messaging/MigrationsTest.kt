package com.circaso.messaging

import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.data.migrations.V002_CreateKeyDirectoryTables
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
            "CREATE TABLE IF NOT EXISTS circaso_messaging.realtime_by_user (user_id UUID, seq BIGINT, frame TEXT, " +
                "PRIMARY KEY (user_id, seq)) WITH CLUSTERING ORDER BY (seq DESC) AND default_time_to_live = 604800",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.outbox (bucket TEXT, event_id UUID, payload TEXT, " +
                "published_at TIMESTAMP, type TEXT, PRIMARY KEY (bucket, event_id)) WITH CLUSTERING ORDER BY " +
                "(event_id ASC) AND default_time_to_live = 604800",
        )

    private val v002 =
        listOf(
            "CREATE TABLE IF NOT EXISTS circaso_messaging.key_bundles (user_id UUID, device_id UUID, identity_key " +
                "TEXT, signed_pre_key TEXT, signed_pre_key_at TIMESTAMP, signed_pre_key_id INT, " +
                "signed_pre_key_signature TEXT, PRIMARY KEY (user_id))",
            "CREATE TABLE IF NOT EXISTS circaso_messaging.one_time_prekeys (user_id UUID, key_id INT, public_key " +
                "TEXT, PRIMARY KEY (user_id, key_id)) WITH CLUSTERING ORDER BY (key_id ASC)",
        )

    @Test
    fun `V001 runs exactly the statements it was applied with`() {
        val session = FakeKandraSession()

        V001_CreateMessagingTables.up(session)

        assertEquals(v001, session.capturedStatements().map { (it as SimpleStatement).query })
    }

    @Test
    fun `V002 runs exactly the statements it was applied with`() {
        val session = FakeKandraSession()

        V002_CreateKeyDirectoryTables.up(session)

        assertEquals(v002, session.capturedStatements().map { (it as SimpleStatement).query })
    }
}
