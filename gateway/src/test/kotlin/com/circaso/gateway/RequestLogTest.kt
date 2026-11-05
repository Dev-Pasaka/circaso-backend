package com.circaso.gateway

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The request log names the route, never what the caller put in the path or the query: a path parameter can be a
 * handle or an invite token (found in QA).
 */
class RequestLogTest {
    private val captured = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger("com.circaso.gateway.RequestLog") as Logger

    @BeforeTest
    fun capture() {
        captured.start()
        logger.addAppender(captured)
    }

    @AfterTest
    fun release() {
        logger.detachAppender(captured)
    }

    /** Everything a log event carries, as text: the message and every structured field as `key=value`. */
    private fun ILoggingEvent.text(): String =
        buildString {
            append(formattedMessage)
            argumentArray?.forEach { append(' ').append(it) }
        }

    private fun routes(): List<String> =
        captured.list.map { event -> Regex("http.route=(\\S+)").find(event.text())!!.groupValues[1] }

    @Test
    fun `a marker in a path parameter or the query string reaches no log line, and the route is its template`() =
        testApplication {
            application {
                install(RequestLog)
                routing {
                    route("/v1/handles") {
                        get("{handle}/availability") { call.respondText("ok") }
                    }
                    route("/v1/invites") {
                        delete("{token}") { call.respondText("gone") }
                    }
                }
            }

            client.get("/v1/handles/marker_handle_7/availability?q=marker_query_7")
            client.delete("/v1/invites/inv_marker_token_7")
            client.get("/v1/nothing/marker_unmatched_7?x=marker_query_8")

            assertEquals(3, captured.list.size)
            val leaks = captured.list.filter { "marker" in it.text() }
            assertTrue(leaks.isEmpty(), "a marker reached the log: ${leaks.size} line(s)")
            assertEquals(
                listOf("/v1/handles/{handle}/availability", "/v1/invites/{token}", UNMATCHED_ROUTE),
                routes(),
            )
        }

    @Test
    fun `an unmatched route answered by StatusPages is logged once, with its 404`() =
        testApplication {
            application {
                // as `app` installs them
                installJson()
                installStatusPages()
                install(RequestLog)
                routing {
                    get("/v1/known") { call.respondText("ok") }
                }
            }

            val response = client.get("/v1/nothing/here")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(listOf(UNMATCHED_ROUTE), routes(), "one line per request")
            assertTrue("http.status=404" in captured.list.single().text(), captured.list.single().text())
        }

    @Test
    fun `the line says where the request came from and on what device`() =
        testApplication {
            application {
                install(RequestLog)
                routing { get("/v1/chats") { call.respondText("ok") } }
            }

            client.get("/v1/chats") {
                header("CF-Connecting-IP", "203.0.113.9")
                header("CF-IPCountry", "KE")
                header("X-Client-Version", "android/1.0.0")
                header(io.ktor.http.HttpHeaders.UserAgent, "Circaso/1.0.0 (Android 15; Xiaomi 23129RAA4G)")
            }

            val line = captured.list.single().text()
            listOf(
                "client.ip=203.0.113.9",
                "client.country=KE",
                "client.version=android/1.0.0",
                "client.device=Android 15 · Xiaomi 23129RAA4G",
                "client.ua=Circaso/1.0.0 (Android 15; Xiaomi 23129RAA4G)",
            ).forEach { assertTrue(it in line, "missing $it in: $line") }
        }
}
