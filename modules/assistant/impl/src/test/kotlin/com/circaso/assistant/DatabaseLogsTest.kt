package com.circaso.assistant

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.circaso.assistant.data.cache.AesTextVault
import com.circaso.assistant.data.repositories.AssistantStoreImpl
import com.circaso.assistant.data.repositories.ChatRepositoryImpl
import com.circaso.assistant.data.repositories.DatabaseTransactor
import com.circaso.assistant.di.assistantDatabaseSpec
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.ReadNote
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.assistant.domain.usecases.DeleteExchange
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Asha's messages are sensitive: what the assistant's database work never puts in a log line, even at DEBUG. */
class DatabaseLogsTest {
    private val database = TestPostgres.database(assistantDatabaseSpec)
    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    /** The loggers `LOG_LEVEL_DATABASE` sets (infrastructure's logback.xml), plus the assistant's own. */
    private val databaseLoggers =
        listOf("com.circaso.infrastructure.impl.database", "io.r2dbc", "org.postgresql", "com.circaso.assistant")
            .map { LoggerFactory.getLogger(it) as Logger }

    @BeforeTest
    fun setUp() {
        TestPostgres.truncate("assistant")
        root.addAppender(logs)
        databaseLoggers.forEach { it.level = Level.DEBUG }
    }

    @AfterTest
    fun tearDown() {
        root.detachAppender(logs)
        databaseLoggers.forEach { it.level = null }
    }

    @Test
    fun `chat text and a phone number in it never reach a log line at LOG_LEVEL_DATABASE=DEBUG`() =
        runBlocking<Unit> {
            val marker = "marker_rent_0712345678"
            val vault =
                AesTextVault(
                    object : Secrets {
                        override fun get(name: String): String? = null
                    },
                    local = true,
                )
            val chat = ChatRepositoryImpl(database, vault)
            val store = AssistantStoreImpl(database, vault, Clock.systemUTC())
            val transactor = DatabaseTransactor(database)
            val me = UserId.new()
            val now = Instant.now()
            val questionId = Uuid7.generate(now)
            val answerId = Uuid7.generate(now.plusMillis(1))
            val conversationId = UUID.randomUUID()
            val question =
                ChatMessage(
                    me,
                    questionId,
                    Role.USER,
                    "Call $marker",
                    null,
                    answerId,
                    marker,
                    null,
                    now,
                    conversationId,
                )
            val answer =
                ChatMessage(
                    me,
                    answerId,
                    Role.ASHA,
                    "Mum is on $marker",
                    questionId,
                    null,
                    null,
                    ReadNote(listOf("chat_$marker"), 1, listOf("Mum $marker")),
                    now,
                    conversationId,
                )

            transactor.run("chat.record-turn") {
                store.commit(
                    AssistantChanges(
                        messages = listOf(question, answer),
                        audit = listOf(AuditEntry(me, "TURN", "tools=none", null)),
                    ),
                )
                store.saveUsage(me, "2026-10-22", 1, 0, 10, 5, now)
            }
            chat.list(me, null, 10)
            chat.find(me, answerId)
            // A failed statement: the same message again breaks the primary key.
            assertFailsWith<DatabaseException> { store.commit(AssistantChanges(messages = listOf(question))) }
            DeleteExchange(chat, transactor, store)(me, questionId, null)

            val logged =
                logs.list.joinToString("\n") { event ->
                    event.formattedMessage + event.argumentArray.orEmpty().joinToString() +
                        (event.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
                }
            assertTrue("chat.record-turn" in logged, "the per-transaction line is there at DEBUG: $logged")
            assertFalse("0712345678" in logged, logged)
            assertFalse("marker_rent" in logged, logged)
            assertFalse(me.value.toString() in logged, logged)
        }
}
