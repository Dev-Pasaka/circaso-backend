package com.circaso.support.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.support.api.TicketAuthor
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketMessage
import com.circaso.support.data.tables.MessagesTable
import com.circaso.support.domain.repositories.MessageRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** A ticket's thread over `support.messages`, read by `messages_ticket_idx` in id order (the order they were made). */
class MessageRepositoryImpl(private val database: Database) : MessageRepository {
    override suspend fun insert(ticketId: TicketId, message: TicketMessage) {
        database.tx("messages.insert") {
            MessagesTable.insert {
                it[messageId] = message.messageId
                it[MessagesTable.ticketId] = ticketId.value
                it[author] = message.author.name
                it[staffId] = message.staffId
                it[body] = message.body
                it[internal] = message.internal
                it[createdAt] = message.createdAt
            }
        }
    }

    override suspend fun of(ticketId: TicketId, withNotes: Boolean): List<TicketMessage> =
        database.tx("messages.of") {
            MessagesTable
                .selectAll()
                .where {
                    (MessagesTable.ticketId eq ticketId.value) and
                        if (withNotes) Op.TRUE else (MessagesTable.internal eq false)
                }.orderBy(MessagesTable.messageId to SortOrder.ASC)
                .map {
                    TicketMessage(
                        messageId = it[MessagesTable.messageId],
                        author = TicketAuthor.valueOf(it[MessagesTable.author]),
                        staffId = it[MessagesTable.staffId],
                        body = it[MessagesTable.body],
                        internal = it[MessagesTable.internal],
                        createdAt = it[MessagesTable.createdAt],
                    )
                }.toList()
        }
}
