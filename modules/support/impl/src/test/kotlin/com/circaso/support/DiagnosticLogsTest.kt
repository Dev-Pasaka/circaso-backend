package com.circaso.support

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.support.data.repositories.DiagnosticLogRepositoryImpl
import com.circaso.support.data.repositories.SupportExpiry
import com.circaso.support.di.supportDatabaseSpec
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.DiagnosticLog
import com.circaso.support.domain.models.NewDiagnosticLog
import com.circaso.support.domain.support.DiagnosticCodes
import com.circaso.support.presentation.controllers.diagnosticLogController
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SU5 and the staff desk's diagnostic logs (2026-11-13): upload, scrub, code, cap, lookup, expiry, account removal. */
class DiagnosticLogsTest {
    private val harness = SupportHarness()
    private val amina = UserId.new()

    private fun upload(log: String = "2026-11-13T12:22:00Z calls: reconnecting (network)", user: UserId = amina) =
        NewDiagnosticLog(user, "android", "1.4.2 (61)", "14", "Xiaomi 23021RAA2Y", log)

    @Test
    fun `an upload answers a code staff find it by, in any case and without the prefix`() =
        runBlocking {
            val stored = harness.diagnostics.upload(upload()).data()
            assertTrue(Regex("DL-[23456789ABCDEFGHJKMNPQRSTVWXYZ]{6}").matches(stored.code), stored.code)
            assertEquals(stored.createdAt.plus(Duration.ofDays(30)), stored.expiresAt)

            val bare = stored.code.removePrefix("DL-").lowercase()
            val found = harness.logDesk.byCode(bare).data().single()
            assertEquals(stored.logId, found.logId)
            assertEquals("Xiaomi 23021RAA2Y", found.deviceModel)
            assertEquals(amina, harness.logDesk.ofUser(amina, 50).data().single().userId)
            assertEquals(upload().log, harness.logDesk.read(stored.logId).data().log)
            assertTrue(harness.logDesk.byCode("not-a-code").data().isEmpty())
        }

    @Test
    fun `the server scrubs tokens, emails and phone numbers an older build let through`() =
        runBlocking {
            val raw = "auth: Bearer abc.def refresh for amina@example.com, +254 712 345678 eyJhbGciOi.eyJzdWIiOi.sig"
            val stored = harness.diagnostics.upload(upload(raw)).data()
            val text = harness.logDesk.read(stored.logId).data().log
            assertTrue("amina@" !in text && "712" !in text && "eyJ" !in text && "abc.def" !in text, text)
        }

    @Test
    fun `a blank or oversized log and a missing platform are refused, and the sixth in an hour is rate limited`() =
        runBlocking {
            val blank = assertIs<Resource.Error>(harness.diagnostics.upload(upload("  ")))
            assertEquals("support.log_invalid", blank.error.code)
            val big = "x".repeat(2 * 1024 * 1024 + 1)
            assertEquals("support.log_invalid", assertIs<Resource.Error>(harness.diagnostics.upload(upload(big))).error.code)
            val noPlatform = upload().copy(platform = "")
            assertEquals(
                "support.log_invalid",
                assertIs<Resource.Error>(harness.diagnostics.upload(noPlatform)).error.code,
            )

            repeat(5) { harness.diagnostics.upload(upload()).data() }
            val sixth = assertIs<Resource.Error>(harness.diagnostics.upload(upload()))
            assertIs<SupportError.LogRateLimited>(sixth.error)
            harness.clock.advance(Duration.ofHours(1))
            harness.diagnostics.upload(upload()).data()
        }

    @Test
    fun `an expired log is gone, and a deleted account's logs go with it`() =
        runBlocking {
            val old = harness.diagnostics.upload(upload()).data()
            harness.clock.advance(Duration.ofDays(31))
            assertEquals(SupportError.LogNotFound, assertIs<Resource.Error>(harness.logDesk.read(old.logId)).error)

            val fresh = harness.diagnostics.upload(upload()).data()
            harness.removeAccount(amina).data()
            assertTrue(harness.logs.rows.isEmpty())
            assertEquals(SupportError.LogNotFound, assertIs<Resource.Error>(harness.logDesk.read(fresh.logId)).error)
        }

    @Test
    fun `codes normalise from what a person types`() {
        assertEquals("DL-7K3QX9", DiagnosticCodes.normalize(" dl-7k3qx9 "))
        assertEquals("DL-7K3QX9", DiagnosticCodes.normalize("7K3QX9"))
        assertNull(DiagnosticCodes.normalize("DL-7K3QX0"))
        assertNull(DiagnosticCodes.normalize("DL-7K3Q"))
    }

    private fun ApplicationTestBuilder.serve() {
        application {
            installJson()
            installStatusPages()
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(CallPrincipalKey, CallPrincipal(amina, DeviceId.new(), UUID.randomUUID(), Scope.APP))
            }
            routing { route("/v1") { diagnosticLogController(harness.diagnostics) } }
        }
    }

    private suspend fun ApplicationTestBuilder.send(body: String): HttpResponse =
        client.post("/v1/support/diagnostics") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `SU5 answers the code and the expiry, refuses a body over 3 MiB and malformed JSON`() =
        testApplication {
            serve()
            val ok =
                send(
                    """{"platform":"ios","appVersion":"1.4.2 (61)","osVersion":"18.1","deviceModel":"iPhone",""" +
                        """"log":"2026-11-13T12:22:00Z calls: ended, cause=RECONNECT_GAVE_UP\n"}""",
                )
            assertEquals(HttpStatusCode.OK, ok.status)
            val data = ok.json()["data"]!!.jsonObject
            assertTrue(data["code"]!!.jsonPrimitive.content.startsWith("DL-"))
            assertTrue(data["expiresAt"]!!.jsonPrimitive.content.isNotBlank())

            val huge = send("""{"platform":"ios","log":"${"x".repeat(3 * 1024 * 1024)}"}""")
            assertEquals(HttpStatusCode.BadRequest, huge.status)
            assertEquals("support.log_invalid", huge.json()["code"]!!.jsonPrimitive.content)

            assertEquals("request.malformed", send("{").json()["code"]!!.jsonPrimitive.content)
        }

    @Test
    fun `on PostgreSQL a log reads back whole, lists without its text, and the purge deletes it once expired`() =
        runBlocking {
            TestPostgres.truncate("support")
            val database = TestPostgres.database(supportDatabaseSpec)
            val logs = DiagnosticLogRepositoryImpl(database)
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            fun log(code: String, expiresAt: Instant) =
                DiagnosticLog(
                    Uuid7.generate(now), code, amina, "android", "1.4.2 (61)", "14", "Redmi", 11, "a\nb\nc\nd\ne\nf",
                    now, expiresAt,
                )
            val live = log("DL-AAAAAA", now.plus(Duration.ofDays(30)))
            assertTrue(logs.insert(live))
            assertTrue(!logs.insert(log("DL-AAAAAA", now.plus(Duration.ofDays(30)))), "a taken code is refused")
            assertTrue(logs.insert(log("DL-BBBBBB", now.minusSeconds(1))))

            assertEquals(live, logs.find(live.logId))
            assertEquals(live.copy(log = ""), logs.byCode("DL-AAAAAA"))
            assertNull(logs.byCode("DL-BBBBBB"), "an expired log reads as gone")
            assertEquals(listOf(live.logId), logs.ofUser(amina, 10).map { it.logId })

            SupportExpiry(database).run()
            assertEquals(1, logs.deleteOfUser(amina), "the purge took the expired one; deletion the other")
        }
}
