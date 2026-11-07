package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.AssignTicketRequest
import com.circaso.ops.presentation.dto.TicketBodyRequest
import com.circaso.ops.presentation.dto.TicketDetailResponse
import com.circaso.ops.presentation.dto.TicketPageResponse
import com.circaso.ops.presentation.dto.TicketStatusRequest
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.PAGED
import com.circaso.ops.presentation.mappers.TAG_TICKETS
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.malformed
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseEnum
import com.circaso.ops.presentation.mappers.parseTicketId
import com.circaso.ops.presentation.mappers.parseUuid
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.TicketService
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.support.api.AssigneeFilter
import com.circaso.support.api.TicketCategory
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketStatus
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staff
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Duration
import java.time.Instant

private const val NOT_FOUND = "support.ticket_not_found"
private const val MAX_AGE_HOURS = 24L * 366

/** The ticket queue and working a ticket: SUPPORT and ADMIN. */
fun Route.ticketController(tickets: TicketService, settings: OpsSettings) {
    route("/ops/tickets") {
        staffOnly(Permission.TICKETS_READ.roles) {
            get {
                val result =
                    call.queueFilter().flatMap { filter ->
                        call.pageLimit(settings).flatMap { tickets.list(filter, call.query("cursor"), it) }
                    }
                call.respondDefault(
                    result.toDefaultResponse { page ->
                        TicketPageResponse(page.items.map { it.toDto() }, page.nextCursor)
                    },
                )
            }.docs<NoBody, TicketPageResponse>(
                "OPS · The ticket queue",
                TAG_TICKETS,
                listOf(MALFORMED, FORBIDDEN),
                notes =
                    "${roles("SUPPORT", "ADMIN")} Filters: `status`, `category`, `assignee` = me | none | <staffId>, " +
                        "`olderThanHours`. Open statuses oldest first, so the longest wait is on top. $PAGED",
            )

            get("{ticketId}") {
                val result = parseTicketId(call.parameters["ticketId"]).flatMap { tickets.get(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, TicketDetailResponse>(
                "OPS · One ticket with its messages and notes",
                TAG_TICKETS,
                listOf(NOT_FOUND, MALFORMED, FORBIDDEN),
                notes = "${roles("SUPPORT", "ADMIN")} `internal: true` marks a note the user never sees.",
            )
        }
        staffOnly(Permission.TICKETS_WRITE.roles) {
            route("/{ticketId}") {
                writeRoutes(tickets)
            }
        }
    }
}

private fun Route.writeRoutes(tickets: TicketService) {
    post("replies") {
        val body = call.receive<TicketBodyRequest>().body
        val result = parseTicketId(call.parameters["ticketId"]).flatMap { tickets.reply(call.actor(), it, body) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<TicketBodyRequest, TicketDetailResponse>(
        "OPS · Reply to the user",
        TAG_TICKETS,
        listOf(NOT_FOUND, "support.body_invalid", "support.ticket_closed", FORBIDDEN),
        notes =
            "${roles("SUPPORT", "ADMIN")} The user sees it in the app as Circaso Support and hears " +
                "`support/ticket.replied`; the ticket turns PENDING_USER. Audited (never the text).",
    )

    post("notes") {
        val body = call.receive<TicketBodyRequest>().body
        val result = parseTicketId(call.parameters["ticketId"]).flatMap { tickets.note(call.actor(), it, body) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<TicketBodyRequest, TicketDetailResponse>(
        "OPS · Add an internal note",
        TAG_TICKETS,
        listOf(NOT_FOUND, "support.body_invalid", FORBIDDEN),
        notes = "${roles("SUPPORT", "ADMIN")} Staff only; the user never sees or hears of it. Audited.",
    )

    post("assign") {
        val request = call.receive<AssignTicketRequest>()
        val assignee =
            request.staffId?.let { raw -> parseUuid(raw, "staffId").map { it } } ?: Resource.success(null)
        val result =
            parseTicketId(call.parameters["ticketId"]).flatMap { id ->
                assignee.flatMap { tickets.assign(call.actor(), id, it) }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<AssignTicketRequest, TicketDetailResponse>(
        "OPS · Assign or unassign",
        TAG_TICKETS,
        listOf(NOT_FOUND, "ops.staff_not_found", "ops.assignee_invalid", FORBIDDEN),
        notes = "${roles("SUPPORT", "ADMIN")} `staffId` null unassigns. To an active SUPPORT or ADMIN. Audited.",
    )

    post("status") {
        val request = call.receive<TicketStatusRequest>()
        val status =
            parseEnum<TicketStatus>(request.status, "status").flatMap {
                it?.let { s -> Resource.success(s) }
                    ?: malformed("status", "required")
            }
        val result =
            parseTicketId(call.parameters["ticketId"]).flatMap { id ->
                status.flatMap { tickets.status(call.actor(), id, it) }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<TicketStatusRequest, TicketDetailResponse>(
        "OPS · Change a ticket's status",
        TAG_TICKETS,
        listOf(NOT_FOUND, MALFORMED, FORBIDDEN),
        notes =
            "${roles("SUPPORT", "ADMIN")} OPEN, PENDING_USER, RESOLVED or CLOSED; the user hears " +
                "`support/ticket.updated`. Audited.",
    )
}

private fun ApplicationCall.queueFilter(): Resource<TicketQueueFilter> =
    parseEnum<TicketStatus>(query("status"), "status").flatMap { status ->
        parseEnum<TicketCategory>(query("category"), "category").flatMap { category ->
            assigneeFilter().flatMap { assignee ->
                olderThan().map { before -> TicketQueueFilter(status, category, assignee, before) }
            }
        }
    }

private fun ApplicationCall.assigneeFilter(): Resource<AssigneeFilter> =
    when (val raw = query("assignee")?.lowercase()) {
        null, "any", "anyone" -> Resource.success(AssigneeFilter.Anyone)
        "me" -> Resource.success(AssigneeFilter.Staff(staff().staffId))
        "none" -> Resource.success(AssigneeFilter.Unassigned)
        else -> parseUuid(raw, "assignee").map { AssigneeFilter.Staff(it) }
    }

private fun ApplicationCall.olderThan(): Resource<Instant?> {
    val raw = query("olderThanHours") ?: return Resource.success(null)
    val hours =
        raw.toLongOrNull()?.takeIf { it in 1..MAX_AGE_HOURS }
            ?: return malformed("olderThanHours", "1 to $MAX_AGE_HOURS")
    return Resource.success(Instant.now().minus(Duration.ofHours(hours)))
}
