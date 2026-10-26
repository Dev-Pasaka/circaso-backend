package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupDeleted
import com.circaso.messaging.api.MemberRemoved
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.Leave
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import java.util.UUID

/**
 * Takes someone out of a group, for a leave and for a removal alike (decision 2026-10-26):
 *
 * 1. Their chat-list line goes with `MemberRemoved` in one batch. The event's id is made from the chat, the person
 *    and when they joined: one event per membership, however many removals of it are tried.
 * 2. Then the member row is deleted with a lightweight transaction (`IF EXISTS`): of a remove and a leave of the
 *    same person racing, only one goes on to the next step, and only it sends frames. If anything fails before
 *    this, the person is still a member, so a retry by either of them completes the removal.
 * 3. Only then the chat changes, through its version, deciding on the rows as they are now: the member count goes
 *    down (never below the rows), a group whose owner is gone gets the longest-serving admin, or else member, as
 *    owner, and a group with nobody left is deleted.
 *
 * Everyone who was in it, the person included, gets `member.removed` so the remaining phones rotate their sender
 * keys; a new owner is announced with `chat.updated`.
 */
class MemberRemoval(
    private val chats: ChatRepository,
    private val writer: ChatWriter,
    private val store: MessagingStore,
    private val membership: Membership,
    private val notifier: ChatNotifier,
    private val bus: EventBus,
) {
    suspend fun remove(member: Member, by: UserId): Resource<Unit> {
        val chatId = member.chatId
        store.commit(ChatChanges(leaves = listOf(Leave(member, removedEvent(member, by)))))
        if (!chats.claimRemoval(chatId, member.userId)) return Resource.error(MessagingError.ChatNotFound)
        membership.changed(chatId)

        val outcome = Outcome()
        val written = writer.update(chatId) { latest -> decide(latest, outcome) }
        if (written is Resource.Success) finish(written.data, outcome)

        val remaining = membership.members(chatId)
        notifier.memberRemoved(
            recipients = remaining + member.userId,
            chatId = chatId,
            removed = member.userId,
            by = by,
        )
        return Resource.success(Unit)
    }

    private fun removedEvent(member: Member, by: UserId): EventEnvelope {
        val removed =
            MemberRemoved(
                member.chatId,
                member.userId,
                by,
            )
        val once = "${MemberRemoved.TYPE}:${member.chatId}:${member.userId}:${member.joinedAt.toEpochMilli()}"
        return bus.envelope(removed).copy(eventId = EventId(UUID.nameUUIDFromBytes(once.toByteArray())))
    }

    /** What the last run of [decide] chose; it runs again on every attempt, so it starts over each time. */
    private class Outcome {
        var deleting = false
        var successor: Member? = null
    }

    /**
     * Inside the version check. A chat already at zero is being deleted by the last member to leave before this one,
     * which stops here: that leave publishes `GroupDeleted`, once.
     */
    private suspend fun decide(latest: Chat, outcome: Outcome): Resource<Chat> {
        outcome.deleting = false
        outcome.successor = null
        if (latest.memberCount <= 0) return Resource.error(MessagingError.ChatNotFound)

        val remaining = chats.members(latest.chatId)
        if (remaining.isEmpty()) {
            outcome.deleting = true
            return Resource.success(latest.copy(memberCount = 0))
        }
        val owner = latest.ownerId
        val ownerLeft = owner != null && remaining.none { it.userId == owner }
        val successor = if (ownerLeft) successorOf(latest, remaining) else null
        outcome.successor = successor
        val count = maxOf(latest.memberCount - 1, remaining.size)
        return Resource.success(latest.copy(memberCount = count, ownerId = successor?.userId ?: owner))
    }

    /** The admin who joined first, or else the member who did; ties go by user id. */
    private fun successorOf(chat: Chat, remaining: List<Member>): Member {
        val bySeniority = remaining.sortedWith(compareBy<Member>({ it.joinedAt }, { it.userId.toString() }))
        val next = bySeniority.firstOrNull { chat.roleOf(it) == Role.ADMIN } ?: bySeniority.first()
        return next.copy(role = Role.OWNER)
    }

    private suspend fun finish(chat: Chat, outcome: Outcome) {
        val successor = outcome.successor
        when {
            outcome.deleting -> deleteGroup(chat.chatId)
            successor != null -> handOver(chat, successor)
        }
    }

    private suspend fun deleteGroup(chatId: ChatId) {
        val deleted = bus.envelope(GroupDeleted(chatId)).oncePerChat(chatId)
        store.commit(ChatChanges(deletedChats = listOf(chatId), events = listOf(deleted)))
        membership.changed(chatId)
    }

    /**
     * The owner row mirrors [Chat.ownerId], which the version check already set. Written only if the successor is
     * still a member: one who left in the meantime isn't put back, and their own leave chose the next owner.
     */
    private suspend fun handOver(chat: Chat, successor: Member) {
        chats.updateRole(successor)
        notifier.chatUpdated(membership.members(chat.chatId), chat)
    }
}
