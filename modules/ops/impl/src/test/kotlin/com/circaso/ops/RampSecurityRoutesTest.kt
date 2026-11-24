package com.circaso.ops

import com.circaso.shared.StaffRole
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ramp merchant security on the ops routes (2026-11-24): finance and admin read a merchant's lock and the settings;
 * only an admin unlocks or changes the setting, with a reason, audited.
 */
class RampSecurityRoutesTest {
    private val harness = OpsHarness()
    private val security = harness.rampSecurity

    private fun HttpRequestBuilder.signedIn(role: StaffRole) {
        header(OpsHarness.ROLE_HEADER, role.name)
        header("Idempotency-Key", "key-1")
    }

    @Test
    fun `finance reads a merchant's security, only an admin unlocks it, with a reason, audited`() =
        testApplication {
            application { harness.install(this) }
            val id = security.lockedMerchant()
            val read = client.get("/v1/ops/ramp/merchants/$id/security") { signedIn(StaffRole.FINANCE) }
            assertEquals(HttpStatusCode.OK, read.status)
            assertTrue("\"locked\":true" in read.bodyAsText() && "\"lockedByLink\":true" in read.bodyAsText())
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/v1/ops/ramp/merchants/$id/security") { signedIn(StaffRole.SUPPORT) }.status,
            )

            suspend fun unlock(role: StaffRole, reason: String) =
                client.post("/v1/ops/ramp/merchants/$id/unlock-withdrawals") {
                    signedIn(role)
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"$reason"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, unlock(StaffRole.FINANCE, "x").status)
            assertTrue("ops.reason_required" in unlock(StaffRole.ADMIN, " ").bodyAsText())
            val done = unlock(StaffRole.ADMIN, "spoke to the owner")
            assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
            assertTrue("\"locked\":false" in done.bodyAsText())
            val line = harness.audit.entries.single { it.action == "ramp_merchant.withdrawals_unlock" }
            assertEquals(id.toString() to "spoke to the owner", line.targetId to line.reason)
            assertEquals(HttpStatusCode.Conflict, unlock(StaffRole.ADMIN, "again").status, "ramp.not_locked")
        }

    @Test
    fun `the passkey-required setting is read by finance and changed by an admin, version-checked`() =
        testApplication {
            application { harness.install(this) }
            val read = client.get("/v1/ops/ramp/security-settings") { signedIn(StaffRole.FINANCE) }
            assertTrue("\"merchantPasskeyRequired\":false" in read.bodyAsText())

            suspend fun change(version: Int) =
                client.put("/v1/ops/ramp/security-settings") {
                    signedIn(StaffRole.ADMIN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"merchantPasskeyRequired":true,"version":$version,"reason":"all merchants"}""")
                }
            val changed = change(1)
            assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
            assertTrue("\"merchantPasskeyRequired\":true" in changed.bodyAsText())
            assertEquals(HttpStatusCode.Conflict, change(1).status)
            assertEquals(
                1,
                harness.audit.entries.count {
                    it.action == "ramp_security_settings.update" &&
                        it.outcome.name == "SUCCEEDED"
                },
            )
        }
}
