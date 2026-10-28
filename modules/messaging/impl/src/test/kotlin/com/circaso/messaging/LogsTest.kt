package com.circaso.messaging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.KyberPreKey
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.RecipientCopy
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.shared.DeviceId
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What messaging's writes never put in a log line, with `LOG_LEVEL_DATABASE` and `LOG_LEVEL_MESSAGING` at DEBUG. */
class LogsTest {
    private val h = PostgresHarness()
    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    /** The loggers `LOG_LEVEL_DATABASE` sets (infrastructure's logback.xml), plus messaging's own. */
    private val loggers =
        listOf("com.circaso.infrastructure.impl.database", "io.r2dbc", "org.postgresql", "com.circaso.messaging")
            .map { LoggerFactory.getLogger(it) as Logger }

    @BeforeTest
    fun setUp() {
        TestPostgres.truncate("messaging")
        root.addAppender(logs)
        loggers.forEach { it.level = Level.DEBUG }
    }

    @AfterTest
    fun tearDown() {
        root.detachAppender(logs)
        loggers.forEach { it.level = null }
    }

    private fun logged(): String =
        logs.list.joinToString("\n") { event ->
            event.formattedMessage + event.argumentArray.orEmpty().joinToString() +
                (event.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
        }

    @Test
    fun `keys, ciphertext and phone numbers never reach a log line`() =
        runBlocking<Unit> {
            val phone = "+254712345678"
            val ciphertext = Base64.getEncoder().encodeToString("marker $phone".toByteArray())
            val (amina, brian, chidi) = List(3) { UserId.new() }
            val identityKey = "identity-marker-0712345678"
            val prekey = "prekey-marker-0712345678"
            h.keys.publish(
                KeyBundle(
                    brian,
                    DeviceId.new(),
                    identityKey,
                    SignedPreKey(7, "signed-marker", "signature"),
                    KyberPreKey(3, "kyber-marker", "signature"),
                    Instant.now(),
                ),
                listOf(OneTimePreKey(1, prekey)),
                Instant.now(),
            )
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian, chidi))
            val toBrian =
                SendMessageCommand(
                    amina,
                    direct,
                    UUID.randomUUID(),
                    MessageKind.MESSAGE,
                    null,
                    listOf(RecipientCopy(brian, ciphertext)),
                    emptyList(),
                    pushable = true,
                )
            val sent = h.sendMessage(toBrian).data()
            h.sendMessage(h.groupCommand(amina, group).copy(ciphertext = ciphertext)).data()
            h.fetchKeyBundle(amina, brian).data()
            h.readInbox(brian, null, 10).data()
            h.ackDelivered(brian, listOf(sent.messageId)).data()

            val logged = logged()
            assertTrue("messages.send" in logged, "the per-transaction line is there at DEBUG: $logged")
            assertFalse(ciphertext in logged, "no ciphertext")
            assertFalse("0712345678" in logged, "no keys or numbers")
            assertFalse("712345678" in logged, "no phone number")
            assertFalse("signed-marker" in logged, "no signed prekey")
            assertFalse(amina.value.toString() in logged, "no ids in the database lines")
        }
}
