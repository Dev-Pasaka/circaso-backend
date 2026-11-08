package com.circaso.support.domain.usecases

import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.NewTicket
import com.circaso.support.api.TicketContext
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketStatus
import com.circaso.support.api.WaitingOn
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.ports.TicketAllowance
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.support.OrderedIds
import com.circaso.support.domain.support.SupportConfig
import com.circaso.support.domain.support.TicketTexts
import com.circaso.support.domain.support.madeAt
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.Wallets

/**
 * SU1, and Asha's `openSupportTicket` (source `ASHA`): a new ticket, `OPEN` and waiting on staff. The checks run
 * cheapest and most certain first: the texts, then the context (a transfer the user sent, received or signed; a chat
 * they are a member of: asked of wallet and messaging, never taken on the phone's word), then the daily cap, counted
 * only for a ticket that would otherwise be written, so a typo doesn't use up the user's day.
 */
class OpenTicket(
    private val tickets: TicketRepository,
    private val wallets: Wallets,
    private val chats: ChatDirectory,
    private val allowance: TicketAllowance,
    private val ids: OrderedIds,
    private val config: SupportConfig,
) {
    suspend operator fun invoke(request: NewTicket): Resource<Ticket> {
        val subject = TicketTexts.subject(request.subject) ?: return Resource.error(SupportError.SubjectInvalid)
        val description =
            TicketTexts.description(request.description) ?: return Resource.error(SupportError.DescriptionInvalid)
        if (!isTheirs(request.userId, request.context)) return Resource.error(SupportError.ContextInvalid)
        allowance.take(request.userId, config.ticketsPerDay, config.ticketWindow)?.let {
            return Resource.error(SupportError.RateLimited(it))
        }
        val ticketId = ids.next()
        val now = ticketId.madeAt()
        val ticket =
            Ticket(
                ticketId = TicketId(ticketId),
                number = 0,
                userId = request.userId,
                subject = subject,
                description = description,
                category = request.category,
                status = TicketStatus.OPEN,
                source = request.source,
                context = request.context,
                assigneeId = null,
                waitingOn = WaitingOn.STAFF,
                createdAt = now,
                updatedAt = now,
                lastMessageAt = now,
                version = 0,
            )
        return Resource.success(tickets.insert(ticket))
    }

    private suspend fun isTheirs(userId: UserId, context: TicketContext): Boolean {
        val transferId = context.transferId
        if (transferId != null) {
            val transfer = wallets.transfer(TransferId(transferId)) ?: return false
            if (userId !in setOfNotNull(transfer.senderId, transfer.recipientId, transfer.signerId)) return false
        }
        val chatId = context.chatId
        return chatId == null || chats.isMember(ChatId(chatId), userId)
    }
}
