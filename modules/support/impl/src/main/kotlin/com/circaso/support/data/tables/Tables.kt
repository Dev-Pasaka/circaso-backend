package com.circaso.support.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * SU1-SU4 and the staff desk: one ticket. `number` is a database identity (`CS-000123`), never chosen by the code, so
 * two nodes can't hand out the same one. `assignee_id` is a staff id from the ops module (support stores ids, never a
 * staff email). Enums are stored by name. The indexes are the queries' (V1__support.sql says which serves which).
 */
object TicketsTable : Table("support.tickets") {
    val ticketId = javaUUID("ticket_id")
    val number = long("number").autoIncrement()
    val userId = javaUUID("user_id")
    val subject = text("subject")
    val description = text("description")
    val category = text("category")
    val status = text("status")

    /** The `source` column (APP or ASHA); `source` itself is a name Exposed's `ColumnSet` already uses. */
    val origin = text("source")
    val contextTransferId = javaUUID("context_transfer_id").nullable()
    val contextChatId = javaUUID("context_chat_id").nullable()
    val assigneeId = javaUUID("assignee_id").nullable()
    val waitingOn = text("waiting_on").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val lastMessageAt = timestamp("last_message_at")
    val version = long("version")
    override val primaryKey = PrimaryKey(ticketId)

    init {
        uniqueIndex("tickets_number_key", number)
        index("tickets_user_idx", false, userId, ticketId)
        index("tickets_status_idx", false, status, ticketId)
        index("tickets_assignee_idx", false, assigneeId, ticketId)
        index("tickets_category_idx", false, category, ticketId)
        index("tickets_unassigned_idx", false, status, ticketId, filterCondition = { assigneeId.isNull() })
    }
}

/** A ticket's messages. `staff_id` is set for staff messages only; `internal` notes never reach the user. */
object MessagesTable : Table("support.messages") {
    val messageId = javaUUID("message_id")
    val ticketId = javaUUID("ticket_id").references(TicketsTable.ticketId, onDelete = ReferenceOption.CASCADE)
    val author = text("author")
    val staffId = javaUUID("staff_id").nullable()
    val body = text("body")
    val internal = bool("internal")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(messageId)

    init {
        index("messages_ticket_idx", false, ticketId, messageId)
    }
}

/** The frames kept 7 days for replay, partitioned by day on `sent_at` like `status.realtime`. */
object RealtimeTable : Table("support.realtime") {
    val userId = javaUUID("user_id")
    val seq = long("seq")

    /** The envelope as JSON. */
    val frame = text("frame")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(userId, seq, sentAt)
}
