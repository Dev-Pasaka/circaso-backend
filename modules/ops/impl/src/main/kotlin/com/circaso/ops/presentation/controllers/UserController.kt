package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.GroupListResponse
import com.circaso.ops.presentation.dto.HoldingsResponse
import com.circaso.ops.presentation.dto.PendingSignUpsDeletedResponse
import com.circaso.ops.presentation.dto.PendingSignUpsResponse
import com.circaso.ops.presentation.dto.ReasonRequest
import com.circaso.ops.presentation.dto.TicketListResponse
import com.circaso.ops.presentation.dto.UserAccountResponse
import com.circaso.ops.presentation.dto.UserDevicesResponse
import com.circaso.ops.presentation.dto.UserPageResponse
import com.circaso.ops.presentation.dto.UserTransfersResponse
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.PAGED
import com.circaso.ops.presentation.mappers.TAG_USERS
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseUserId
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.MoneyService
import com.circaso.ops.presentation.services.UserService
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val NOT_FOUND = "ops.user_not_found"

/** People: search and the user page's sections, each behind the permission its data needs. */
fun Route.userController(
    users: UserService,
    money: MoneyService,
    settings: OpsSettings,
) {
    route("/ops/users") {
        staffOnly(Permission.USERS_READ.roles) {
            get {
                val result =
                    call.pageLimit(settings).flatMap { limit ->
                        users.search(
                            call.query("q").orEmpty(),
                            call.query("cursor"),
                            limit,
                            includePending = call.query("pending") == "true",
                        )
                    }
                call.respondDefault(
                    result.toDefaultResponse { page ->
                        UserPageResponse(page.items.map { it.toDto() }, page.nextCursor)
                    },
                )
            }.docs<NoBody, UserPageResponse>(
                "OPS · Search people",
                TAG_USERS,
                listOf(MALFORMED),
                notes =
                    "${roles("any")} `q`: a user id, an @handle (prefix), a phone number (+254…, exact), an email " +
                        "(exact) or a name (prefix). Empty: the newest accounts, without unfinished sign-ups (no " +
                        "phone yet) unless `pending=true`. $PAGED",
            )

            get("pending-signups") {
                call.respondDefault(
                    users.pendingSignUps().toDefaultResponse { PendingSignUpsResponse(it) },
                )
            }.docs<NoBody, PendingSignUpsResponse>(
                "OPS · Unfinished sign-ups",
                TAG_USERS,
                emptyList(),
                notes =
                    "${roles("any")} Accounts that signed in with Google or Apple but never verified a phone, " +
                        "started over an hour ago: what a clean-up would delete now.",
            )
        }
        staffOnly(Permission.USERS_CLEANUP.roles) {
            post("pending-signups/delete") {
                call.respondDefault(
                    users.deletePendingSignUps(call.actor()).toDefaultResponse { PendingSignUpsDeletedResponse(it) },
                )
            }.docs<NoBody, PendingSignUpsDeletedResponse>(
                "OPS · Delete unfinished sign-ups",
                TAG_USERS,
                listOf(FORBIDDEN),
                notes =
                    "${roles("ADMIN")} Audited. Deletes accounts still waiting for a phone that started over an hour " +
                        "ago, with their logins, as the 30-day sweep would. Never activated, so nothing else knows them.",
            )
        }
        route("/{userId}") {
            accountRoutes(users)
            moneyRoutes(money, settings)
        }
    }
}

