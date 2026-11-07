package com.circaso.support.api

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Where a ticket stands (decision 2026-11-08). [OPEN]: waiting on Circaso. [PENDING_USER]: staff replied and wait on the
 * user. [RESOLVED]: staff consider it done; a reply from the user opens it again. [CLOSED]: final, no more replies.
 */
enum class TicketStatus { OPEN, PENDING_USER, RESOLVED, CLOSED }

enum class TicketCategory { ACCOUNT, PAYMENTS, WALLET, GROUPS, SECURITY, ASHA, OTHER }

/** Who opened it: the user from the app's support screens, or Asha on the user's behalf (after they agreed). */
enum class TicketSource { APP, ASHA }

/** Who wrote a message. Staff messages carry the staff id; internal notes are always staff and never reach the user. */
enum class TicketAuthor { USER, STAFF, ASHA }

/** Whose turn it is: the queue's "waiting on". Null once the ticket is resolved or closed. */
enum class WaitingOn { STAFF, USER }

/** What the ticket is about, when it is about one thing in the app. Both optional; ids only, never content. */
data class TicketContext(val transferId: UUID? = null, val chatId: UUID? = null)

/** A ticket to open for [userId]. Subject 3-120 characters, description 10-4,000, after trimming. */
data class NewTicket(
    val userId: UserId,
    val subject: String,
    val description: String,
    val category: TicketCategory,
    val context: TicketContext = TicketContext(),
    val source: TicketSource,
)

/** What opening a ticket answers: its id and the number to tell the user ([TicketNumbers.format]). */
data class OpenedTicket(val ticketId: TicketId, val number: String)

/**
 * Asha opens a ticket for the user she is talking to (the assistant's `openSupportTicket` tool). The tool's own rules
 * (only after trying to help, only once the user agreed, a summary of the issue) are in its description; this only
 * writes the ticket, as the user would from the app.
 */
interface SupportTickets {
    /** Errors: `support.subject_invalid`, `support.description_invalid`, `support.rate_limited`. */
    suspend fun open(ticket: NewTicket): Resource<OpenedTicket>
}

/** One line of the staff queue. [assigneeId] is a staff id from the ops module; support stores ids, never emails. */
data class TicketSummary(
    val ticketId: TicketId,
    val number: String,
    val userId: UserId,
    val subject: String,
    val category: TicketCategory,
    val status: TicketStatus,
    val source: TicketSource,
    val assigneeId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastMessageAt: Instant,
    val waitingOn: WaitingOn?,
)

/** One message of a ticket. [internal] notes are staff only and never shown to, or sent to, the user. */
data class TicketMessage(
    val messageId: UUID,
    val author: TicketAuthor,
    val staffId: UUID?,
    val body: String,
    val internal: Boolean,
    val createdAt: Instant,
)

/** A whole ticket for staff: the line, what the user wrote first, its context and every message, oldest first. */
data class TicketDetail(
    val summary: TicketSummary,
    val description: String,
    val context: TicketContext,
    val messages: List<TicketMessage>,
)

/** Who a queue shows: anyone's, nobody's yet, or one staff member's. */
sealed interface AssigneeFilter {
    data object Anyone : AssigneeFilter

    data object Unassigned : AssigneeFilter

    data class Staff(val staffId: UUID) : AssigneeFilter
}

/**
 * The queue's filters. [createdBefore] is the "older than" filter (now minus the age). A queue filtered by an open
 * status ([TicketStatus.OPEN], [TicketStatus.PENDING_USER]) is oldest first, so the longest wait is on top; any other
 * is newest first.
 */
data class TicketQueueFilter(
    val status: TicketStatus? = null,
    val category: TicketCategory? = null,
    val assignee: AssigneeFilter = AssigneeFilter.Anyone,
    val createdBefore: Instant? = null,
)

data class TicketPage(val items: List<TicketSummary>, val nextCursor: String?)

/** The dashboard's ticket tile. */
data class TicketCounts(val open: Long, val pendingUser: Long, val unassigned: Long)

/**
 * The staff side of support, for the ops module's routes on the ops gateway (decision 2026-11-08: tickets first;
 * encrypted support chats are a later phase). Who may call what (SUPPORT and ADMIN) and the audit line are the ops
 * module's; this only reads and writes tickets. A staff reply reaches the user as `support/ticket.replied` (socket, or
 * push when offline); notes never do.
 */
interface SupportDesk {
    /** Errors: `request.malformed` for a cursor that isn't one. [limit] is 1-200. */
    suspend fun queue(
        filter: TicketQueueFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TicketPage>

    /** Error: `support.ticket_not_found`. */
    suspend fun ticket(ticketId: TicketId): Resource<TicketDetail>

    /** The user's tickets, newest first, at most [limit]. */
    suspend fun ofUser(userId: UserId, limit: Int): Resource<List<TicketSummary>>

    /** A reply the user sees; the ticket turns `PENDING_USER`. Errors: not found, `support.body_invalid`, `support.ticket_closed`. */
    suspend fun reply(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketDetail>

    /** An internal note, staff only. Errors: not found, `support.body_invalid`. */
    suspend fun note(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketDetail>

    /** [assigneeId] null unassigns. Error: not found. */
    suspend fun assign(ticketId: TicketId, assigneeId: UUID?): Resource<TicketDetail>

    /** Errors: not found. The user hears `support/ticket.updated`. */
    suspend fun changeStatus(ticketId: TicketId, status: TicketStatus): Resource<TicketDetail>

    suspend fun counts(): Resource<TicketCounts>
}
