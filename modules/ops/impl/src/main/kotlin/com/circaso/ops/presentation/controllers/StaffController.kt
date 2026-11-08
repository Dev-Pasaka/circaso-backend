package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.AuditFilter
import com.circaso.ops.domain.models.AuditTarget
import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.AddStaffRequest
import com.circaso.ops.presentation.dto.AuditPageResponse
import com.circaso.ops.presentation.dto.CreateSupportAccountRequest
import com.circaso.ops.presentation.dto.StaffDto
import com.circaso.ops.presentation.dto.StaffListResponse
import com.circaso.ops.presentation.dto.SupportAccountDto
import com.circaso.ops.presentation.dto.SupportAccountsResponse
import com.circaso.ops.presentation.dto.UpdateStaffRequest
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.PAGED
import com.circaso.ops.presentation.mappers.TAG_STAFF
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.malformed
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseRole
import com.circaso.ops.presentation.mappers.parseUuid
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.AuditService
import com.circaso.ops.presentation.services.StaffService
import com.circaso.ops.presentation.services.SupportAccountService
import com.circaso.shared.Resource
import com.circaso.shared.StaffRole
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

/** The staff list, the audit log and Circaso's support accounts: ADMIN only. */
fun Route.staffController(
    staff: StaffService,
    audit: AuditService,
    supportAccounts: SupportAccountService,
    settings: OpsSettings,
) {
    route("/ops/staff") {
        staffOnly(Permission.STAFF_MANAGE.roles) {
            staffRoutes(staff)
        }
    }
    route("/ops/audit") {
        staffOnly(Permission.AUDIT_READ.roles) {
            get {
                val result =
                    call.auditFilter().flatMap { filter ->
                        call.auditBefore().flatMap { before ->
                            call.pageLimit(settings).flatMap { audit.list(filter, before, it) }
                        }
                    }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, AuditPageResponse>(
                "OPS · The audit log",
                TAG_STAFF,
                listOf(MALFORMED, FORBIDDEN),
                notes =
                    "${roles("ADMIN")} Every staff action, newest first. Filters: `actor` (staff id), `action` " +
                        "(user.suspend, ticket.reply, …), `targetType`, `targetId`. $PAGED",
            )
        }
    }
    route("/ops/support-accounts") {
        staffOnly(Permission.SUPPORT_ACCOUNTS_MANAGE.roles) {
            supportAccountRoutes(supportAccounts, settings)
        }
    }
}

private fun Route.staffRoutes(staff: StaffService) {
    get {
        val result = staff.list()
        call.respondDefault(result.toDefaultResponse { rows -> StaffListResponse(rows.map { it.toDto() }) })
    }.docs<NoBody, StaffListResponse>(
        "OPS · The staff list",
        TAG_STAFF,
        listOf(FORBIDDEN),
        "${roles("ADMIN")} Bootstrap admins (`OPS_BOOTSTRAP_ADMINS`) come first with `fromConfig: true`.",
    )

    post {
        val request = call.receive<AddStaffRequest>()
        val result = parseRole(request.role).flatMap { staff.add(call.actor(), request.email, it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<AddStaffRequest, StaffDto>(
        "OPS · Add a staff member",
        TAG_STAFF,
        listOf("ops.email_invalid", "ops.staff_exists", MALFORMED, FORBIDDEN),
        notes =
            "${roles("ADMIN")} `email`: what they sign in to Cloudflare Access with; `role`: ADMIN, SUPPORT or " +
                "FINANCE. Audited.",
    )

    patch("{staffId}") {
        val request = call.receive<UpdateStaffRequest>()
        val role: Resource<StaffRole?> =
            request.role?.let { raw -> parseRole(raw).map { it } } ?: Resource.success(null)
        val result =
            parseUuid(call.parameters["staffId"], "staffId").flatMap { id ->
                role.flatMap { staff.update(call.actor(), id, it, request.active) }
            }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<UpdateStaffRequest, StaffDto>(
        "OPS · Change a role, or switch access off or on",
        TAG_STAFF,
        listOf(
            "ops.staff_not_found",
            "ops.cannot_change_self",
            "ops.staff_from_config",
            "ops.staff_changed",
            FORBIDDEN,
        ),
        notes = "${roles("ADMIN")} Takes effect on their next request. Audited.",
    )
}

private fun Route.supportAccountRoutes(supportAccounts: SupportAccountService, settings: OpsSettings) {
    get {
        val result = call.pageLimit(settings).flatMap { supportAccounts.list(call.query("cursor"), it) }
        call.respondDefault(
            result.toDefaultResponse { page ->
                SupportAccountsResponse(page.accounts.map { it.toDto() }, page.nextCursor)
            },
        )
    }.docs<NoBody, SupportAccountsResponse>(
        "OPS2 · Every support account, oldest first",
        TAG_STAFF,
        listOf(MALFORMED, FORBIDDEN),
        "${roles("ADMIN")} Moved from the app gateway (decision 2026-11-08). $PAGED",
    )

    post {
        val request = call.receive<CreateSupportAccountRequest>()
        val result = supportAccounts.create(call.actor(), request.displayName, request.handle)
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<CreateSupportAccountRequest, SupportAccountDto>(
        "OPS1 · Create a Circaso support account",
        TAG_STAFF,
        listOf("users.handle_invalid", "users.handle_taken", MALFORMED, FORBIDDEN),
        notes =
            "${roles("ADMIN")} The account is ACTIVE at once and can never sign in on the app or hold a wallet; " +
                "the handle may be a reserved name such as `circaso`. Audited.",
    )
}

private fun ApplicationCall.auditFilter(): Resource<AuditFilter> {
    val actor = query("actor")?.let { raw -> parseUuid(raw, "actor").map { it } } ?: Resource.success(null)
    val action =
        query("action")?.let { raw ->
            AuditAction.parse(raw)?.let { Resource.success(it) }
                ?: malformed("action", "unknown")
        }
            ?: Resource.success(null)
    val target =
        query("targetType")?.let { raw ->
            AuditTarget.parse(raw)?.let { Resource.success(it) }
                ?: malformed("targetType", "unknown")
        }
            ?: Resource.success(null)
    return actor.flatMap { a -> action.flatMap { b -> target.map { c -> AuditFilter(a, b, c, query("targetId")) } } }
}

private fun ApplicationCall.auditBefore(): Resource<UUID?> =
    query("cursor")?.let { raw -> parseUuid(raw, "cursor").map { it } } ?: Resource.success(null)
