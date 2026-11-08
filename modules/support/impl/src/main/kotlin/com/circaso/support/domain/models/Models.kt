package com.circaso.support.domain.models

import com.circaso.shared.UserId
import com.circaso.support.api.TicketCategory
import com.circaso.support.api.TicketContext
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketMessage
import com.circaso.support.api.TicketSource
import com.circaso.support.api.TicketStatus
import com.circaso.support.api.WaitingOn
import java.time.Instant
import java.util.UUID

/**
 * One row of `support.tickets`. [number] is the database's identity (shown as `CS-000123`), 0 until it is inserted.
 * [createdAt] is the instant inside [ticketId] (a UUIDv7 from `OrderedIds`), so "created before" is a bound on the id
 * and every queue index serves it. [lastMessageAt] moves with what the user can see (their replies and staff
 * replies), never with an internal note, so nothing about a note reaches them; [updatedAt] moves with every change.
 */
data class Ticket(
    val ticketId: TicketId,
    val number: Long,
    val userId: UserId,
    val subject: String,
    val description: String,
    val category: TicketCategory,
    val status: TicketStatus,
    val source: TicketSource,
    val context: TicketContext,
    val assigneeId: UUID?,
    val waitingOn: WaitingOn?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastMessageAt: Instant,
    val version: Long,
)

/** A ticket and its messages, oldest first: all of them for staff, the user's view without internal notes. */
data class TicketThread(val ticket: Ticket, val messages: List<TicketMessage>)

/** A page of tickets; [next] is the last ticket's id when there may be more. */
data class TicketsPage(val tickets: List<Ticket>, val next: TicketId?)
