package com.circaso.ops

import com.circaso.ops.domain.models.AuditOutcome
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fees and limits on the ops routes (decision 2026-11-08): every change needs a reason, is the wallet's to make, and
 * lands in the audit log with the version before and after, as the wallet reports them.
 */
class FeeRoutesTest {
    private val harness = OpsHarness()
    private val rule = harness.fees.feeRule

    private suspend fun ApplicationTestBuilder.send(
        method: String,
        path: String,
        body: String,
    ): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(OpsHarness.ROLE_HEADER, "ADMIN")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (method == "PATCH") client.patch(path, build) else client.post(path, build)
    }

    @Test
    fun `a fee change is a new version, audited with what was there before and after`() =
        testApplication {
            application { harness.install(this) }

            val response =
                send(
                    "POST",
                    "/v1/ops/fees/rules/$rule/versions",
                    """{"baseVersion":1,"terms":{"rateBps":75,"minFeeUsdMicros":100000},"reason":"Q4 pricing"}""",
                )

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.bodyAsText()
            assertTrue(body.contains("\"before\":{\"ruleId\":\"$rule\",\"version\":1"), body)
            assertTrue(body.contains("\"version\":2"), body)
            assertEquals(
                listOf(1, 2),
                harness.fees.fees
                    .getValue(rule)
                    .map { it.version },
            )
            val line = harness.audit.entries.single()
            assertEquals("fee_rule.change", line.action)
            assertEquals("fee_rule", line.targetType)
            assertEquals(rule.toString(), line.targetId)
            assertEquals("Q4 pricing", line.reason)
            assertEquals(AuditOutcome.SUCCEEDED, line.outcome)
            val detail = line.detail.orEmpty()
            assertTrue(detail.startsWith("before v1 {rate 50 bps, cap \$10000, min none"), detail)
            assertTrue(detail.contains("→ after v2 {rate 75 bps, cap \$10000, min \$0.1"), detail)
        }

    @Test
    fun `a change without a reason is refused before the wallet is asked, and leaves no line`() =
        testApplication {
            application { harness.install(this) }

            val response =
                send(
                    "POST",
                    "/v1/ops/fees/rules/$rule/versions",
                    """{"baseVersion":1,"terms":{"rateBps":75},"reason":" "}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("ops.reason_required"))
            assertEquals(
                1,
                harness.fees.fees
                    .getValue(rule)
                    .size,
            )
            assertTrue(harness.audit.entries.isEmpty())
        }

    @Test
    fun `a stale version is the wallet's conflict, audited as failed with its code`() =
        testApplication {
            application { harness.install(this) }

            val response =
                send(
                    "POST",
                    "/v1/ops/fees/rules/$rule/versions",
                    """{"baseVersion":7,"terms":{"rateBps":75},"reason":"Q4 pricing"}""",
                )

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("wallet.rule_changed"))
            val line = harness.audit.entries.single()
            assertEquals(AuditOutcome.FAILED, line.outcome)
            assertEquals("wallet.rule_changed", line.errorCode)
            assertTrue(line.detail.orEmpty().startsWith("requested {rate 75 bps"), line.detail)
        }

    @Test
    fun `a time that isn't an instant is malformed`() =
        testApplication {
            application { harness.install(this) }

            val response =
                send(
                    "POST",
                    "/v1/ops/fees/rules/$rule/versions",
                    """{"baseVersion":1,"terms":{"rateBps":75},"effectiveFrom":"tomorrow","reason":"Q4 pricing"}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("effectiveFrom"))
        }

    @Test
    fun `new rules name themselves in the audit line, and the bounds keep before and after`() =
        testApplication {
            application { harness.install(this) }

            send(
                "POST",
                "/v1/ops/fees/rules",
                """{"scope":{"kind":"EXTERNAL","blockchain":"BASE"},"terms":{"rateBps":150},"reason":"Base launch"}""",
            )
            send("PATCH", "/v1/ops/fees/bounds", """{"minRateBps":0,"maxRateBps":300,"reason":"Board approved 3 %"}""")
            send(
                "POST",
                "/v1/ops/limits/rules/${harness.fees.limitRule}/end",
                """{"baseVersion":1,"reason":"KYC tiers replace it"}""",
            )

            val (created, bounds, ended) = harness.audit.entries
            assertEquals("fee_rule.create", created.action)
            assertTrue(created.detail.orEmpty().startsWith("new rule "), created.detail)
            assertEquals("fee_bounds.update", bounds.action)
            assertEquals("before {0-200 bps} → after {0-300 bps}", bounds.detail)
            assertEquals("limit_rule.end", ended.action)
            assertTrue(ended.detail.orEmpty().contains("per payment \$500"), ended.detail)
        }
}
