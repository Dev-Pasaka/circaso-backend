package com.circaso.shared

/**
 * What a Circaso staff member may do on the ops gateway (decision 2026-11-08, Pasaka). Every staff call carries the
 * [Scope.OPS] scope; the role is checked on top of it, route by route, on the server.
 *
 * - [ADMIN]: everything, including the staff list and the audit log.
 * - [SUPPORT]: users (account, devices, sessions, groups), suspend and unsuspend, support tickets. No money totals.
 * - [FINANCE]: money, read-only: per user (wallets, transfers, fees paid) and platform-wide (fees, volumes).
 */
enum class StaffRole {
    ADMIN,
    SUPPORT,
    FINANCE,
    ;

    companion object {
        fun parse(raw: String): StaffRole? = entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}
