package com.circaso.identity.data

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.repositories.Changes
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A QA follow-up: with `LOG_LEVEL_DATABASE=DEBUG` (and everything else at DEBUG too), a real identity write that
 * carries a phone number, and one the unique index refuses, never put the number in any log event: not in a message,
 * an argument or an exception. The events are read raw, before any appender's masking.
 */
class DatabaseLogsTest {
    private val levels = listOf("LOG_LEVEL", "LOG_LEVEL_OTHER", "LOG_LEVEL_DATABASE", "LOG_FORMAT")
    private val captured = ListAppender<ILoggingEvent>()

    // A number nothing else in the tests uses, so finding it anywhere means this test's write leaked it.
    private val marker = "+254799123987"

    @BeforeTest
    fun debugEverything() {
        configure("LOG_LEVEL" to "DEBUG", "LOG_LEVEL_OTHER" to "DEBUG", "LOG_LEVEL_DATABASE" to "DEBUG")
        captured.start()
        root().addAppender(captured)
        PostgresIdentity.reset()
    }

    @AfterTest
    fun restore() {
        root().detachAppender(captured)
        configure()
    }

    private fun root(): Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    private fun configure(vararg settings: Pair<String, String>) {
        levels.forEach(System::clearProperty)
        settings.forEach { (name, value) -> System.setProperty(name, value) }
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.reset()
        JoranConfigurator().apply { this.context = context }.doConfigure(
            javaClass.classLoader.getResource("logback.xml"),
        )
    }

    private fun ILoggingEvent.text(): String =
        buildString {
            append(message).append(' ').append(formattedMessage)
            argumentArray?.forEach { append(' ').append(it) }
            var throwable: IThrowableProxy? = throwableProxy
            while (throwable != null) {
                append(' ').append(throwable.message)
                throwable.stackTraceElementProxyArray?.forEach { append(' ').append(it) }
                throwable = throwable.cause
            }
        }

    @Test
    fun `a write that carries a phone number, and one refused for it, never log the number`() =
        runBlocking<Unit> {
            val pg = PostgresIdentity()
            val phone = PhoneNumber.parse(marker)!!
            val first =
                Account(UserId.new(), null, AccountStatus.PENDING_PHONE, null, Verification.NONE, 1, Instant.now())
            val second = first.copy(userId = UserId.new())

            pg.transactor.run("test.sign-up") {
                pg.accounts.create(first)
                pg.accounts.create(second)
            }
            val claims =
                listOf(first, second).map { account ->
                    pg.transactor.attempt("accounts.claim-phone") {
                        val locked = pg.accounts.lock(account.userId)!!
                        val verified = SecurityEvent(account.userId, null, SecurityEventType.PHONE_VERIFIED, null, null)
                        pg.store.write(Changes(securityEvents = listOf(verified)))
                        if (pg.accounts.claimPhone(locked, phone)) {
                            Resource.success(Unit)
                        } else {
                            Resource.error(SharedError.Internal)
                        }
                    }
                }
            pg.accounts.findByPhone(phone)
            assertTrue(claims[0] is Resource.Success && claims[1] is Resource.Error, "the second claim was refused")

            val events = captured.list.toList()
            assertTrue(
                events.any { it.loggerName.startsWith("com.circaso.infrastructure.impl.database") },
                "DEBUG was on",
            )
            val digits = marker.removePrefix("+")
            val leaks = events.filter { digits in it.text() }
            assertTrue(leaks.isEmpty(), "the number reached the log: ${leaks.map { it.loggerName }}")
        }
}
