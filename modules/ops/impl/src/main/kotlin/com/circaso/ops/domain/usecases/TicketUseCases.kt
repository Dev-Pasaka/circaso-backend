package com.circaso.ops.domain.usecases

import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.models.TicketLinePage
import com.circaso.ops.domain.models.TicketView
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.StaffRoster
import com.circaso.ops.domain.support.TicketViews
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.support.api.SupportDesk
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketStatus
import java.util.UUID

// Tickets for SUPPORT and ADMIN (decision 2026-11-08: tickets first; encrypted support chats later). Support keeps the
// tickets; ops audits every change and puts staff emails and user names on them. Message bodies never enter the audit.

class ListTickets(private val desk: SupportDesk, private val tickets: TicketViews) {
    suspend operator fun invoke(
        filter: TicketQueueFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TicketLinePage> =
        desk.queue(filter, cursor, limit).map { TicketLinePage(tickets.rows(it.items), it.nextCursor) }
}

class GetTicket(private val desk: SupportDesk, private val tickets: TicketViews) {
    suspend operator fun invoke(ticketId: TicketId): Resource<TicketView> =
        desk.ticket(ticketId).map { tickets.view(it) }
}

/** A reply the user sees in the app; they hear `support/ticket.replied`. */
class ReplyToTicket(private val desk: SupportDesk, private val tickets: TicketViews, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        ticketId: TicketId,
        body: String,
    ): Resource<TicketView> =
        trail
            .record(actor, AuditAction.TICKET_REPLY, ticketId.toString()) { desk.reply(ticketId, actor.staffId, body) }
            .map { tickets.view(it) }
}

/** An internal note: staff only, never shown to the user. */
class NoteOnTicket(private val desk: SupportDesk, private val tickets: TicketViews, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        ticketId: TicketId,
        body: String,
    ): Resource<TicketView> =
        trail
            .record(actor, AuditAction.TICKET_NOTE, ticketId.toString()) { desk.note(ticketId, actor.staffId, body) }
            .map { tickets.view(it) }
}

/** Gives the ticket to an active SUPPORT or ADMIN member, or to nobody ([assigneeId] null). */
class AssignTicket(
    private val desk: SupportDesk,
    private val tickets: TicketViews,
    private val roster: StaffRoster,
    private val trail: AuditTrail,
) {
    suspend operator fun invoke(
        actor: Actor,
        ticketId: TicketId,
        assigneeId: UUID?,
    ): Resource<TicketView> {
        if (assigneeId != null) {
            val assignee = roster.byId(assigneeId) ?: return Resource.error(OpsError.StaffNotFound)
            if (!assignee.active || !Permission.TICKETS_WRITE.allows(assignee.role)) {
                return Resource.error(OpsError.AssigneeInvalid)
            }
        }
        val detail = "assignee ${assigneeId ?: "none"}"
        return trail
            .record(actor, AuditAction.TICKET_ASSIGN, ticketId.toString(), detail = detail) {
                desk.assign(ticketId, assigneeId)
            }.map { tickets.view(it) }
    }
}

class ChangeTicketStatus(
    private val desk: SupportDesk,
    private val tickets: TicketViews,
    private val trail: AuditTrail,
) {
    suspend operator fun invoke(
        actor: Actor,
        ticketId: TicketId,
        status: TicketStatus,
    ): Resource<TicketView> =
        trail
            .record(actor, AuditAction.TICKET_STATUS, ticketId.toString(), detail = "status ${status.name}") {
                desk.changeStatus(ticketId, status)
            }.flatMap { Resource.success(tickets.view(it)) }
}
