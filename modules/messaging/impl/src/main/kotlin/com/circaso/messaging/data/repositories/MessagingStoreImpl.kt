package com.circaso.messaging.data.repositories

import com.circaso.messaging.data.entities.ChatRow
import com.circaso.messaging.data.entities.MemberRow
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.UserChatRow
import com.circaso.messaging.data.mappers.toRow
import com.circaso.messaging.data.mappers.toUserChatRow
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import io.kandra.core.ExperimentalKandraApi
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.repository.KandraSuspendRepository
import java.time.Instant
import java.util.UUID

/**
 * Writes one operation's rows and its outbox rows in LOGGED batches, then hands the events to the bus. A person's
 * member row, chat-list line and event always share a batch. A group created with many people goes in chunks of
 * [CHUNK] people, so no batch grows past what the database allows; the chat rows and chat-level events ride with
 * the first chunk, so a chat exists before its members do.
 */
@OptIn(ExperimentalKandraApi::class)
class MessagingStoreImpl(
    private val runtime: KandraRuntime,
    private val chats: KandraSuspendRepository<ChatRow>,
    private val members: KandraSuspendRepository<MemberRow>,
    private val userChats: KandraSuspendRepository<UserChatRow>,
    private val outbox: KandraSuspendRepository<MessagingOutboxRow>,
    private val bus: EventBus,
) : MessagingStore {
    override suspend fun commit(changes: ChatChanges) {
        val joins = changes.joins.chunked(CHUNK)
        val leaves = changes.leaves.chunked(CHUNK)
        val chunks = maxOf(joins.size, leaves.size, 1)
        for (index in 0 until chunks) {
            val first = index == 0
            write(
                ChatChanges(
                    chats = if (first) changes.chats else emptyList(),
                    deletedChats = if (first) changes.deletedChats else emptyList(),
                    joins = joins.getOrElse(index) { emptyList() },
                    roles = if (first) changes.roles else emptyList(),
                    leaves = leaves.getOrElse(index) { emptyList() },
                    events = if (first) changes.events else emptyList(),
                ),
            )
        }
        for (event in changes.events + changes.joins.mapNotNull { it.event } + changes.leaves.mapNotNull { it.event }) {
            bus.publish(event)
        }
    }

    private suspend fun write(changes: ChatChanges) {
        if (changes == ChatChanges()) return
        runtime.batch {
            for (chat in changes.chats) chats.saveInBatch(chat.toRow())
            for (chatId in changes.deletedChats) chats.deleteInBatch(deletedChat(chatId.value))
            for (join in changes.joins) {
                members.saveInBatch(join.member.toRow())
                userChats.saveInBatch(join.member.toUserChatRow(join.kind))
                join.event?.let { outbox.saveInBatch(it.toOutboxRow()) }
            }
            for (member in changes.roles) members.saveInBatch(member.toRow())
            for (leave in changes.leaves) {
                val member = leave.member
                members.deleteInBatch(member.toRow())
                userChats.deleteInBatch(deletedLine(member))
                leave.event?.let { outbox.saveInBatch(it.toOutboxRow()) }
            }
            for (event in changes.events) outbox.saveInBatch(event.toOutboxRow())
        }
    }

    /** Only the key matters to a delete; the other columns are placeholders. */
    private fun deletedChat(chatId: UUID) =
        ChatRow(
            chatId = chatId,
            kind = "",
            settings = "",
            memberCount = 0,
            createdBy = chatId,
            createdAt = EPOCH,
        )

    private fun deletedLine(member: Member) =
        UserChatRow(
            userId = member.userId.value,
            chatId = member.chatId.value,
            kind = "",
            joinedAt = EPOCH,
        )

    private companion object {
        /** People per batch: each is two rows and an outbox row. */
        const val CHUNK = 40
        val EPOCH: Instant = Instant.EPOCH
    }
}

private fun EventEnvelope.toOutboxRow() =
    MessagingOutboxRow(
        bucket,
        eventId.value,
        type,
        toJson(),
    )
