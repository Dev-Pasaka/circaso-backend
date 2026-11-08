package com.circaso.support.presentation.mappers

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.support.api.NewTicket
import com.circaso.support.api.TicketAuthor
import com.circaso.support.api.TicketCategory
import com.circaso.support.api.TicketContext
import com.circaso.support.api.TicketDetail
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketMessage
import com.circaso.support.api.TicketNumbers
import com.circaso.support.api.TicketPage
import com.circaso.support.api.TicketSource
import com.circaso.support.api.TicketSummary
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.models.TicketsPage
import com.circaso.support.domain.support.SupportConfig
import com.circaso.support.presentation.dto.OpenTicketRequest
import com.circaso.support.presentation.dto.TicketDetailResponse
import com.circaso.support.presentation.dto.TicketDto
import com.circaso.support.presentation.dto.TicketMessageDto
import com.circaso.support.presentation.dto.TicketsResponse
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/** What a staff message shows as to the user: the team, never a person (decision 2026-11-08). */
const val SUPPORT_NAME = "Circaso Support"
private const val ASHA_NAME = "Asha"

/**
 * SU1's shape: a known category and well-formed ids. The lengths of the texts and whether the transfer or chat is the
 * caller's are the use case's, since Asha reaches it without this mapper.
 */
fun OpenTicketRequest.toNewTicket(userId: UserId): Resource<NewTicket> {
    val known = categoryOf(category) ?: return Resource.error(SupportError.CategoryInvalid)
    val transfer = transferId?.let { Uuid7.parse(it.trim()) ?: return malformed("transferId", "a UUID") }
    val chat = chatId?.let { Uuid7.parse(it.trim()) ?: return malformed("chatId", "a UUID") }
    val ticket = NewTicket(userId, subject, description, known, TicketContext(transfer, chat), TicketSource.APP)
    return Resource.success(ticket)
}

fun categoryOf(wire: String): TicketCategory? =
    TicketCategory.entries.firstOrNull {
        it.name == wire.trim().uppercase()
    }

/** A path id that isn't one can't be anybody's ticket: it answers like an unknown one. */
fun parseTicketId(raw: String?): Resource<TicketId> =
    TicketId.parse(raw)?.let { Resource.success(it) } ?: Resource.error(SupportError.TicketNotFound)

/** `?limit=`: 1 to [SupportConfig.pageMax], [SupportConfig.pageDefault] when absent. */
fun parseLimit(raw: String?, config: SupportConfig): Resource<Int> {
    val limit =
        raw?.let { it.toIntOrNull() ?: return malformed("limit", "1 to ${config.pageMax}") } ?: config.pageDefault
    return if (limit in 1..config.pageMax) Resource.success(limit) else malformed("limit", "1 to ${config.pageMax}")
}

/** `?cursor=`: the last ticket's id, base64url, opaque to the app. Absent starts from the top; anything else is 400. */
fun parseCursor(raw: String?): Resource<TicketId?> {
    if (raw.isNullOrBlank()) return Resource.success(null)
    val bytes = runCatching { Base64.getUrlDecoder().decode(raw) }.getOrNull()
    if (bytes == null || bytes.size != UUID_BYTES) return malformed("cursor", "a nextCursor from an earlier page")
    val buffer = ByteBuffer.wrap(bytes)
    return Resource.success(TicketId(UUID(buffer.long, buffer.long)))
}

fun TicketId.toCursor(): String {
    val buffer = ByteBuffer.allocate(UUID_BYTES)
    buffer.putLong(value.mostSignificantBits)
    buffer.putLong(value.leastSignificantBits)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array())
}

private fun malformed(field: String, problem: String) = Resource.error(SharedError.Malformed(field, problem))

private const val UUID_BYTES = 16

// ---- the user's view ----

fun Ticket.toDto() =
    TicketDto(
        ticketId = ticketId.toString(),
        number = TicketNumbers.format(number),
        subject = subject,
        category = category.name,
        status = status.name,
        source = source.name,
        waitingOn = waitingOn?.name,
        transferId = context.transferId?.toString(),
        chatId = context.chatId?.toString(),
        createdAt = createdAt.toString(),
        lastMessageAt = lastMessageAt.toString(),
    )

fun TicketsPage.toResponse() = TicketsResponse(tickets.map { it.toDto() }, next?.toCursor())

/** SU1: the new ticket, which has no replies yet. */
fun Ticket.toOpenedResponse() = TicketDetailResponse(toDto(), description, emptyList())

/** The user's thread: internal notes were never read for it, and are dropped here again in case. */
fun TicketThread.toResponse() =
    TicketDetailResponse(
        ticket = ticket.toDto(),
        description = ticket.description,
        messages = messages.filterNot { it.internal }.map { it.toDto() },
    )

private fun TicketMessage.toDto() =
    TicketMessageDto(
        messageId = messageId.toString(),
        author = author.name,
        authorName =
            when (author) {
                TicketAuthor.USER -> null
                TicketAuthor.STAFF -> SUPPORT_NAME
                TicketAuthor.ASHA -> ASHA_NAME
            },
        body = body,
        createdAt = createdAt.toString(),
    )

// ---- the staff desk's (support:api) ----

fun Ticket.toSummary() =
    TicketSummary(
        ticketId = ticketId,
        number = TicketNumbers.format(number),
        userId = userId,
        subject = subject,
        category = category,
        status = status,
        source = source,
        assigneeId = assigneeId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
        waitingOn = waitingOn,
    )

fun TicketsPage.toPage() = TicketPage(tickets.map { it.toSummary() }, next?.toCursor())

fun TicketThread.toDetail() = TicketDetail(ticket.toSummary(), ticket.description, ticket.context, messages)
