package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.presentation.dto.DashboardResponse
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.TAG_DASHBOARD
import com.circaso.ops.presentation.mappers.malformed
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.DashboardService
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staff
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val DEFAULT_DAYS = 30

fun Route.dashboardController(dashboard: DashboardService) {
    route("/ops/dashboard") {
        staffOnly(Permission.DASHBOARD_USERS.roles) {
            get {
                val days =
                    call.query("days")?.let { raw ->
                        raw.toIntOrNull()?.let { Resource.success(it) } ?: malformed("days", "must be a number")
                    } ?: Resource.success(DEFAULT_DAYS)
                val result = days.flatMap { dashboard.get(call.staff().role, it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, DashboardResponse>(
                "OPS · The dashboard",
                TAG_DASHBOARD,
                listOf(MALFORMED),
                notes =
                    "${roles("any")} `?days=30` (1-366). Each role gets its tiles; the others are null: users, " +
                        "active and groups for every role, transactions and fees for FINANCE and ADMIN, tickets for " +
                        "SUPPORT and ADMIN. Money in integer micro-dollars.",
            )
        }
    }
}
