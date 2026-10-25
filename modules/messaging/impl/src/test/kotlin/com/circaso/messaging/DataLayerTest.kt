package com.circaso.messaging

import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.MessagingRealtimeRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.data.migrations.V002_CreateKeyDirectoryTables
import com.circaso.messaging.data.repositories.KeyRepositoryImpl
import com.circaso.messaging.data.repositories.MessagingOutboxStore
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.shared.DeviceId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventId
import com.circaso.shared.UserId
import io.kandra.migrate.KandraMigrationRunner
import io.kandra.test.KandraRuntimeHandle
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestUtils
import io.kandra.test.KandraTestcontainers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Messaging's data layer against a real Cassandra-compatible container: the migrations, the outbox, the key directory. */
class DataLayerTest {
    private val db: KandraRuntimeHandle =
        run {
            KandraTestBackend.newSession().use { admin ->
                admin.execute(KandraTestBackend.createKeyspaceCql("circaso_messaging"))
                KandraMigrationRunner(admin, keyspace = "circaso_messaging").run(
                    V001_CreateMessagingTables,
                    V002_CreateKeyDirectoryTables,
                )
            }
            KandraTestcontainers.freshKeyspace(
                MessagingRealtimeRow::class,
                MessagingOutboxRow::class,
                KeyBundleRow::class,
                OneTimePreKeyRow::class,
            )
        }

    private val rows = db.suspendRepository<MessagingOutboxRow>()
    private val outbox = MessagingOutboxStore(rows)
    private val keys = KeyRepositoryImpl(db.runtime, db.suspendRepository(), db.suspendRepository())

    @AfterTest
    fun close() {
        db.close()
        KandraTestUtils.reset()
    }

    private fun bundle(userId: UserId, signedPreKeyId: Int = 7) =
        KeyBundle(
            userId,
            DeviceId.new(),
            "BcF2",
            SignedPreKey(signedPreKeyId, "BXa", "kq3"),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
        )

    private fun oneTime(ids: IntRange) = ids.map { OneTimePreKey(it, "BQm$it") }

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

    @Test
    fun `a bundle is published, replaced and topped up`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val first = bundle(amina, signedPreKeyId = 7)
            keys.publish(first, oneTime(1..5), staleKeyIds = emptySet())
            assertEquals(first, keys.bundle(amina))
            assertEquals((1..5).toSet(), keys.oneTimeKeyIds(amina))

            val second = bundle(amina, signedPreKeyId = 8)
            keys.publish(second, oneTime(4..8), staleKeyIds = setOf(1, 2, 3))
            assertEquals(8, keys.bundle(amina)!!.signedPreKey.keyId)
            assertEquals((4..8).toSet(), keys.oneTimeKeyIds(amina), "rewritten ids stay, stale ones go")

            keys.addOneTimeKeys(amina, oneTime(9..10))
            assertEquals((4..10).toSet(), keys.oneTimeKeyIds(amina))
        }

    @Test
    fun `concurrent claims never return the same prekey, and a claimed key is gone`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            keys.publish(bundle(amina), oneTime(1..20), staleKeyIds = emptySet())

            val claimed =
                (1..30)
                    .map { async(Dispatchers.IO) { keys.claimOneTimeKey(amina) } }
                    .awaitAll()
                    .filterNotNull()

            val ids = claimed.map { it.keyId }
            assertEquals(ids.size, ids.toSet().size, "no key was handed out twice: $ids")
            val left = keys.oneTimeKeyIds(amina)
            assertTrue(ids.none { it in left }, "a key handed out is no longer stored")
            // a claim that times out under contention may delete a key without handing it out, never twice
            assertTrue((ids + left).all { it in 1..20 })
            assertTrue(claimed.all { it.publicKey == "BQm${it.keyId}" })
        }

    @Test
    fun `claims take the keys in order until none are left`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            keys.publish(bundle(amina), oneTime(1..3), staleKeyIds = emptySet())

            assertEquals(listOf(1, 2, 3), (1..3).map { keys.claimOneTimeKey(amina)!!.keyId })
            assertNull(keys.claimOneTimeKey(amina))
            assertNull(keys.claimOneTimeKey(UserId.new()), "someone who never published has none")
        }
}