private fun Route.accountRoutes(users: UserService) {
    staffOnly(Permission.USERS_READ.roles) {
        get {
            val result = parseUserId(call.parameters["userId"]).flatMap { users.account(it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, UserAccountResponse>(
            "OPS · A person's account",
            TAG_USERS,
            listOf(NOT_FOUND, MALFORMED),
            notes = "${roles("any")} Phone and emails masked. `suspension` while suspended: since, why, by whom.",
        )

        get("groups") {
            val result = parseUserId(call.parameters["userId"]).flatMap { users.groups(it) }
            call.respondDefault(result.toDefaultResponse { rows -> GroupListResponse(rows.map { it.toDto() }) })
        }.docs<NoBody, GroupListResponse>("OPS · A person's groups", TAG_USERS, listOf(NOT_FOUND), roles("any"))
    }
    staffOnly(Permission.USERS_DEVICES.roles) {
        get("devices") {
            val result = parseUserId(call.parameters["userId"]).flatMap { users.devices(it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, UserDevicesResponse>(
            "OPS · A person's phones and sessions",
            TAG_USERS,
            listOf(NOT_FOUND, FORBIDDEN),
            roles("SUPPORT", "ADMIN"),
        )
    }
    staffOnly(Permission.USERS_TICKETS.roles) {
        get("tickets") {
            val result = parseUserId(call.parameters["userId"]).flatMap { users.tickets(it) }
            call.respondDefault(result.toDefaultResponse { rows -> TicketListResponse(rows.map { it.toDto() }) })
        }.docs<NoBody, TicketListResponse>(
            "OPS · A person's support tickets",
            TAG_USERS,
            listOf(NOT_FOUND, FORBIDDEN),
            roles("SUPPORT", "ADMIN"),
        )
    }
    staffOnly(Permission.USERS_SUSPEND.roles) {
        post("suspend") {
            val request = call.receive<ReasonRequest>()
            val result =
                parseUserId(call.parameters["userId"]).flatMap { users.suspend(call.actor(), it, request.reason) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<ReasonRequest, UserAccountResponse>(
            "OPS · Suspend a person",
            TAG_USERS,
            listOf("ops.reason_required", "identity.account_not_found", "identity.account_not_active", FORBIDDEN),
            notes =
                "${roles("SUPPORT", "ADMIN")} Audited with the reason. Every session ends at once, sign-in answers " +
                    "`identity.account_suspended`, the profile hides and payments answer `wallet.account_restricted`.",
        )

        post("unsuspend") {
            val request = call.receive<ReasonRequest>()
            val result =
                parseUserId(call.parameters["userId"]).flatMap { users.unsuspend(call.actor(), it, request.reason) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<ReasonRequest, UserAccountResponse>(
            "OPS · Give a suspended account back",
            TAG_USERS,
            listOf("ops.reason_required", "identity.account_not_found", "identity.account_not_suspended", FORBIDDEN),
            notes = "${roles("SUPPORT", "ADMIN")} Audited with the reason. The person signs in again.",
        )
    }
}

private fun Route.moneyRoutes(money: MoneyService, settings: OpsSettings) {
    staffOnly(Permission.USERS_MONEY.roles) {
        get("wallets") {
            val result = parseUserId(call.parameters["userId"]).flatMap { money.wallets(it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, HoldingsResponse>(
            "OPS · A person's wallets and balances",
            TAG_USERS,
            listOf(NOT_FOUND, FORBIDDEN),
            notes =
                "${roles("FINANCE", "ADMIN")} Balances from Circle; `balancesAvailable` false when Circle can't " +
                    "be read right now.",
        )

        get("transfers") {
            val result =
                parseUserId(call.parameters["userId"]).flatMap { userId ->
                    call.pageLimit(settings).flatMap { money.userTransfers(userId, call.query("cursor"), it) }
                }
            call.respondDefault(
                result.toDefaultResponse { page ->
                    UserTransfersResponse(
                        page.page.items.map { it.toDto() },
                        page.page.nextCursor,
                        page.feesPaidUsdMicros,
                    )
                },
            )
        }.docs<NoBody, UserTransfersResponse>(
            "OPS · A person's transfers and fees paid",
            TAG_USERS,
            listOf(NOT_FOUND, FORBIDDEN, MALFORMED),
            notes = "${roles("FINANCE", "ADMIN")} Sent and received, newest first. $PAGED",
        )
    }
}
