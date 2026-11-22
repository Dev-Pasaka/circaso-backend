package com.circaso.ops

import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.EmailTemplateIds
import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.EmailTemplateBook
import com.circaso.shared.StaffRole
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Email template ids (decision 2026-11-22): rows staff set in the admin instead of lines of the environment. The old
 * variables are imported once; the email vendor reads the ids back through a ten-second copy; staff list and set them.
 */
class EmailTemplatesTest {
    private class MovableClock(var now: Instant = Instant.parse("2026-11-22T12:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val harness = OpsHarness()
    private val rows = harness.emailTemplates
    private val seeds = harness.emailSeeds
    private val clock = MovableClock()
    private val warnings = RecordingTemplateWarnings()
    private val book = EmailTemplateBook(rows, seeds, warnings, clock)

    private fun HttpRequestBuilder.signedIn(role: StaffRole) {
        header(OpsHarness.ROLE_HEADER, role.name)
        header("Idempotency-Key", "key-1")
    }

    private suspend fun ApplicationTestBuilder.list(role: StaffRole = StaffRole.ADMIN): HttpResponse =
        client.get("/v1/ops/email-templates") { signedIn(role) }

    private suspend fun ApplicationTestBuilder.set(
        template: String,
        id: String,
        version: Long? = 0,
        reason: String = "Published the new welcome in Resend",
        role: StaffRole = StaffRole.ADMIN,
    ): HttpResponse =
        client.put("/v1/ops/email-templates/$template") {
            signedIn(role)
            contentType(ContentType.Application.Json)
            val versionField = version?.let { "\"version\":$it," }.orEmpty()
            setBody("""{"providerTemplateId":"$id",$versionField"reason":"$reason"}""")
        }

    private fun lines() = harness.audit.entries.filter { it.action == "email_template.set" }

    // The import-once seed

    @Test
    fun `an id in the old variable is imported once, and a later change of the variable is ignored`() =
        runTest {
            seeds.values[EmailTemplate.WELCOME] = "tpl_from_env"

            assertEquals("tpl_from_env", book.idOf(EmailTemplate.WELCOME))
            val imported = rows.rows.getValue(EmailTemplate.WELCOME)
            assertEquals(
                Triple("tpl_from_env", null, 1L),
                Triple(imported.providerTemplateId, imported.updatedBy, imported.version),
            )
            assertNull(book.idOf(EmailTemplate.RECOVERY_STARTED), "no variable, no row: not set")

            // the variable changes, or goes: the row wins, and the variable is never looked at for it again
            seeds.values[EmailTemplate.WELCOME] = "tpl_changed_in_env"
            seeds.asked.clear()
            clock.now = clock.now.plusSeconds(60)
            assertEquals("tpl_from_env", book.idOf(EmailTemplate.WELCOME))
            assertEquals(
                "tpl_from_env",
                EmailTemplateBook(rows, seeds, warnings, clock).idOf(EmailTemplate.WELCOME),
                "a restart too",
            )
            assertFalse(EmailTemplate.WELCOME in seeds.asked)
            seeds.values.remove(EmailTemplate.WELCOME)
            clock.now = clock.now.plusSeconds(60)
            assertEquals("tpl_from_env", book.idOf(EmailTemplate.WELCOME))

            // a template with no row picks its variable up when it first appears, once
            seeds.values[EmailTemplate.RECOVERY_STARTED] = " tpl_recovery "
            clock.now = clock.now.plusSeconds(60)
            assertEquals("tpl_recovery", book.idOf(EmailTemplate.RECOVERY_STARTED))
        }

    @Test
    fun `an id staff set is never overwritten by the variable`() =
        runTest {
            rows.insert(EmailTemplate.WELCOME, "tpl_set_in_admin", "admin@circaso.com", clock.now)
            seeds.values[EmailTemplate.WELCOME] = "tpl_from_env"

            assertEquals("tpl_set_in_admin", book.idOf(EmailTemplate.WELCOME))
            assertEquals("tpl_set_in_admin", book.current().getValue(EmailTemplate.WELCOME).providerTemplateId)
        }

    // The ten-second copy

    @Test
    fun `a change made elsewhere is picked up within ten seconds, and one made here at once`() =
        runTest {
            rows.insert(EmailTemplate.WELCOME, "tpl_v1", "admin@circaso.com", clock.now)
            assertEquals("tpl_v1", book.idOf(EmailTemplate.WELCOME))
            val loads = rows.loads

            rows.update(EmailTemplate.WELCOME, "tpl_v2", 1, "admin@circaso.com", clock.now)
            clock.now = clock.now.plusSeconds(9)
            assertEquals("tpl_v1", book.idOf(EmailTemplate.WELCOME), "the copy is at most ten seconds old")
            assertEquals(loads, rows.loads, "and the table isn't read on every send")
            clock.now = clock.now.plusSeconds(2)
            assertEquals("tpl_v2", book.idOf(EmailTemplate.WELCOME))

            rows.update(EmailTemplate.WELCOME, "tpl_v3", 2, "admin@circaso.com", clock.now)
            book.invalidate()
            assertEquals("tpl_v3", book.idOf(EmailTemplate.WELCOME))
        }

    @Test
    fun `when the table can't be read, the last read stays in force`() =
        runTest {
            rows.insert(EmailTemplate.WELCOME, "tpl_v1", "admin@circaso.com", clock.now)
            assertEquals("tpl_v1", book.idOf(EmailTemplate.WELCOME))

            rows.failure = IllegalStateException("database down")
            clock.now = clock.now.plusSeconds(60)

            assertEquals("tpl_v1", book.idOf(EmailTemplate.WELCOME))
            assertEquals(listOf("IllegalStateException"), warnings.errors)
            val cold = EmailTemplateBook(rows, seeds, RecordingTemplateWarnings(), clock)
            assertNull(cold.idOf(EmailTemplate.WELCOME), "nothing read yet: that email isn't sent, and nothing throws")
        }

    @Test
    fun `the email vendor's contract is answered from the table, through the module's wiring`() =
        runTest {
            val ids = harness.koin.get<EmailTemplateIds>()
            assertNull(ids.idOf(EmailTemplate.MERCHANT_SIGN_IN_CODE))

            seeds.values[EmailTemplate.PIN_VAULT_OPENED] = "tpl_vault"
            rows.insert(EmailTemplate.MERCHANT_SIGN_IN_CODE, "tpl_code", "admin@circaso.com", clock.now)
            val later = OpsHarness.withEmailTemplates(rows, seeds).koin.get<EmailTemplateIds>()

            assertEquals("tpl_code", later.idOf(EmailTemplate.MERCHANT_SIGN_IN_CODE))
            assertEquals("tpl_vault", later.idOf(EmailTemplate.PIN_VAULT_OPENED))
        }

    // The staff routes

    @Test
    fun `finance and admin read the template ids, only an admin sets one`() =
        testApplication {
            application { harness.install(this) }

            assertEquals(setOf(StaffRole.ADMIN, StaffRole.FINANCE), Permission.EMAIL_TEMPLATES_READ.roles)
            assertEquals(setOf(StaffRole.ADMIN), Permission.EMAIL_TEMPLATES_MANAGE.roles)
            assertEquals(HttpStatusCode.OK, list(StaffRole.FINANCE).status)
            assertEquals(HttpStatusCode.OK, list(StaffRole.ADMIN).status)
            assertEquals(HttpStatusCode.Forbidden, list(StaffRole.SUPPORT).status)
            for (role in listOf(StaffRole.FINANCE, StaffRole.SUPPORT)) {
                val refused = set("WELCOME", "tpl_welcome", role = role)
                assertEquals(HttpStatusCode.Forbidden, refused.status, "$role")
                assertTrue("ops.role_forbidden" in refused.bodyAsText())
            }
            assertTrue(rows.rows.isEmpty() && harness.audit.entries.isEmpty())
        }

    @Test
    fun `the list names every email, set or not, and shows an id imported from the old variable`() =
        testApplication {
            application { harness.install(this) }
            seeds.values[EmailTemplate.WELCOME] = "tpl_from_env"

            val body = list().bodyAsText()

            EmailTemplate.entries.forEach { assertTrue("\"template\":\"${it.name}\"" in body, it.name) }
            assertTrue(
                "\"template\":\"WELCOME\",\"providerTemplateId\":\"tpl_from_env\",\"set\":true,\"version\":1" in body,
                body,
            )
            val unset = "\"template\":\"MERCHANT_SIGN_IN_CODE\",\"providerTemplateId\":null,\"set\":false,\"version\":0"
            assertTrue(unset in body, body)
        }

    @Test
    fun `setting an id needs a reason, is version-checked, and is audited with the id before and after`() =
        testApplication {
            application { harness.install(this) }

            val noReason = set("WELCOME", "tpl_welcome", reason = " ")
            assertEquals(HttpStatusCode.BadRequest, noReason.status)
            assertTrue("ops.reason_required" in noReason.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, set("WELCOME", "not an id").status)
            assertEquals(HttpStatusCode.BadRequest, set("WELCOME", "").status)
            assertEquals(HttpStatusCode.NotFound, set("NO_SUCH_EMAIL", "tpl_x").status)
            assertTrue(rows.rows.isEmpty() && harness.audit.entries.isEmpty(), "nothing written, nothing audited")

            val first = set("welcome", "tpl_welcome")
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            assertTrue("\"providerTemplateId\":\"tpl_welcome\",\"set\":true,\"version\":1" in first.bodyAsText())
            assertTrue("\"updatedBy\":\"admin@circaso.com\"" in first.bodyAsText())
            assertEquals(
                "tpl_welcome",
                harness.koin.get<EmailTemplateIds>().idOf(EmailTemplate.WELCOME),
                "in force at once here",
            )

            val stale = set("WELCOME", "tpl_other", version = 0)
            assertEquals(HttpStatusCode.Conflict, stale.status, "someone set it since version 0 was read")
            assertTrue("ops.email_template_changed" in stale.bodyAsText())
            assertEquals(HttpStatusCode.Conflict, set("WELCOME", "tpl_other", version = 7).status)

            val changed = set("WELCOME", "tpl_welcome_v2", version = 1, reason = "New design published")
            assertTrue("\"providerTemplateId\":\"tpl_welcome_v2\",\"set\":true,\"version\":2" in changed.bodyAsText())
            val forced = set("WELCOME", "tpl_welcome_v3", version = null)
            assertTrue("\"version\":3" in forced.bodyAsText(), "no version: save over whatever is there")

            val audited = lines()
            assertEquals(5, audited.size)
            assertTrue(audited.all { it.targetId == "WELCOME" && it.targetType == "email_template" })
            val succeeded = audited.filter { it.outcome == AuditOutcome.SUCCEEDED }
            assertEquals(
                listOf("id (not set)→tpl_welcome", "id tpl_welcome→tpl_welcome_v2", "id tpl_welcome_v2→tpl_welcome_v3"),
                succeeded.map { it.detail },
            )
            assertEquals("New design published", succeeded[1].reason)
            val failed = audited.filter { it.outcome == AuditOutcome.FAILED }
            assertEquals(
                listOf("ops.email_template_changed", "ops.email_template_changed"),
                failed.map { it.errorCode },
            )
        }
}
