package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupDeleted
import com.circaso.messaging.api.MemberRemoved
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import java.time.Clock
import java.util.UUID

/**
 * Takes someone out of a group, for a leave ([by] is the person) and for a removal by an admin alike. It is one
 * transaction (messaging.md → M10), which closes the half-done removal MS3 could leave behind:
 *
 * 1. The chat's row is locked first, so membership changes to one group run one after another, and the caller's
 *    right to remove is decided on the rows as they are now.
 * 2. The member row is taken with `DELETE … RETURNING`. Only the caller that gets it goes on; one that gets nothing
 *    changed nothing (`messaging.chat_not_found`), so of a remove and a leave racing, one wins.
 * 3. The winner decrements `member_count` (moving the version on), picks the successor when the owner left (the
 *    longest-serving admin, else member), deletes the group when nobody is left, and writes `MemberRemoved` (its id
 *    made from the chat, the person and when they joined) and `GroupDeleted` to the outbox. A deleted group's photo
 *    is given an expiry of now, so the next `messaging.purge-attachments` removes it (decision 2026-11-05).
 *
 * After the commit, everyone who was in it, the person included, gets `member.removed` so the remaining phones
 * rotate their sender keys; a new owner is announced with `chat.updated`.
 */
class MemberRemoval(
    private val chats: ChatRepository,
    private val attachments: AttachmentRepository,
    private val transactor: Transactor,
    private val outbox: MessagingOutbox,
    private val membership: Membership,
    private val notifier: ChatNotifier,
    private val bus: EventBus,
    private val clock: Clock,
) {
    /** What the transaction did, for the frames and events after the commit. */
    private class Removed(
        val member: Member,
        val chat: Chat?,
        val remaining: List<Member>,
        val newOwner: Member?,
        val events: List<EventEnvelope>,
    )

    suspend fun remove(
        chatId: ChatId,
        target: UserId,
        by: UserId,
    ): Resource<Unit> {
        val removed =
            when (val outcome = transactor.attempt("chats.remove-member") { decide(chatId, target, by) }) {
                is Resource.Error -> return outcome
                is Resource.Success -> outcome.data
            }
        membership.changed(chatId)
        removed.events.forEach { bus.publish(it) }
        val remaining = removed.remaining.map { it.userId }
        notifier.memberRemoved(remaining + target, chatId, removed = target, by = by)
        val chat = removed.chat
        if (removed.newOwner != null && chat != null) notifier.chatUpdated(remaining, chat)
        return Resource.success(Unit)
    }

    /** Inside the transaction, with the chat's row locked before any member row is read. */
    private suspend fun decide(
        chatId: ChatId,
        target: UserId,
        by: UserId,
    ): Resource<Removed> {
        val chat = chats.lockChat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        val actor = chats.member(chatId, by) ?: return Resource.error(MessagingError.ChatNotFound)
        if (chat.kind != ChatKind.GROUP) return Resource.error(MessagingError.NotAGroup)
        if (target != by) {
            if (!chat.roleOf(actor).isAdmin) return Resource.error(MessagingError.NotAdmin)
            val subject = chats.member(chatId, target) ?: return Resource.error(MessagingError.ChatNotFound)
            if (chat.roleOf(subject) == Role.OWNER) return Resource.error(MessagingError.NotAdmin)
        }

        val member = chats.removeMember(chatId, target) ?: return Resource.error(MessagingError.ChatNotFound)
        val remaining = chats.members(chatId)
        val removedEvent = removedEvent(member, by)
        if (remaining.isEmpty()) {
            chats.deleteChat(chatId)
            chat.avatarMediaId?.let { attachments.releaseGroupPhoto(it, clock.instant()) }
            val deleted = bus.envelope(GroupDeleted(chatId)).oncePerChat(chatId)
            outbox.write(listOf(removedEvent, deleted))
            return Resource.success(Removed(member, null, remaining, null, listOf(removedEvent, deleted)))
        }

        val successor = if (chat.ownerId == target) successorOf(chat, remaining) else null
        val next = chat.copy(memberCount = chat.memberCount - 1, ownerId = successor?.userId ?: chat.ownerId)
        val updated = chats.updateChat(chat, next) ?: return Resource.error(MessagingError.VersionConflict)
        if (successor != null) chats.setRole(successor)
        outbox.write(listOf(removedEvent))
        return Resource.success(Removed(member, updated, remaining, successor, listOf(removedEvent)))
    }

    /** One event per membership, however many removals of it are tried. */
    private fun removedEvent(member: Member, by: UserId): EventEnvelope {
        val removed = MemberRemoved(member.chatId, member.userId, by)
        val once = "${MemberRemoved.TYPE}:${member.chatId}:${member.userId}:${member.joinedAt.toEpochMilli()}"
        return bus.envelope(removed).copy(eventId = EventId(UUID.nameUUIDFromBytes(once.toByteArray())))
    }

    /** The admin who joined first, or else the member who did; ties go by user id. */
    private fun successorOf(chat: Chat, remaining: List<Member>): Member {
        val bySeniority = remaining.sortedWith(compareBy<Member>({ it.joinedAt }, { it.userId.toString() }))
        val next = bySeniority.firstOrNull { chat.roleOf(it) == Role.ADMIN } ?: bySeniority.first()
        return next.copy(role = Role.OWNER)
    }
}
