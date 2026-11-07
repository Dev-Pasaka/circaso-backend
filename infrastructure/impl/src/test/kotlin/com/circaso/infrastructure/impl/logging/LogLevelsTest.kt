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
            "LOG_LEVEL_MESSAGING",
            "LOG_LEVEL_WALLET",
            "LOG_LEVEL_CALLS",
            "LOG_LEVEL_STATUS",
            "LOG_LEVEL_SHARE",
            "LOG_LEVEL_ASSISTANT",
            "LOG_LEVEL_INTEGRATIONS",
            "LOG_LEVEL_CIRCLE",
            "LOG_LEVEL_EXCHANGERATE",
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
        assertEquals(Level.ERROR, levelOf("Exposed"), "Exposed's own logger can quote values")
        assertEquals(Level.WARN, levelOf("io.r2dbc.pool.ConnectionPool"))
        assertEquals(Level.WARN, levelOf("io.r2dbc.pool.ConnectionPool"))
        assertEquals(Level.WARN, levelOf("org.flywaydb.core.internal.command.DbMigrate"))
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
            levelOf("io.r2dbc.pool.ConnectionPool"),
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
            "LOG_LEVEL_MESSAGING" to "ERROR",
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
        assertEquals(Level.DEBUG, levelOf("com.circaso.infrastructure.impl.database.Transactions"))
        assertEquals(Level.ERROR, levelOf("Exposed"), "Exposed stays at ERROR whatever LOG_LEVEL_DATABASE says")
        assertEquals(Level.INFO, levelOf("io.r2dbc.postgresql.PARAM"), "bound values are never logged")
        assertEquals(
            Level.INFO,
            levelOf("io.r2dbc.postgresql.client.ReactorNettyClient"),
            "nor a server error's detail",
        )
        assertEquals(Level.INFO, levelOf("io.r2dbc.postgresql.PostgresqlConnection"), "nor a server message")
        assertEquals(Level.DEBUG, levelOf("io.r2dbc.postgresql.QUERY"), "the SQL itself, with placeholders, may be")
        assertEquals(Level.DEBUG, levelOf("io.r2dbc.pool.ConnectionPool"))
        assertEquals(Level.DEBUG, levelOf("org.flywaydb.core.Flyway"))
        assertEquals(Level.DEBUG, levelOf("org.postgresql.Driver"))
        assertEquals(Level.TRACE, levelOf("io.lettuce.core.protocol.CommandHandler"))
        assertEquals(Level.TRACE, levelOf("com.circaso.infrastructure.impl.redis.Redis"))
        assertEquals(Level.INFO, levelOf("io.ktor.server.engine.EmbeddedServer"))
        assertEquals(Level.DEBUG, levelOf("com.circaso.integrations.impl.dev.LogSms"))
        assertEquals(Level.DEBUG, levelOf("com.circaso.infrastructure.impl.http.VendorHttpClients"))
        assertEquals(Level.INFO, levelOf("com.circaso.users.domain.usecases.SyncContacts"))
        assertEquals(Level.ERROR, levelOf("com.circaso.messaging.domain.usecases.SendMessage"))
        assertEquals(Level.OFF, levelOf("com.circaso.assistant.domain.usecases.StartTurn"))
        assertEquals(Level.WARN, levelOf("com.circaso.app.Main"), "anything else of Circaso follows LOG_LEVEL")
    }

    @Test
    fun `LOG_LEVEL_CIRCLE moves Circle alone and otherwise follows LOG_LEVEL_INTEGRATIONS`() {
        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL_INTEGRATIONS" to "ERROR")
        assertEquals(Level.ERROR, levelOf("com.circaso.integrations.impl.circle.CircleCustody"))

        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL_INTEGRATIONS" to "ERROR", "LOG_LEVEL_CIRCLE" to "DEBUG")
        assertEquals(Level.DEBUG, levelOf("com.circaso.integrations.impl.circle.CircleWebhook"))
        assertEquals(Level.ERROR, levelOf("com.circaso.integrations.impl.resend.ResendEmail"))
    }

    @Test
    fun `LOG_LEVEL_EXCHANGERATE moves the rates alone and otherwise follows LOG_LEVEL_INTEGRATIONS`() {
        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL_INTEGRATIONS" to "ERROR")
        assertEquals(Level.ERROR, levelOf("com.circaso.integrations.impl.exchangerate.OpenErApiRates"))

        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL_INTEGRATIONS" to "ERROR", "LOG_LEVEL_EXCHANGERATE" to "DEBUG")
        assertEquals(Level.DEBUG, levelOf("com.circaso.integrations.impl.exchangerate.OpenErApiRates"))
        assertEquals(Level.ERROR, levelOf("com.circaso.integrations.impl.circle.CircleCustody"))
    }

    @Test
    fun `LOG_LEVEL_CALLS moves the calls module alone`() {
        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL" to "WARN", "LOG_LEVEL_CALLS" to "DEBUG")
        assertEquals(Level.DEBUG, levelOf("com.circaso.calls.domain.usecases.JoinCall"))
        assertEquals(Level.WARN, levelOf("com.circaso.wallet.domain.usecases.GetWallet"))
    }

    @Test
    fun `LOG_LEVEL_STATUS moves the status module alone`() {
        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL" to "WARN", "LOG_LEVEL_STATUS" to "DEBUG")
        assertEquals(Level.DEBUG, levelOf("com.circaso.status.domain.usecases.PostMoment"))
        assertEquals(Level.WARN, levelOf("com.circaso.calls.domain.usecases.JoinCall"))
    }

    @Test
    fun `LOG_LEVEL_SHARE moves the share module alone`() {
        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL" to "WARN", "LOG_LEVEL_SHARE" to "DEBUG")
        assertEquals(Level.DEBUG, levelOf("com.circaso.share.domain.usecases.CreateShareLink"))
        assertEquals(Level.WARN, levelOf("com.circaso.status.domain.usecases.PostMoment"))
    }

    @Test
    fun `LOG_LEVEL_WALLET moves the wallet module alone`() {
        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL" to "WARN", "LOG_LEVEL_WALLET" to "DEBUG")
        assertEquals(Level.DEBUG, levelOf("com.circaso.wallet.domain.usecases.GetWallet"))
        assertEquals(Level.WARN, levelOf("com.circaso.users.domain.usecases.SyncContacts"))
    }

    @Test
    fun `LOG_LEVEL_OTHER sets the level for everything not named`() {
        configure("LOG_FORMAT" to "PRETTY", "LOG_LEVEL_OTHER" to "ERROR")

        assertEquals(Level.ERROR, levelOf("org.somelibrary.Thing"))
        assertTrue(levelOf("com.circaso.app.Main") == Level.INFO)
    }
}
