package com.circaso.infrastructure.impl.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingTest {
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val originalOut = System.out
    private val captured = ByteArrayOutputStream()

    @AfterTest
    fun restore() {
        System.setOut(originalOut)
        MDC.clear()
        context.reset()
    }

    /** Boots the real logback.xml in the given format and returns what it wrote to stdout. */
    private fun logWith(format: String, write: (org.slf4j.Logger) -> Unit): String {
        System.setOut(PrintStream(captured, true))
        context.reset()
        context.putProperty("LOG_FORMAT", format)
        context.putProperty("LOG_LEVEL", "DEBUG")
        JoranConfigurator().apply {
            this.context = this@LoggingTest.context
            doConfigure(javaClass.getResource("/logback.xml"))
        }
        // the format that isn't chosen is declared but unreferenced, which logback reports as a warning
        val problems =
            context.statusManager.copyOfStatusList
                .filter { it.level >= ch.qos.logback.core.status.Status.WARN && "not referenced" !in it.message }
        assertTrue(problems.isEmpty(), "logback.xml has configuration problems: $problems")
        write(LoggerFactory.getLogger("com.circaso.identity.presentation.SessionService"))
        return captured.toString()
    }

    /** One real event, built by logback, for the formatter tests. */
    private fun eventOf(
        level: String,
        message: String,
        vararg args: Any,
        mdc: Map<String, String> = emptyMap(),
    ): ILoggingEvent {
        val logger = LoggerFactory.getLogger("com.circaso.gateway.RequestLog") as Logger
        val appender =
            ListAppender<ILoggingEvent>().apply {
                this.context = this@LoggingTest.context
                start()
            }
        logger.level = ch.qos.logback.classic.Level.TRACE
        logger.addAppender(appender)
        mdc.forEach { (k, v) -> MDC.put(k, v) }
        when (level) {
            "debug" -> logger.debug(message, *args)
            "warn" -> logger.warn(message, *args)
            "error" -> logger.error(message, *args)
            else -> logger.info(message, *args)
        }
        return appender.list.single()
    }

    @Test
    fun `the pretty format shows the request id, module and a request line`() {
        val out =
            logWith("PRETTY") { log ->
                MDC.put("requestId", "4bf92f3577b34da6a3ce929d0e0e4736")
                log.info(
                    "out",
                    kv("kind", "response"),
                    kv("http.method", "GET"),
                    kv("http.route", "/v1/me/profile"),
                    kv("http.status", 200),
                    kv("durationMs", 12),
                )
            }

        assertTrue(out.contains("4bf92f35"), out)
        assertTrue(out.contains("identity"), out)
        assertTrue(out.contains("← out GET /v1/me/profile 200 12ms"), out)
        assertFalse(out.contains("\u001B["), "no color codes when stdout isn't a terminal: $out")
    }

    @Test
    fun `the json format carries the request id, module, kind and whole ids as fields`() {
        val out =
            logWith("JSON") { log ->
                MDC.put("requestId", "4bf92f3577b34da6a3ce929d0e0e4736")
                log.info(
                    "out",
                    kv("kind", "response"),
                    kv("http.status", 200),
                    kv("userId", "0193f2c1-7a4e-7c21-9b1e-5f3d2a8c4e61"),
                )
            }
        val json = Json.parseToJsonElement(out.trim().lines().last()).jsonObject

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", json["requestId"]!!.jsonPrimitive.content)
        assertEquals("identity", json["module"]!!.jsonPrimitive.content)
        assertEquals("response", json["kind"]!!.jsonPrimitive.content)
        assertEquals("INFO", json["level"]!!.jsonPrimitive.content)
        assertEquals("0193f2c1-7a4e-7c21-9b1e-5f3d2a8c4e61", json["userId"]!!.jsonPrimitive.content)
        assertTrue(json.containsKey("@timestamp"))
    }

    @Test
    fun `the json format masks a phone number and a token that slip into a message`() {
        val jwt = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl"
        val out =
            logWith("JSON") { log ->
                log.warn("sent code to +254712345678 with $jwt")
            }

        assertFalse(out.contains("+254712345678"), out)
        assertFalse(out.contains(jwt), out)
        assertTrue(out.contains("[phone]") && out.contains("[jwt]"), out)
    }

    @Test
    fun `redaction masks E164 numbers and JWTs and leaves other text alone`() {
        assertEquals("code for +254•••678 ok", Redaction.mask("code for +254712345678 ok"))
        assertEquals("token [jwt] end", Redaction.mask("token eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiIxMjMifQ.c2ln end"))
        assertEquals("nothing to hide: 12345", Redaction.mask("nothing to hide: 12345"))
    }

    @Test
    fun `colors follow the table`() {
        val formatter = LineFormatter(Palette(true))
        val line =
            formatter.line(
                eventOf(
                    "warn",
                    "out",
                    kv("kind", "response"),
                    kv("http.method", "POST"),
                    kv("http.route", "/v1/auth/code"),
                    kv("http.status", 429),
                    kv("durationMs", 450),
                    kv("error.code", "request.rate_limited"),
                ),
            )

        assertTrue(line.contains("\u001B[90m← out\u001B[0m"), line)
        assertTrue(line.contains("\u001B[32mPOST\u001B[0m"), line)
        assertTrue(line.contains("\u001B[33m429\u001B[0m"), line)
        assertTrue(line.contains("\u001B[33m450ms\u001B[0m"), line)
        assertTrue(line.contains("\u001B[33mrequest.rate_limited\u001B[0m"), "error code takes the WARN color: $line")
    }

    @Test
    fun `a slow request is red and a server error is bold red`() {
        val formatter = LineFormatter(Palette(true))
        val line =
            formatter.line(
                eventOf("error", "out", kv("kind", "response"), kv("http.status", 503), kv("durationMs", 1500)),
            )

        assertTrue(line.contains("\u001B[1;31m503\u001B[0m"), line)
        assertTrue(line.contains("\u001B[31m1500ms\u001B[0m"), line)
    }

    @Test
    fun `the console shortens user ids and keeps other metadata as key=value`() {
        val formatter = LineFormatter(Palette(false))
        val line =
            formatter.line(
                eventOf(
                    "info",
                    "attempt failed",
                    kv("vendor", "africastalking"),
                    kv("userId", "0193f2c1-7a4e-7c21-9b1e-5f3d2a8c4e61"),
                ),
            )

        assertEquals("attempt failed vendor=africastalking userId=0193…e61", line)
    }

    @Test
    fun `the module is the second segment of a Circaso logger and the class otherwise`() {
        assertEquals("identity", LineFormatter.moduleOf("com.circaso.identity.domain.SignIn"))
        assertEquals("infrastructure", LineFormatter.moduleOf("com.circaso.infrastructure.impl.logging.Palette"))
        assertEquals("NettyApplicationEngine", LineFormatter.moduleOf("io.ktor.server.netty.NettyApplicationEngine"))
    }
}
