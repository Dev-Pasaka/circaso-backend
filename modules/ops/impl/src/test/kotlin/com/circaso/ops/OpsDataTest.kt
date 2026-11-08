package com.circaso.ops

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.ops.data.repositories.AuditRepositoryImpl
import com.circaso.ops.data.repositories.StaffRepositoryImpl
import com.circaso.ops.di.opsDatabaseSpec
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.AuditEntry
import com.circaso.ops.domain.models.AuditFilter
import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.models.Staff
import com.circaso.shared.StaffRole
import com.circaso.shared.Uuid7
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
