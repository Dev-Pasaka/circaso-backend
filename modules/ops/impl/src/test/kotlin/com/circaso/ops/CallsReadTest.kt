package com.circaso.ops

import com.circaso.calls.api.CallId
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The calls pages of the admin portal (2026-11-13): a person's calls, and one call with each phone's report. */
class CallsReadTest {
    private val harness = OpsHarness()
    private val amina = harness.accounts.add()
    private val baraka = harness.accounts.add()
    private val callId: CallId = harness.calls.dropped(amina, baraka)

    init {
        harness.profiles.name(amina, "Amina W.", "amina")
    }

    private suspend fun ApplicationTestBuilder.read(path: String): HttpResponse =
        client.get(path) { header(OpsHarness.ROLE_HEADER, "SUPPORT") }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `a person's calls list the dropped call with its duration and report count`() =
        testApplication {
            application { harness.install(this) }

            val page = read("/v1/ops/users/$amina/calls").json()["data"]!!.jsonObject
            val line = page["items"]!!.jsonArray.single().jsonObject

            assertEquals(callId.toString(), line["callId"]!!.jsonPrimitive.content)
            assertEquals("DROPPED", line["endReason"]!!.jsonPrimitive.content)
            assertEquals("162", line["durationSeconds"]!!.jsonPrimitive.content)
            assertEquals("2", line["participants"]!!.jsonPrimitive.content)
            assertEquals("1", line["reports"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, page["nextCursor"])
        }

    @Test
    fun `a call shows each participant's name, why they left and their phone's report`() =
        testApplication {
            application { harness.install(this) }

            val call = read("/v1/ops/calls/$callId").json()["data"]!!.jsonObject
            val (caller, callee) = call["participants"]!!.jsonArray.map { it.jsonObject }

            assertEquals("DROPPED", call["endReason"]!!.jsonPrimitive.content)
            assertEquals("Amina W.", caller["name"]!!.jsonPrimitive.content)
            assertEquals("amina", caller["handle"]!!.jsonPrimitive.content)
            assertEquals("RECONNECT_GAVE_UP", caller["leftCause"]!!.jsonPrimitive.content)
            val report = caller["report"]!!.jsonObject
            assertEquals("android", report["platform"]!!.jsonPrimitive.content)
            assertEquals(
                "down",
                report["events"]!!
                    .jsonArray[1]
                    .jsonObject["detail"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "84.0",
                report["samples"]!!
                    .jsonArray[0]
                    .jsonObject["rttMs"]!!
                    .jsonPrimitive.content,
            )
            assertEquals("1", report["summary"]!!.jsonObject["reconnects"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, callee["name"], "no profile: no name")
            assertEquals(JsonNull, callee["leftCause"])
            assertEquals(JsonNull, callee["report"])
        }

    @Test
    fun `an unknown call is ops call_not_found and a bad id is malformed`() =
        testApplication {
            application { harness.install(this) }

            val missing = read("/v1/ops/calls/${Uuid7.generate()}")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals("ops.call_not_found", missing.json()["code"]!!.jsonPrimitive.content)
            assertEquals("request.malformed", read("/v1/ops/calls/nope").json()["code"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, read("/v1/ops/users/${UserId.new()}/calls").status)
        }
}
