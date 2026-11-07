package com.circaso.ops.domain.models

import com.circaso.shared.StaffRole
import java.time.Instant
import java.util.UUID

/**
 * An audit line goes in as [PENDING] before the action runs and is finished as [SUCCEEDED] or [FAILED] after, so an
 * action can never happen without its line: if the process dies half way, the line stays `PENDING` and says so.
 */
enum class AuditOutcome { PENDING, SUCCEEDED, FAILED }

/** What a staff action touched. */
enum class AuditTarget(val wire: String) {
    USER("user"),
    TICKET("ticket"),
    STAFF("staff"),
    SUPPORT_ACCOUNT("support_account"),
    ;

    companion object {
        fun parse(raw: String): AuditTarget? = entries.firstOrNull { it.wire == raw }
    }
}

/** Every staff action the audit log names. The wire form is what the portal filters on. */
enum class AuditAction(val wire: String, val target: AuditTarget) {
    USER_SUSPEND("user.suspend", AuditTarget.USER),
    USER_UNSUSPEND("user.unsuspend", AuditTarget.USER),
    TICKET_REPLY("ticket.reply", AuditTarget.TICKET),
    TICKET_NOTE("ticket.note", AuditTarget.TICKET),
    TICKET_ASSIGN("ticket.assign", AuditTarget.TICKET),
    TICKET_STATUS("ticket.status", AuditTarget.TICKET),
    STAFF_ADD("staff.add", AuditTarget.STAFF),
    STAFF_UPDATE("staff.update", AuditTarget.STAFF),
    STAFF_DEV_LOGIN("staff.dev_login", AuditTarget.STAFF),
    SUPPORT_ACCOUNT_CREATE("support_account.create", AuditTarget.SUPPORT_ACCOUNT),
    ;

    companion object {
        fun parse(raw: String): AuditAction? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * One staff action: who ([actorId], their email and role at the time), what ([action]), on what ([targetId]), why
 * ([reason], required for a suspension), when ([at]) and how it ended. [detail] is a short, non-personal note of what
 * changed (`role FINANCE→SUPPORT`, `status RESOLVED`); never a message body.
 */
data class AuditEntry(
    val id: UUID,
    val actorId: UUID,
    val actorEmail: String,
    val actorRole: StaffRole,
    /** [AuditAction.wire]; kept as text so a line written by a newer build still reads after a rollback. */
    val action: String,
    val targetType: String,
    val targetId: String?,
    val reason: String?,
    val detail: String?,
    val outcome: AuditOutcome,
    val errorCode: String?,
    val requestId: String?,
    val at: Instant,
) {
    override fun toString(): String = "AuditEntry($id, $action, $outcome)"
}

/** The audit page's filters; every one optional. */
data class AuditFilter(
    val actorId: UUID? = null,
    val action: AuditAction? = null,
    val target: AuditTarget? = null,
    val targetId: String? = null,
)

data class AuditPage(val items: List<AuditEntry>, val nextCursor: String?)
