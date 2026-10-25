package com.circaso.messaging

import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.MessagingRealtimeRow
import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.data.repositories.MessagingOutboxStore
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventId
import io.kandra.migrate.KandraMigrationRunner
import io.kandra.test.KandraRuntimeHandle
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestUtils
import io.kandra.test.KandraTestcontainers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The foundation's data layer against a real Cassandra-compatible container: the migration and the outbox. */
class DataLayerTest {
    private val db: KandraRuntimeHandle =
        run {
            KandraTestBackend.newSession().use { admin ->
                admin.execute(KandraTestBackend.createKeyspaceCql("circaso_messaging"))
                KandraMigrationRunner(admin, keyspace = "circaso_messaging").run(V001_CreateMessagingTables)
            }
            KandraTestcontainers.freshKeyspace(
                MessagingRealtimeRow::class,
                MessagingOutboxRow::class,
            )
        }

    private val rows = db.suspendRepository<MessagingOutboxRow>()
    private val outbox = MessagingOutboxStore(rows)

    @AfterTest
    fun close() {
        db.close()
        KandraTestUtils.reset()
    }

    @Test
    fun `an event waits in the outbox until the relay stamps it`() =
        runBlocking<Unit> {
            val event =
                EventEnvelope(
                    eventId = EventId.new(),
                    type = "messaging.ChatCreated",
                    version = 1,
                    occurredAt = Instant.now(),
                    producer = "messaging",
                    data = JsonObject(emptyMap()),
                )
            rows.save(MessagingOutboxRow(event.bucket, event.eventId.value, event.type, event.toJson()))

            assertEquals(listOf(event.eventId), outbox.unpublished(event.bucket).map { it.eventId })
            outbox.markPublished(event, Instant.now())
            assertTrue(outbox.unpublished(event.bucket).isEmpty())
        }
}
