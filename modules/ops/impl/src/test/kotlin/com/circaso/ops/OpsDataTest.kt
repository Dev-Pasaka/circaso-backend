package com.circaso.ops

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.EmailTemplate
import com.circaso.ops.data.repositories.AuditRepositoryImpl
import com.circaso.ops.data.repositories.EmailTemplateRepositoryImpl
import com.circaso.ops.data.repositories.StaffRepositoryImpl
import com.circaso.ops.di.opsDatabaseSpec
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.AuditEntry
import com.circaso.ops.domain.models.AuditFilter
import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.models.EmailTemplateId
import com.circaso.ops.domain.models.Staff
import com.circaso.shared.StaffRole
import com.circaso.shared.Uuid7
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ops.staff` and `ops.audit_log` against PostgreSQL, migrated by the module's own Flyway scripts. */
class OpsDataTest {
    private val database = TestPostgres.database(opsDatabaseSpec)
    private val staff = StaffRepositoryImpl(database)
    private val audit = AuditRepositoryImpl(database)
    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @AfterTest
    fun clean() = TestPostgres.truncate("ops")

    private fun member(email: String) =
        Staff(Uuid7.generate(), email, StaffRole.SUPPORT, true, "admin@circaso.com", now, now)

    private fun line(
        action: AuditAction,
        target: String,
        at: Instant = now,
    ) = AuditEntry(
        id = Uuid7.generate(at),
        actorId = UUID.randomUUID(),
        actorEmail = "support@circaso.com",
        actorRole = StaffRole.SUPPORT,
        action = action.wire,
        targetType = action.target.wire,
        targetId = target,
        reason = "why",
        detail = null,
        outcome = AuditOutcome.PENDING,
        errorCode = null,
        requestId = "req",
        at = at,
    )

    @Test
    fun `one row per email, and an update at a stale version is refused`() =
        runTest {
            val row = member("agent@circaso.com")

            assertTrue(staff.insert(row))
            assertFalse(staff.insert(member("agent@circaso.com")))
            assertTrue(staff.update(row.copy(role = StaffRole.FINANCE)))
            assertFalse(staff.update(row.copy(active = false)), "version 0 is stale now")
            assertEquals(StaffRole.FINANCE, staff.findByEmail("agent@circaso.com")?.role)
            assertEquals(setOf(row.id), staff.findMany(setOf(row.id, UUID.randomUUID())).keys)
        }

    @Test
    fun `the audit log pages newest first, filters, and finds the latest succeeded suspension`() =
        runTest {
            val user = UUID.randomUUID().toString()
            val older = line(AuditAction.USER_SUSPEND, user, now.minusSeconds(60))
            val newer = line(AuditAction.USER_SUSPEND, user, now)
            val reply = line(AuditAction.TICKET_REPLY, "t-1", now.minusSeconds(30))
            listOf(older, newer, reply).forEach { audit.begin(it) }
            audit.finish(older.id, AuditOutcome.SUCCEEDED, null, now)
            audit.finish(newer.id, AuditOutcome.FAILED, "identity.account_not_active", now)

            val first = audit.list(AuditFilter(), before = null, limit = 2)
            val rest = audit.list(AuditFilter(), before = first.last().id, limit = 2)
            val suspensions = audit.list(AuditFilter(action = AuditAction.USER_SUSPEND), null, 10)

            assertEquals(listOf(newer.id, reply.id), first.map { it.id })
            assertEquals(listOf(older.id), rest.map { it.id })
            assertEquals(2, suspensions.size)
            assertEquals(older.id, audit.latestSucceeded("user.suspend", user)?.id)
            assertEquals("identity.account_not_active", first.first().errorCode)
        }

    @Test
    fun `an email template's first row is written once, and a change needs the version it was read at`() =
        runBlocking<Unit> {
            val templates = EmailTemplateRepositoryImpl(database)

            assertTrue(templates.load().isEmpty())
            assertTrue(templates.insert(EmailTemplate.WELCOME, "tpl_from_env", by = null, at = now))
            assertFalse(templates.insert(EmailTemplate.WELCOME, "tpl_other", by = "admin@circaso.com", at = now))
            val imported = templates.load().getValue(EmailTemplate.WELCOME)
            assertEquals(EmailTemplateId(EmailTemplate.WELCOME, "tpl_from_env", null, now, 1), imported)

            val changed = templates.update(EmailTemplate.WELCOME, "tpl_v2", 1, "admin@circaso.com", now)
            assertEquals(
                Triple("tpl_v2", "admin@circaso.com", 2L),
                changed?.let {
                    Triple(it.providerTemplateId, it.updatedBy, it.version)
                },
            )
            assertNull(
                templates.update(EmailTemplate.WELCOME, "tpl_stale", 1, "other@circaso.com", now),
                "a stale save",
            )
            assertNull(
                templates.update(EmailTemplate.RECOVERY_STARTED, "tpl_x", null, "admin@circaso.com", now),
                "no row",
            )
            assertEquals(
                "tpl_v3",
                templates.update(EmailTemplate.WELCOME, "tpl_v3", null, "a@circaso.com", now)?.providerTemplateId,
            )

            val racing =
                List(6) { async { templates.update(EmailTemplate.WELCOME, "tpl_race_$it", 3, "a@circaso.com", now) } }
            assertEquals(1, racing.awaitAll().count { it != null }, "one of six saves at the same version wins")
            val seeding = List(6) { async { templates.insert(EmailTemplate.PIN_VAULT_OPENED, "tpl_seed", null, now) } }
            assertEquals(1, seeding.awaitAll().count { it }, "two processes importing the same seed write one row")
        }
}
