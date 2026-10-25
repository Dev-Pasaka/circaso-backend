package com.circaso.messaging

import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
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

    @Test
    fun `V001 runs exactly the statements it was applied with`() {
        val session = FakeKandraSession()

        V001_CreateMessagingTables.up(session)

        assertEquals(v001, session.capturedStatements().map { (it as SimpleStatement).query })
    }
}
