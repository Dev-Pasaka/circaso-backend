package com.circaso.support.presentation.dto

import kotlinx.serialization.Serializable

/**
 * SU1. `category` is `ACCOUNT`, `PAYMENTS`, `WALLET`, `GROUPS`, `SECURITY`, `ASHA` or `OTHER`. `transferId` (a payment
 * you sent, received or signed) and `chatId` (a chat you are in) say what it is about, when it is about one thing.
 */
@Serializable
data class OpenTicketRequest(
    val subject: String,
    val description: String,
    val category: String,
    val transferId: String? = null,
    val chatId: String? = null,
)

/** SU4. */
@Serializable
data class ReplyRequest(val body: String)

/**
 * A ticket as its user sees it. `number` is what they read out (`CS-000123`). `status` is `OPEN`, `PENDING_USER`
 * (we replied: their turn), `RESOLVED` or `CLOSED`; `waitingOn` is `STAFF`, `USER` or null once resolved or closed.
 * `source` is `APP` or `ASHA` (Asha opened it for them). `lastMessageAt` moves with every reply either way. Nothing
 * here names who on staff has it.
 */
@Serializable
data class TicketDto(
    val ticketId: String,
    val number: String,
    val subject: String,
    val category: String,
    val status: String,
    val source: String,
    val waitingOn: String? = null,
    val transferId: String? = null,
    val chatId: String? = null,
    val createdAt: String,
    val lastMessageAt: String,
)

/** SU2. `nextCursor` continues, null on the last page. */
@Serializable
data class TicketsResponse(val tickets: List<TicketDto>, val nextCursor: String? = null)

/**
 * One message on a ticket. `author` is `USER` (you), `STAFF` or `ASHA`; `authorName` is "Circaso Support" for staff
 * (never a person's name, id or email) and "Asha" for Asha, null for your own.
 */
@Serializable
data class TicketMessageDto(
    val messageId: String,
    val author: String,
    val authorName: String? = null,
    val body: String,
    val createdAt: String,
)

/** SU1, SU3 and SU4: the ticket, what you wrote first, and the replies oldest first. */
@Serializable
data class TicketDetailResponse(val ticket: TicketDto, val description: String, val messages: List<TicketMessageDto>)
