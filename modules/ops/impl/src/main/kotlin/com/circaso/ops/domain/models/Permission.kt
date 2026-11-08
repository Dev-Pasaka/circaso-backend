package com.circaso.ops.domain.models

import com.circaso.shared.StaffRole
import com.circaso.shared.StaffRole.ADMIN
import com.circaso.shared.StaffRole.FINANCE
import com.circaso.shared.StaffRole.SUPPORT

/**
 * The one table of who may do what on the ops gateway (decision 2026-11-08, Pasaka's three roles). Routes guard with
 * [roles]; `/v1/ops/me` hands the portal the [wire] names of its role, so the menu is built from the same table the
 * server enforces. ADMIN may do everything; SUPPORT sees people and works tickets but no money; FINANCE sees money,
 * read-only.
 */
enum class Permission(val wire: String, val roles: Set<StaffRole>) {
    DASHBOARD_USERS("dashboard.users", setOf(ADMIN, SUPPORT, FINANCE)),
    DASHBOARD_TICKETS("dashboard.tickets", setOf(ADMIN, SUPPORT)),
    DASHBOARD_MONEY("dashboard.money", setOf(ADMIN, FINANCE)),
    USERS_READ("users.read", setOf(ADMIN, SUPPORT, FINANCE)),
    USERS_DEVICES("users.devices", setOf(ADMIN, SUPPORT)),
    USERS_MONEY("users.money", setOf(ADMIN, FINANCE)),
    USERS_TICKETS("users.tickets", setOf(ADMIN, SUPPORT)),
    USERS_SUSPEND("users.suspend", setOf(ADMIN, SUPPORT)),
    TRANSACTIONS_READ("transactions.read", setOf(ADMIN, FINANCE)),
    FINANCE_READ("finance.read", setOf(ADMIN, FINANCE)),

    /** What Asha's model use costs (decision 2026-11-08): a cost report, so money roles only. */
    AI_USAGE_READ("ai_usage.read", setOf(ADMIN, FINANCE)),
    GROUPS_READ("groups.read", setOf(ADMIN, SUPPORT, FINANCE)),
    GROUPS_MONEY("groups.money", setOf(ADMIN, FINANCE)),
    TICKETS_READ("tickets.read", setOf(ADMIN, SUPPORT)),
    TICKETS_WRITE("tickets.write", setOf(ADMIN, SUPPORT)),
    STAFF_MANAGE("staff.manage", setOf(ADMIN)),
    AUDIT_READ("audit.read", setOf(ADMIN)),
    SUPPORT_ACCOUNTS_MANAGE("support_accounts.manage", setOf(ADMIN)),
    ;

    fun allows(role: StaffRole): Boolean = role in roles

    companion object {
        fun of(role: StaffRole): List<Permission> = entries.filter { it.allows(role) }
    }
}
