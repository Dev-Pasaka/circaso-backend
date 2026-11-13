package com.circaso.ops

import com.circaso.ops.domain.models.AuditOutcome
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The diagnostic log pages of the admin portal (2026-11-13): find by code or person, open one, audited. */
class DiagnosticsReadTest {
    private val harness = OpsHarness()
    private val amina = harness.accounts.add()
    private val log = harness.logs.add(amina)

    private suspend fun ApplicationTestBuilder.read(path: String): HttpResponse =
        client.get(path) { header(OpsHarness.ROLE_HEADER, "SUPPORT") }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `a log is found by its code in any case and by its person, as a line without the text`() =
        testApplication {
            application { harness.install(this) }
            for (path in listOf("/v1/ops/diagnostics?code=dl-7k3qx9", "/v1/ops/diagnostics?userId=$amina")) {
                val item =
                    read(path)
                        .json()["data"]!!
                        .jsonObject["items"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(log.logId.toString(), item["logId"]!!.jsonPrimitive.content, path)
                assertEquals("DL-7K3QX9", item["code"]!!.jsonPrimitive.content)
                assertTrue("log" !in item)
            }
            val none = read("/v1/ops/diagnostics?code=DL-222222").json()["data"]!!.jsonObject["items"]!!.jsonArray
            assertTrue(none.isEmpty())
        }

    @Test
    fun `opening a log answers its text and writes an audit line naming the code and the person`() =
        testApplication {
            application { harness.install(this) }
            val body = read("/v1/ops/diagnostics/${log.logId}").json()["data"]!!.jsonObject
            assertTrue(body["log"]!!.jsonPrimitive.content.contains("RECONNECT_GAVE_UP"))

            val line = harness.audit.entries.single()
            assertEquals("diagnostics.read", line.action)
            assertEquals("diagnostic_log", line.targetType)
            assertEquals(log.logId.toString(), line.targetId)
            assertEquals(AuditOutcome.SUCCEEDED, line.outcome)
            assertEquals("DL-7K3QX9 of user $amina", line.detail)
        }

    @Test
    fun `a log that isn't there is ops diagnostic_log_not_found`() =
        testApplication {
            application { harness.install(this) }
            val missing = read("/v1/ops/diagnostics/${com.circaso.shared.Uuid7.generate()}")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals("ops.diagnostic_log_not_found", missing.json()["code"]!!.jsonPrimitive.content)
            assertEquals(
                "request.malformed",
                read("/v1/ops/diagnostics/${UUID.randomUUID()}x").json()["code"]!!.jsonPrimitive.content,
            )
        }
}
