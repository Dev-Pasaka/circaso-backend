package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class TicketRowDto(
    val ticketId: String,
    val number: String,
    val userId: String,
    val userName: String? = null,
    val subject: String,
    val category: String,
    val status: String,
    val source: String,
    val assigneeId: String? = null,
    val assigneeEmail: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val lastMessageAt: String,
    val waitingOn: String? = null,
)

@Serializable
data class TicketPageResponse(val items: List<TicketRowDto>, val nextCursor: String? = null)

@Serializable
data class TicketListResponse(val items: List<TicketRowDto>)

@Serializable
data class TicketContextDto(val transferId: String? = null, val chatId: String? = null)

/** [internal]: a staff note, never shown to the user. */
@Serializable
data class TicketMessageDto(
    val messageId: String,
    val author: String,
    val staffEmail: String? = null,
    val body: String,
    val createdAt: String,
    val internal: Boolean,
)

@Serializable
data class TicketDetailResponse(
    val ticket: TicketRowDto,
    val description: String,
    val context: TicketContextDto,
    val messages: List<TicketMessageDto>,
)

@Serializable
data class TicketBodyRequest(val body: String = "")

/** [staffId] null unassigns. */
@Serializable
data class AssignTicketRequest(val staffId: String? = null)

@Serializable
data class TicketStatusRequest(val status: String = "")
