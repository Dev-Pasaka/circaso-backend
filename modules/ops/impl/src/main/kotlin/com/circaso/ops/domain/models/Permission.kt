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

    /** Deleting unfinished sign-ups now rather than at the 30-day sweep (2026-11-13). */
    USERS_CLEANUP("users.cleanup", setOf(ADMIN)),
    TRANSACTIONS_READ("transactions.read", setOf(ADMIN, FINANCE)),
    FINANCE_READ("finance.read", setOf(ADMIN, FINANCE)),

    /** What Asha's model use costs (decision 2026-11-08): a cost report, so money roles only. */
    AI_USAGE_READ("ai_usage.read", setOf(ADMIN, FINANCE)),
    GROUPS_READ("groups.read", setOf(ADMIN, SUPPORT, FINANCE)),
    GROUPS_MONEY("groups.money", setOf(ADMIN, FINANCE)),
    TICKETS_READ("tickets.read", setOf(ADMIN, SUPPORT)),
    TICKETS_WRITE("tickets.write", setOf(ADMIN, SUPPORT)),

    /** A person's calls and each call's reports from the phones, to see why a call dropped (2026-11-13). */
    CALLS_READ("calls.read", setOf(ADMIN, SUPPORT)),

    /** Diagnostic logs phones sent from You → Help (2026-11-13); opening one is audited. */
    DIAGNOSTICS_READ("diagnostics.read", setOf(ADMIN, SUPPORT)),
    STAFF_MANAGE("staff.manage", setOf(ADMIN)),
    AUDIT_READ("audit.read", setOf(ADMIN)),
    SUPPORT_ACCOUNTS_MANAGE("support_accounts.manage", setOf(ADMIN)),
    FEES_READ("fees.read", setOf(ADMIN, FINANCE)),
    FEES_MANAGE("fees.manage", setOf(ADMIN)),

    /** Granting or revoking a person's access to a wallet network (2026-11-18). */
    USERS_NETWORK("users.network", setOf(ADMIN)),

    /** Reading and changing the wallet networks' settings: state, app id, chains and assets (2026-11-18). */
    WALLET_SETTINGS("wallet.settings", setOf(ADMIN)),

    /** Circaso's fee wallets, their balances, the withdrawal destinations and the withdrawals (2026-11-18). */
    FEES_WALLET_READ("fees.wallet.read", setOf(ADMIN, FINANCE)),

    /** Adding or removing a withdrawal destination, and withdrawing collected fees to one (2026-11-18). */
    FEES_WITHDRAW("fees.withdraw", setOf(ADMIN)),

    /**
     * The ramp's merchants, its settings, its preview list, the orders waiting for a person, a user's identity
     * verification and the block list (2026-11-22).
     */
    RAMP_READ("ramp.read", setOf(ADMIN, FINANCE)),

    /**
     * Approving or suspending a merchant, changing the ramp's settings and its preview list, barring a user from the
     * ramp or lifting that, and resetting a verification (2026-11-22).
     */
    RAMP_MANAGE("ramp.manage", setOf(ADMIN)),

    /** Reading which provider template each email is sent with (2026-11-22). */
    EMAIL_TEMPLATES_READ("email_templates.read", setOf(ADMIN, FINANCE)),

    /** Setting an email's provider template id (2026-11-22). */
    EMAIL_TEMPLATES_MANAGE("email_templates.manage", setOf(ADMIN)),
    ;

    fun allows(role: StaffRole): Boolean = role in roles

    companion object {
        fun of(role: StaffRole): List<Permission> = entries.filter { it.allows(role) }
    }
}
