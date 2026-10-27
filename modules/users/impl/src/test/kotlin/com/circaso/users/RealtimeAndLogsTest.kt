package com.circaso.users

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.CircasoJson
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.data.cache.RealtimeFramesImpl
import com.circaso.users.data.repositories.ContactRepositoryImpl
import com.circaso.users.data.repositories.DatabaseTransactor
import com.circaso.users.data.repositories.HandleRepositoryImpl
import com.circaso.users.data.repositories.UsersStoreImpl
import com.circaso.users.di.usersDatabaseSpec
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.WaitingContact
import com.circaso.users.domain.ports.UsersFrame
import com.circaso.users.domain.repositories.UsersChanges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Only the counter the realtime sender uses; anything else is a test mistake. */
private class SeqRedis : RedisScope {
    private val counters = ConcurrentHashMap<String, Long>()
    override val prefix = "usr"

    override suspend fun incrementRenewing(key: String, ttl: Duration): Long = counters.merge(key, 1L, Long::plus)!!

    override suspend fun get(key: String): String? = counters[key]?.toString()

    override suspend fun getPipelined(keys: List<String>): List<String?> = error("unused")

    override suspend fun set(
        key: String,
        value: String,
        ttl: Duration,
    ) = error("unused")

    override suspend fun setIfAbsent(
        key: String,
        value: String,
        ttl: Duration,
    ): Boolean = error("unused")

    override suspend fun getAndDelete(key: String): String? = error("unused")

    override suspend fun delete(key: String): Boolean = error("unused")

    override suspend fun exists(key: String): Boolean = error("unused")

    override suspend fun increment(key: String, ttl: Duration): Long = error("unused")

    override suspend fun incrementBy(
        key: String,
        by: Long,
        ttl: Duration,
    ): Long = error("unused")

    override suspend fun hashSet(
        key: String,
        fields: Map<String, String>,
        ttl: Duration,
    ) = error("unused")

    override suspend fun hashGetAll(key: String): Map<String, String> = error("unused")

    override suspend fun hashTake(key: String): Map<String, String> = error("unused")

    override suspend fun hashDelete(key: String, field: String): Boolean = error("unused")

    override suspend fun hashIncrement(
        key: String,
        field: String,
        by: Long,
    ): Long? = error("unused")

    override suspend fun ttl(key: String): Duration? = error("unused")

    override suspend fun publish(channel: String, message: String) = error("unused")

    override fun subscribe(channel: String): Flow<String> = emptyFlow()
}

private class RecordingRealtime : Realtime {
    val delivered = CopyOnWriteArrayList<Pair<UserId, RealtimeEnvelope>>()

    override suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope): Resource<Unit> {
        delivered += userId to envelope
        return Resource.success(Unit)
    }

    override suspend fun deliverMany(userIds: Collection<UserId>, envelope: RealtimeEnvelope): Resource<Unit> =
        error("unused")
}

/** The realtime frames users keeps for replay, and what its writes never put in a log line. */
class RealtimeAndLogsTest {
    private val database = TestPostgres.database(usersDatabaseSpec)
    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    /** The loggers `LOG_LEVEL_DATABASE` sets (infrastructure's logback.xml), plus users' own. */
    private val databaseLoggers =
        listOf("com.circaso.infrastructure.impl.database", "io.r2dbc", "org.postgresql", "com.circaso.users")
            .map { LoggerFactory.getLogger(it) as Logger }

    @BeforeTest
    fun setUp() {
        TestPostgres.truncate("users")
        root.addAppender(logs)
        databaseLoggers.forEach { it.level = Level.DEBUG }
    }

    @AfterTest
    fun tearDown() {
        root.detachAppender(logs)
        databaseLoggers.forEach { it.level = null }
    }

    @Test
    fun `a sent frame takes the next seq, is kept, delivered, and replayed in order`() =
        runBlocking<Unit> {
            val realtime = RecordingRealtime()
            val frames = RealtimeFramesImpl(SeqRedis(), database, realtime, Clock.systemUTC())
            val amina = UserId.new()
            val brian = UserId.new()

            frames.send(amina, UsersFrame.ProfileUpdated(brian, 1))
            frames.send(amina, UsersFrame.ContactJoined(brian, "Brian"))
            frames.send(amina, UsersFrame.ProfileUpdated(brian, 2))

            assertEquals(listOf(1L, 2L, 3L), realtime.delivered.map { it.second.seq })
            assertEquals(listOf(2L, 3L), frames.since(amina, 1, 10).map { it.seq })
            assertEquals(listOf(1L), frames.since(amina, 0, 1).map { it.seq })
            val replayed =
                CircasoJson.decodeFromString(
                    RealtimeEnvelope.serializer(),
                    frames.since(amina, 2, 10)[0].json,
                )
            assertEquals("profile.updated", replayed.type)
            assertEquals(3L, frames.latest(amina))
            assertTrue(frames.since(brian, 0, 10).isEmpty())
        }

    @Test
    fun `handle and contact writes at LOG_LEVEL_DATABASE=DEBUG never log their values`() =
        runBlocking<Unit> {
            val marker = "marker_0712345678"
            val handles = HandleRepositoryImpl(database, Clock.systemUTC())
            val contacts = ContactRepositoryImpl(database)
            val store = UsersStoreImpl(database)
            val transactor = DatabaseTransactor(database)
            val me = UserId.new()
            val her = UserId.new()

            transactor.run("handles.claim") { handles.claim(marker, me, Instant.now()) }
            store.commit(
                UsersChanges(
                    contacts =
                        listOf(
                            Contact(me, her, ContactSource.PHONEBOOK, ContactStatus.ACTIVE, null, Instant.now()),
                        ),
                    waiting = listOf(WaitingContact("hmac-$marker", me, Instant.now())),
                ),
            )
            contacts.remove(me, her, Instant.now())
            handles.find(marker)

            val logged =
                logs.list.joinToString("\n") { event ->
                    event.formattedMessage + event.argumentArray.orEmpty().joinToString() +
                        (event.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
                }
            assertTrue("handles.claim" in logged, "the per-transaction line is there at DEBUG: $logged")
            assertFalse("0712345678" in logged, logged)
            assertFalse(me.value.toString() in logged, logged)
        }
}
