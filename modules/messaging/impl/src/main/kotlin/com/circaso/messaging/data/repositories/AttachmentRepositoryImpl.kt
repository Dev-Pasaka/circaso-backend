package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.tables.AttachmentsTable
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentKind
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import java.util.UUID

/** AT1-AT3 and the purge over `attachments`. */
class AttachmentRepositoryImpl(private val database: Database) : AttachmentRepository {
    override suspend fun insert(attachment: Attachment) {
        database.tx("attachments.insert") {
            AttachmentsTable.insert {
                it[attachmentId] = attachment.attachmentId
                it[chatId] = attachment.chatId.value
                it[uploaderId] = attachment.uploaderId.value
                it[kind] = attachment.kind.name
                it[sizeBytes] = attachment.sizeBytes
                it[state] = attachment.state.name
                it[createdAt] = attachment.createdAt
                it[expiresAt] = attachment.expiresAt
            }
        }
    }

    override suspend fun find(attachmentId: UUID): Attachment? =
        database.tx("attachments.find") {
            AttachmentsTable
                .selectAll()
                .where { AttachmentsTable.attachmentId eq attachmentId }
                .map { it.toAttachment() }
                .singleOrNull()
        }

    override suspend fun markReady(attachmentId: UUID): Boolean =
        database.tx("attachments.mark-ready") {
            AttachmentsTable.update(
                where = {
                    (AttachmentsTable.attachmentId eq attachmentId) and
                        (AttachmentsTable.state eq AttachmentState.PENDING.name)
                },
            ) {
                it[state] = AttachmentState.READY.name
            } > 0
        }

    override suspend fun purgeable(
        now: Instant,
        pendingBefore: Instant,
        limit: Int,
    ): List<Attachment> =
        database.tx("attachments.purgeable") {
            AttachmentsTable
                .selectAll()
                .where {
                    (AttachmentsTable.expiresAt lessEq now) or
                        (
                            (AttachmentsTable.state eq AttachmentState.PENDING.name) and
                                (AttachmentsTable.createdAt lessEq pendingBefore)
                        )
                }.orderBy(AttachmentsTable.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toAttachment() }
                .toList()
        }

    override suspend fun delete(attachmentId: UUID): Boolean =
        database.tx("attachments.delete") {
            AttachmentsTable.deleteWhere { AttachmentsTable.attachmentId eq attachmentId } > 0
        }
}

private fun ResultRow.toAttachment() =
    Attachment(
        attachmentId = this[AttachmentsTable.attachmentId],
        chatId = ChatId(this[AttachmentsTable.chatId]),
        uploaderId = UserId(this[AttachmentsTable.uploaderId]),
        kind = AttachmentKind.valueOf(this[AttachmentsTable.kind]),
        sizeBytes = this[AttachmentsTable.sizeBytes],
        state = AttachmentState.valueOf(this[AttachmentsTable.state]),
        createdAt = this[AttachmentsTable.createdAt],
        expiresAt = this[AttachmentsTable.expiresAt],
    )
