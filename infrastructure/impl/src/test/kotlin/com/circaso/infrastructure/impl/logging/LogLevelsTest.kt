package com.circaso.infrastructure.impl.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The per-service log switches in logback.xml: what each variable controls, and what happens when none is set. */
class LogLevelsTest {
    private val variables =
        listOf(
            "LOG_FORMAT",
            "LOG_LEVEL",
            "LOG_LEVEL_OTHER",
            "LOG_LEVEL_REQUESTS",
            "LOG_LEVEL_GATEWAY",
            "LOG_LEVEL_IDENTITY",
            "LOG_LEVEL_USERS",
            "LOG_LEVEL_ASSISTANT",
            "LOG_LEVEL_INTEGRATIONS",
            "LOG_LEVEL_EVENTS",
            "LOG_LEVEL_SCHEDULER",
            "LOG_LEVEL_DATABASE",
            "LOG_LEVEL_REDIS",
            "LOG_LEVEL_HTTP",
        )

    @AfterTest
    fun reset() {
        variables.forEach(System::clearProperty)
        configure()
    }

    private fun configure(vararg settings: Pair<String, String>) {
        variables.forEach(System::clearProperty)
        settings.forEach { (name, value) -> System.setProperty(name, value) }
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.reset()
        JoranConfigurator().apply { this.context = context }.doConfigure(
            javaClass.classLoader.getResource("logback.xml"),
        )
    }

    private fun levelOf(name: String): Level =
        (
            LoggerFactory.getLogger(
                name,
            ) as ch.qos.logback.classic.Logger
        ).effectiveLevel

    @Test
    fun `with nothing set Circaso logs at INFO and the libraries stay quiet`() {
        configure("LOG_FORMAT" to "PRETTY")

        assertEquals(Level.INFO, levelOf("com.circaso.identity.domain.usecases.SignInWithProvider"))
        assertEquals(Level.INFO, levelOf("com.circaso.gateway.RequestLog"))
        assertEquals(Level.WARN, levelOf("com.datastax.oss.driver.internal.core.channel.InFlightHandler"))
        assertEquals(Level.WARN, levelOf("io.kandra.runtime.BatchEngine"))
        assertEquals(Level.WARN, levelOf("io.netty.handler.codec.http.HttpObjectDecoder"))
        assertEquals(Level.WARN, levelOf("io.lettuce.core.protocol.CommandHandler"))
        assertEquals(Level.WARN, levelOf("org.apache.anything.Else"))
    }

    @Test
    fun `LOG_LEVEL moves every Circaso module and leaves the libraries alone`() {
        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL" to "DEBUG")

        assertEquals(Level.DEBUG, levelOf("com.circaso.users.domain.usecases.SyncContacts"))
        assertEquals(Level.DEBUG, levelOf("com.circaso.infrastructure.impl.events.EventBus"))
        assertEquals(
            Level.WARN,
            levelOf("com.datastax.oss.driver.internal.core.channel.InFlightHandler"),
            "the driver follows its own switch",
        )
    }

    @Test
    fun `each service has its own switch, and OFF turns it off`() {
        configure(
            "LOG_FORMAT" to "PRETTY",
            "LOG_LEVEL" to "WARN",
            "LOG_LEVEL_IDENTITY" to "DEBUG",
            "LOG_LEVEL_REQUESTS" to "OFF",
            "LOG_LEVEL_EVENTS" to "ERROR",
            "LOG_LEVEL_SCHEDULER" to "OFF",
            "LOG_LEVEL_DATABASE" to "DEBUG",
            "LOG_LEVEL_REDIS" to "TRACE",
            "LOG_LEVEL_HTTP" to "INFO",
            "LOG_LEVEL_INTEGRATIONS" to "DEBUG",
            "LOG_LEVEL_USERS" to "INFO",
            "LOG_LEVEL_ASSISTANT" to "OFF",
            "LOG_LEVEL_GATEWAY" to "DEBUG",
        )

        assertEquals(Level.DEBUG, levelOf("com.circaso.identity.domain.usecases.RegisterDevice"))
        assertEquals(Level.OFF, levelOf("com.circaso.gateway.RequestLog"), "the request line, off")
        assertEquals(
            Level.DEBUG,
            levelOf("com.circaso.gateway.Webhooks"),
            "the rest of the gateway is separate from the request line",
        )
        assertEquals(Level.ERROR, levelOf("com.circaso.infrastructure.impl.events.OutboxRelay"))
        assertEquals(Level.OFF, levelOf("com.circaso.infrastructure.impl.scheduler.Scheduler"))
        assertEquals(Level.DEBUG, levelOf("com.datastax.oss.driver.internal.core.channel.InFlightHandler"))
        assertEquals(Level.DEBUG, levelOf("io.kandra.runtime.BatchEngine"))
        assertEquals(Level.DEBUG, levelOf("com.circaso.infrastructure.impl.database.Kandra"))
        assertEquals(Level.TRACE, levelOf("io.lettuce.core.protocol.CommandHandler"))
        assertEquals(Level.TRACE, levelOf("com.circaso.infrastructure.impl.redis.Redis"))
        assertEquals(Level.INFO, levelOf("io.ktor.server.engine.EmbeddedServer"))
        assertEquals(Level.DEBUG, levelOf("com.circaso.integrations.impl.dev.LogSms"))
        assertEquals(Level.DEBUG, levelOf("com.circaso.infrastructure.impl.http.VendorHttpClients"))
        assertEquals(Level.INFO, levelOf("com.circaso.users.domain.usecases.SyncContacts"))
        assertEquals(Level.OFF, levelOf("com.circaso.assistant.domain.usecases.StartTurn"))
        assertEquals(Level.WARN, levelOf("com.circaso.app.Main"), "anything else of Circaso follows LOG_LEVEL")
    }

    @Test
    fun `LOG_LEVEL_OTHER sets the level for everything not named`() {
        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL_OTHER" to "ERROR")

        assertEquals(Level.ERROR, levelOf("org.somelibrary.Thing"))
        assertTrue(levelOf("com.circaso.app.Main") == Level.INFO)
    }
}
