package com.circaso.support.presentation.api

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.support.api.DiagnosticLogDesk
import com.circaso.support.api.DiagnosticLogLine
import com.circaso.support.api.DiagnosticLogText
import com.circaso.support.api.NewTicket
import com.circaso.support.api.OpenedTicket
import com.circaso.support.api.SupportDesk
import com.circaso.support.api.SupportTickets
import com.circaso.support.api.TicketCounts
import com.circaso.support.api.TicketDetail
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketNumbers
import com.circaso.support.api.TicketPage
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketStatus
import com.circaso.support.api.TicketSummary
import com.circaso.support.presentation.mappers.parseCursor
import com.circaso.support.presentation.mappers.toLine
import com.circaso.support.presentation.mappers.toText
import com.circaso.support.presentation.mappers.toDetail
import com.circaso.support.presentation.mappers.toPage
import com.circaso.support.presentation.mappers.toSummary
import com.circaso.support.presentation.services.DiagnosticLogService
import com.circaso.support.presentation.services.SupportDeskService
import com.circaso.support.presentation.services.TicketService
import java.util.UUID

/** support:api for Asha's `openSupportTicket`: the same use case as SU1, so the same rules and the same daily cap. */
class SupportTicketsImpl(private val tickets: TicketService) : SupportTickets {
    override suspend fun open(ticket: NewTicket): Resource<OpenedTicket> =
        tickets.open(ticket).map { OpenedTicket(it.ticketId, TicketNumbers.format(it.number)) }
}

/** support:api for the ops module's staff routes. Every call goes through a service, like any other way in. */
class SupportDeskImpl(private val desk: SupportDeskService) : SupportDesk {
    override suspend fun queue(
        filter: TicketQueueFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TicketPage> {
        if (limit !in 1..MAX_LIMIT) return Resource.error(SharedError.Malformed("limit", "1 to $MAX_LIMIT"))
        return parseCursor(cursor).flatMap { desk.queue(filter, it, limit) }.map { it.toPage() }
    }

    override suspend fun ticket(ticketId: TicketId): Resource<TicketDetail> =
        desk.ticket(ticketId).map { it.toDetail() }

    override suspend fun ofUser(userId: UserId, limit: Int): Resource<List<TicketSummary>> =
        desk.ofUser(userId, limit.coerceIn(1, MAX_LIMIT)).map { list -> list.map { it.toSummary() } }

    override suspend fun reply(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketDetail> = desk.reply(ticketId, staffId, body).map { it.toDetail() }

    override suspend fun note(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketDetail> = desk.note(ticketId, staffId, body).map { it.toDetail() }

    override suspend fun assign(ticketId: TicketId, assigneeId: UUID?): Resource<TicketDetail> =
        desk.assign(ticketId, assigneeId).map { it.toDetail() }

    override suspend fun changeStatus(ticketId: TicketId, status: TicketStatus): Resource<TicketDetail> =
        desk.changeStatus(ticketId, status).map { it.toDetail() }

    override suspend fun counts(): Resource<TicketCounts> = desk.counts()

    private companion object {
        const val MAX_LIMIT = 200
    }
}

/** support:api's diagnostic logs for the ops module's staff routes (2026-11-13). */
class DiagnosticLogDeskImpl(private val logs: DiagnosticLogService) : DiagnosticLogDesk {
    override suspend fun byCode(code: String): Resource<List<DiagnosticLogLine>> =
        logs.byCode(code).map { found -> found.map { it.toLine() } }

    override suspend fun ofUser(userId: UserId, limit: Int): Resource<List<DiagnosticLogLine>> =
        logs.ofUser(userId, limit).map { found -> found.map { it.toLine() } }

    override suspend fun read(logId: UUID): Resource<DiagnosticLogText> = logs.read(logId).map { it.toText() }
}
