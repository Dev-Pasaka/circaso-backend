package com.circaso.ops

import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsRampVerification
import com.circaso.shared.StaffRole
import com.circaso.shared.UserId
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Identity verification and the block list on the ops routes (decision 2026-11-22): FINANCE and ADMIN look up a
 * user's verification and read who is barred; only ADMIN bars, lifts a block or resets a verification, each with a
 * reason, each in the audit log on the person it is about.
 */
class RampKycRoutesTest {
    private val harness = OpsHarness()
    private val ramp = harness.ramp
    private val at = Instant.parse("2026-11-22T12:00:00Z")

    private fun HttpRequestBuilder.signedIn(role: StaffRole) {
        header(OpsHarness.ROLE_HEADER, role.name)
        header("Idempotency-Key", "key-1")
    }

    private suspend fun ApplicationTestBuilder.read(path: String, role: StaffRole = StaffRole.FINANCE): HttpResponse =
        client.get("/v1/ops/ramp$path") { signedIn(role) }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
        role: StaffRole = StaffRole.ADMIN,
    ): HttpResponse =
        client.post("/v1/ops/ramp$path") {
            signedIn(role)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.remove(path: String, role: StaffRole = StaffRole.ADMIN): HttpResponse =
        client.delete("/v1/ops/ramp$path") { signedIn(role) }

    private fun lines(action: String) = harness.audit.entries.filter { it.action == action }

    private fun verification(
        environment: String = "LIVE",
        status: String = "VERIFIED",
        reason: String? = null,
    ) = OpsRampVerification(
        environment = environment,
        status = status,
        reason = reason,
        provider = "didit",
        attempts = 2,
        hasIdentity = status == "VERIFIED" && environment == "LIVE",
        startedAt = at,
        verifiedAt = at.takeIf { status == "VERIFIED" },
        updatedAt = at,
    )

    @Test
    fun `finance and admin read verifications and the block list, support neither, only an admin changes them`() =
        testApplication {
            application { harness.install(this) }
            val user = harness.accounts.add()
            val merchant = ramp.add()

            for (path in listOf("/users/$user/verification", "/blocks")) {
                assertEquals(HttpStatusCode.OK, read(path, StaffRole.FINANCE).status, path)
                assertEquals(HttpStatusCode.OK, read(path, StaffRole.ADMIN).status, path)
                val support = read(path, StaffRole.SUPPORT)
                assertEquals(HttpStatusCode.Forbidden, support.status, path)
                assertTrue("ops.role_forbidden" in support.bodyAsText())
            }
            for (role in listOf(StaffRole.FINANCE, StaffRole.SUPPORT)) {
                val reset = """{"environment":"LIVE","reason":"Lost the old account"}"""
                val refused =
                    listOf(
                        post("/blocks", """{"userId":"$user","reason":"Reversed a payment"}""", role),
                        remove("/blocks/$user?reason=Cleared", role),
                        post("/users/$user/verification/reset", reset, role),
                        post("/merchants/$merchant/verification/reset", reset, role),
                    )
                assertTrue(refused.all { it.status == HttpStatusCode.Forbidden }, "$role")
            }
            assertTrue(ramp.blocked.isEmpty() && ramp.resets.isEmpty() && harness.audit.entries.isEmpty())
        }

    @Test
    fun `a user's verification is shown per environment, with their block, and never a document or a key`() =
        testApplication {
            application { harness.install(this) }
            val user = harness.accounts.add()

            val none = read("/users/$user/verification").bodyAsText()
            assertTrue("\"verifications\":[]" in none && "\"blocked\":false" in none, none)

            ramp.verifications[user] =
                listOf(verification("SANDBOX"), verification("LIVE", "DECLINED", "identity_in_use"))
            val found = read("/users/$user/verification").bodyAsText()
            assertTrue(
                """{"environment":"SANDBOX","status":"VERIFIED","reason":null,"provider":"didit","attempts":2,""" +
                    """"hasIdentity":false,"startedAt":"2026-11-22T12:00:00Z","verifiedAt":"2026-11-22T12:00:00Z",""" +
                    """"updatedAt":"2026-11-22T12:00:00Z"}""" in found,
                found,
            )
            assertTrue("\"status\":\"DECLINED\",\"reason\":\"identity_in_use\"" in found, found)
            assertFalse("identityKey" in found || "sessionToken" in found || "document" in found)

            assertEquals(HttpStatusCode.NotFound, read("/users/${UserId.new()}/verification").status)
            assertEquals(HttpStatusCode.BadRequest, read("/users/not-an-id/verification").status)
        }

    @Test
    fun `barring a user needs a real person and a reason, is audited on them, and shows in the list`() =
        testApplication {
            application { harness.install(this) }
            val user = harness.accounts.add()

            assertEquals(HttpStatusCode.BadRequest, post("/blocks", """{"userId":"$user","reason":""}""").status)
            val stranger = post("/blocks", """{"userId":"${UserId.new()}","reason":"Reversed a payment"}""")
            assertEquals(HttpStatusCode.NotFound, stranger.status)
            assertEquals(HttpStatusCode.BadRequest, post("/blocks", """{"userId":"nope","reason":"Reversed"}""").status)
            assertTrue(ramp.blocked.isEmpty() && harness.audit.entries.isEmpty())

            val barred = post("/blocks", """{"userId":"$user","reason":"Reversed a payment after release"}""")
            assertEquals(HttpStatusCode.OK, barred.status, barred.bodyAsText())
            val body = barred.bodyAsText()
            assertTrue("\"userId\":\"$user\"" in body && "\"reason\":\"OPS\"" in body, body)
            assertTrue("\"note\":\"Reversed a payment after release\"" in body, body)
            val block = ramp.blocked.single()
            assertEquals(harness.ids.getValue(StaffRole.ADMIN).toString(), block.createdBy)

            val line = lines("user.ramp_block").single()
            assertEquals(user.toString() to "user", line.targetId to line.targetType)
            assertEquals("Reversed a payment after release", line.reason)
            assertEquals(AuditOutcome.SUCCEEDED to "blocked (OPS)", line.outcome to line.detail)

            val listed = read("/blocks").bodyAsText()
            assertTrue("\"blockId\":\"${block.blockId}\"" in listed && "\"nextCursor\":null" in listed, listed)
            val seen = read("/users/$user/verification").bodyAsText()
            assertTrue("\"blocked\":true" in seen && "\"block\":{" in seen, seen)

            // Barring someone already barred answers the block there is.
            val again = post("/blocks", """{"userId":"$user","reason":"Reported again"}""").bodyAsText()
            assertTrue("\"blockId\":\"${block.blockId}\"" in again, again)
            assertEquals(1, ramp.blocked.size)
        }

    @Test
    fun `the block list pages newest first`() =
        testApplication {
            application { harness.install(this) }
            val users = List(3) { harness.accounts.add() }
            users.forEach { post("/blocks", """{"userId":"$it","reason":"Reversed a payment"}""") }

            val first = read("/blocks?limit=2").bodyAsText()
            assertTrue("\"userId\":\"${users[2]}\"" in first && "\"userId\":\"${users[1]}\"" in first, first)
            assertFalse("\"userId\":\"${users[0]}\"" in first)
            val cursor = Regex("\"nextCursor\":\"([^\"]+)\"").find(first)!!.groupValues[1]
            assertTrue(cursor.endsWith("~${ramp.blocked[1].blockId}"), cursor)

            val second = read("/blocks?limit=2&cursor=$cursor").bodyAsText()
            assertTrue("\"userId\":\"${users[0]}\"" in second && "\"nextCursor\":null" in second, second)
            assertEquals(HttpStatusCode.BadRequest, read("/blocks?cursor=nonsense").status)
        }

    @Test
    fun `lifting a block needs a reason and is audited, whether or not there was one`() =
        testApplication {
            application { harness.install(this) }
            val user = harness.accounts.add()
            post("/blocks", """{"userId":"$user","reason":"Reversed a payment"}""")

            assertEquals(HttpStatusCode.BadRequest, remove("/blocks/$user").status, "no reason")
            assertEquals(1, ramp.blocked.size)

            val lifted = remove("/blocks/$user?reason=Refunded%20the%20merchant")
            assertEquals(HttpStatusCode.OK, lifted.status, lifted.bodyAsText())
            assertTrue("\"blocked\":false" in lifted.bodyAsText())
            assertTrue(ramp.blocked.isEmpty())
            val line = lines("user.ramp_unblock").single()
            assertEquals(user.toString() to "Refunded the merchant", line.targetId to line.reason)
            assertEquals("unblocked", line.detail)

            remove("/blocks/$user?reason=Checking%20again")
            assertEquals("was not blocked", lines("user.ramp_unblock").last().detail)
        }

    @Test
    fun `resetting a verification names the environment, needs a reason, and is audited on the user or the merchant`() =
        testApplication {
            application { harness.install(this) }
            val user = harness.accounts.add()
            val merchant = ramp.add()
            ramp.verifications[user] = listOf(verification("LIVE", "DECLINED", "identity_in_use"))

            val noReason = post("/users/$user/verification/reset", """{"environment":"LIVE","reason":""}""")
            assertEquals(HttpStatusCode.BadRequest, noReason.status)
            val unknown =
                post("/users/$user/verification/reset", """{"environment":"STAGING","reason":"Lost account"}""")
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertEquals(AuditOutcome.FAILED, lines("user.ramp_verification_reset").single().outcome)
            assertTrue(ramp.resets.isEmpty())

            val reset =
                post("/users/$user/verification/reset", """{"environment":"live","reason":"Lost the old account"}""")
            assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())
            assertTrue("\"verifications\":[]" in reset.bodyAsText(), "answers the user as they stand now")
            assertEquals(Triple(user.toString(), "LIVE", harness.ids.getValue(StaffRole.ADMIN)), ramp.resets.single())
            val line = lines("user.ramp_verification_reset").last()
            assertEquals(user.toString() to "user", line.targetId to line.targetType)
            assertEquals("reset LIVE: removed" to "Lost the old account", line.detail to line.reason)

            val again = post("/users/$user/verification/reset", """{"environment":"LIVE","reason":"Checking"}""")
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals("reset LIVE: nothing to remove", lines("user.ramp_verification_reset").last().detail)

            val forMerchant =
                post(
                    "/merchants/$merchant/verification/reset",
                    """{"environment":"SANDBOX","reason":"Wrong document"}""",
                )
            assertEquals(HttpStatusCode.OK, forMerchant.status, forMerchant.bodyAsText())
            assertTrue("\"removed\":false" in forMerchant.bodyAsText())
            val merchantLine = lines("ramp_merchant.verification_reset").single()
            assertEquals(merchant.toString() to "ramp_merchant", merchantLine.targetId to merchantLine.targetType)
            val missing =
                post(
                    "/merchants/${MerchantId.new()}/verification/reset",
                    """{"environment":"SANDBOX","reason":"Wrong document"}""",
                )
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }

    @Test
    fun `approving a merchant whose identity isn't verified is refused with the ramp's code, and audited as failed`() =
        testApplication {
            application { harness.install(this) }
            val merchant = ramp.add()
            ramp.refusal = "ramp.merchant_kyc_required"

            val refused = post("/merchants/$merchant/approve", """{"reason":"Vetted partner"}""")

            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertTrue("ramp.merchant_kyc_required" in refused.bodyAsText())
            val line = lines("ramp_merchant.approve").single()
            assertEquals(AuditOutcome.FAILED to "ramp.merchant_kyc_required", line.outcome to line.errorCode)
        }

    @Test
    fun `the merchant list and summary show each merchant's verification`() =
        testApplication {
            application { harness.install(this) }
            val merchant = ramp.add("APPROVED")
            ramp.merchants[merchant] =
                ramp.merchants.getValue(merchant).copy(verifications = listOf(verification("SANDBOX")))

            val listed = read("/merchants").bodyAsText()

            assertTrue("\"verifications\":[{\"environment\":\"SANDBOX\",\"status\":\"VERIFIED\"" in listed, listed)
            val summary = read("/merchants/$merchant/summary?network=TESTNET").bodyAsText()
            assertTrue("\"kyc\":{\"environment\":\"SANDBOX\",\"status\":\"VERIFIED\"" in summary, summary)
        }

    @Test
    fun `the KYC settings are read, changed one at a time, and the audit line says what changed`() =
        testApplication {
            application { harness.install(this) }

            val before = read("/settings?network=TESTNET").bodyAsText()
            assertTrue(
                "\"kycProvider\":\"didit\"" in before &&
                    "\"kycEnvironment\":\"SANDBOX\"" in before &&
                    "\"kycWorkflowUser\":\"a3cb5782-d5c1-4c35-832d-ed373d80a0ea\"" in before &&
                    "\"kycWorkflowMerchant\":\"dabdb52a-b4bc-435a-b2bf-a636bb17bad6\"" in before &&
                    "\"merchantPortalUrl\":\"https://merchants.circaso.com\"" in before &&
                    "\"kycAttemptsPerDay\":3" in before &&
                    "\"kycPendingHours\":24" in before &&
                    "\"kycProviders\":[\"didit\"]" in before,
                before,
            )

            fun body(extra: String, version: Int) =
                """{"state":"PREVIEW","chains":["BASE-SEPOLIA"],"orderMinMinor":"1000","orderMaxMinor":"500000",""" +
                    """"dailyLimitMinor":"2000000","promptsPerHour":5,"feeBps":150,"merchantShareBps":7000,""" +
                    """"feeSettleHourUtc":21,"quoteSeconds":60,"paymentWindowSeconds":120,$extra,""" +
                    """"callbackBaseUrl":"https://api.circaso.com","collectionsProvider":"payhero",""" +
                    """"version":$version,"reason":"Turning identity checks on for the pilot"}"""

            suspend fun put(extra: String, version: Int) =
                client.put("/v1/ops/ramp/settings?network=TESTNET") {
                    signedIn(StaffRole.ADMIN)
                    contentType(ContentType.Application.Json)
                    setBody(body(extra, version))
                }

            // A form from before the KYC settings existed sends none of them: they stay as they are.
            val old = put(""""kycRequired":true""", 1).bodyAsText()
            assertTrue("\"kycRequired\":true" in old && "\"kycEnvironment\":\"SANDBOX\"" in old, old)
            assertTrue("\"kycWorkflowUser\":\"a3cb5782-d5c1-4c35-832d-ed373d80a0ea\"" in old, old)
            assertEquals(null, ramp.saved.last().kycProvider, "left out, so the ramp keeps the one in force")

            val changed =
                put(
                    """"kycRequired":true,"kycEnvironment":"live","kycWorkflowUser":" wf-live-user ",""" +
                        """"kycAttemptsPerDay":5,"kycPendingHours":48,"merchantPortalUrl":"https://merchants.test"""",
                    2,
                ).bodyAsText()
            assertTrue(
                "\"kycEnvironment\":\"LIVE\"" in changed &&
                    "\"kycWorkflowUser\":\"wf-live-user\"" in changed &&
                    "\"kycAttemptsPerDay\":5" in changed &&
                    "\"kycPendingHours\":48" in changed &&
                    "\"merchantPortalUrl\":\"https://merchants.test\"" in changed,
                changed,
            )
            val detail = lines("ramp_settings.update").last().detail.orEmpty()
            assertTrue("kycEnvironment SANDBOX→LIVE" in detail, detail)
            assertTrue("kycWorkflowUser a3cb5782-d5c1-4c35-832d-ed373d80a0ea→wf-live-user" in detail, detail)
            assertTrue("kycAttemptsPerDay 3→5" in detail && "kycPendingHours 24→48" in detail, detail)
            assertTrue("merchantPortalUrl https://merchants.circaso.com→https://merchants.test" in detail, detail)
        }
}
