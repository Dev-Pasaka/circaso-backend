package com.circaso.support.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.support.domain.support.SupportConfig
import com.circaso.support.presentation.dto.OpenTicketRequest
import com.circaso.support.presentation.dto.ReplyRequest
import com.circaso.support.presentation.dto.TicketDetailResponse
import com.circaso.support.presentation.dto.TicketsResponse
import com.circaso.support.presentation.mappers.parseCursor
import com.circaso.support.presentation.mappers.parseLimit
import com.circaso.support.presentation.mappers.parseTicketId
import com.circaso.support.presentation.mappers.toNewTicket
import com.circaso.support.presentation.mappers.toOpenedResponse
import com.circaso.support.presentation.mappers.toResponse
import com.circaso.support.presentation.services.TicketService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Support tickets"
private const val SCOPE = "Scope: `app`."
private const val IDEMPOTENT = "Send an `Idempotency-Key`."
private const val NOT_FOUND = "support.ticket_not_found"
private const val MALFORMED = "request.malformed"

/**
 * Support tickets from the app (decision 2026-11-08): SU1 open one, SU2 list yours, SU3 read one, SU4 reply. Staff
 * answer from the ops dashboard; their replies reach the app as `support/ticket.replied` and show as "Circaso
 * Support", never a person. Internal notes never leave the server's staff side.
 */
fun Route.ticketController(tickets: TicketService, config: SupportConfig) {
    route("/support/tickets") {
        authenticate(Scope.APP) {
            collectionRoutes(tickets, config)
            route("/{ticketId}") {
                ticketRoutes(tickets)
            }
        }
    }
}

private fun Route.collectionRoutes(tickets: TicketService, config: SupportConfig) {
    post {
        val caller = call.userId()
        val result = call.receive<OpenTicketRequest>().toNewTicket(caller).flatMap { tickets.open(it) }
        call.respondDefault(result.toDefaultResponse { it.toOpenedResponse() })
    }.docs<OpenTicketRequest, TicketDetailResponse>(
        "SU1 · Open a support ticket",
        TAG,
        listOf(
            "support.subject_invalid",
            "support.description_invalid",
            "support.category_invalid",
            "support.context_invalid",
            "support.rate_limited",
            MALFORMED,
        ),
        notes =
            "$SCOPE $IDEMPOTENT `subject` 3 to 120 characters, `description` 10 to 4,000 (both trimmed). " +
                "`category`: `ACCOUNT`, `PAYMENTS`, `WALLET`, `GROUPS`, `SECURITY`, `ASHA` or `OTHER`. Optional " +
                "`transferId` (a payment you sent, received or signed) and `chatId` (a chat you are in) say what " +
                "it is about; anything else answers `support.context_invalid`. At most ${config.ticketsPerDay} new " +
                "tickets a day, counted from your first (Asha's count too): `support.rate_limited` with " +
                "`retryAfterSeconds`. Answers the ticket, `OPEN` and waiting on staff, with its `number` " +
                "(`CS-000123`) to show.",
    )

    get {
        val caller = call.userId()
        val result =
            parseLimit(call.request.queryParameters["limit"], config).flatMap { limit ->
                parseCursor(call.request.queryParameters["cursor"]).flatMap { tickets.list(caller, it, limit) }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, TicketsResponse>(
        "SU2 · Your support tickets",
        TAG,
        listOf(MALFORMED),
        notes =
            "$SCOPE `?cursor=&limit=${config.pageDefault}` (at most ${config.pageMax}). Every ticket of yours, " +
                "newest first, those Asha opened for you too (`source: ASHA`). `nextCursor` continues, null on the " +
                "last page.",
    )
}

private fun Route.ticketRoutes(tickets: TicketService) {
    get {
        val caller = call.userId()
        val result = parseTicketId(call.parameters["ticketId"]).flatMap { tickets.get(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, TicketDetailResponse>(
        "SU3 · Read a support ticket",
        TAG,
        listOf(NOT_FOUND),
        notes =
            "$SCOPE Yours only: someone else's answers `support.ticket_not_found`. The replies come oldest first; " +
                "staff replies have `author: STAFF` and `authorName: \"Circaso Support\"`.",
    )

    post("replies") {
        val caller = call.userId()
        val body = call.receive<ReplyRequest>().body
        val result = parseTicketId(call.parameters["ticketId"]).flatMap { tickets.reply(caller, it, body) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<ReplyRequest, TicketDetailResponse>(
        "SU4 · Reply on a support ticket",
        TAG,
        listOf(NOT_FOUND, "support.body_invalid", "support.ticket_closed", MALFORMED),
        notes =
            "$SCOPE $IDEMPOTENT `body` 1 to 4,000 characters. A ticket that is `RESOLVED` or waiting on you " +
                "(`PENDING_USER`) opens again (`OPEN`, waiting on staff); a `CLOSED` one answers " +
                "`support.ticket_closed` (409): open a new ticket instead. Answers the ticket with its replies.",
    )
}
