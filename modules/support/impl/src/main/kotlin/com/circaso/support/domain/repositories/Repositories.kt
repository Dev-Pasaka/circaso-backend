package com.circaso.support.domain.repositories

import com.circaso.shared.UserId
import com.circaso.support.api.TicketCounts
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketMessage
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketStatus
import com.circaso.support.domain.models.Ticket
import java.time.Instant
import java.util.UUID

/**
 * `support.tickets`. Every change is one conditional `UPDATE … RETURNING` (working rules: never a read followed by a
 * blind write): it says whether it happened and hands back the row as it now is, with `version` one higher. Null
 * means the condition failed; the use case then reads the row to tell "not found" from "closed".
 */
interface TicketRepository {
    /** Inserts [ticket] and answers it with the number the database gave it. */
    suspend fun insert(ticket: Ticket): Ticket

    suspend fun find(ticketId: TicketId): Ticket?

    /** [userId]'s tickets, newest first, after [after] (keyset on the id). */
    suspend fun listOfUser(
        userId: UserId,
        after: TicketId?,
        limit: Int,
    ): List<Ticket>

    /** The staff queue: oldest first for an open status ([isOldestFirst]), else newest first; keyset on the id. */
    suspend fun queue(
        filter: TicketQueueFilter,
        after: TicketId?,
        limit: Int,
    ): List<Ticket>

    /** The user wrote again: any status but `CLOSED` turns `OPEN`, waiting on staff. Null if not theirs or closed. */
    suspend fun userReplied(
        ticketId: TicketId,
        userId: UserId,
        at: Instant,
    ): Ticket?

    /** Staff replied: any status but `CLOSED` turns `PENDING_USER`, waiting on the user. Null if absent or closed. */
    suspend fun staffReplied(ticketId: TicketId, at: Instant): Ticket?

    /** A note was added: only `updated_at` and `version` move, so the user's view doesn't. Null if absent. */
    suspend fun noted(ticketId: TicketId, at: Instant): Ticket?

    /** Sets (or, with null, clears) the assignee. Null if absent. */
    suspend fun assign(
        ticketId: TicketId,
        assigneeId: UUID?,
        at: Instant,
    ): Ticket?

    /** Moves the ticket to [status] when it is in another one. Null if absent or already in it. */
    suspend fun changeStatus(
        ticketId: TicketId,
        status: TicketStatus,
        at: Instant,
    ): Ticket?

    /** The dashboard's tile, each count served by an index. */
    suspend fun counts(): TicketCounts

    /** Every ticket of [userId] (their messages go by the foreign key); how many went. */
    suspend fun deleteOfUser(userId: UserId): Int
}

/** `support.messages`, read oldest first (keyset on the ordered id within its ticket). */
interface MessageRepository {
    suspend fun insert(ticketId: TicketId, message: TicketMessage)

    /** The ticket's messages oldest first; internal notes only when [withNotes]. */
    suspend fun of(ticketId: TicketId, withNotes: Boolean): List<TicketMessage>
}

/** Open statuses wait on someone, so their queue puts the longest wait on top (TicketQueueFilter's KDoc). */
fun TicketQueueFilter.isOldestFirst(): Boolean = status == TicketStatus.OPEN || status == TicketStatus.PENDING_USER
