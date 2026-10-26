package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.Join
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.ChatWriter
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ReachPolicy
import java.time.Clock

/**
 * M9 `POST /chats/{chatId}/members`: admins add people, or any member when the group allows it. Each person must be
 * reachable by whoever adds them; the rest are listed in `notAdded`, with no reason. People already in the group
 * are skipped. The member count goes up through the chat's version first, so two adds at once can't pass 1,000
 * between them; if the rows then fail to write, the count stays high, which only refuses adds early.
 */
class AddMembers(
    private val chats: ChatRepository,
    private val writer: ChatWriter,
    private val store: MessagingStore,
    private val reach: ReachPolicy,
    private val membership: Membership,
    private val views: ChatViews,
    private val notifier: ChatNotifier,
    private val bus: EventBus,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        chatId: ChatId,
        memberIds: Set<UserId>,
    ): Resource<ChatView> {
        val adder = chats.member(chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (!mayAdd(chat, adder)) return Resource.error(MessagingError.NotAdmin)

        val current = chats.members(chatId).map { it.userId }.toSet()
        val asked = memberIds - current
        if (current.size + asked.size > config.maxGroupMembers) return Resource.error(MessagingError.GroupFull)
        val added = reach.reachable(caller, asked)
        val notAdded = (asked - added).sortedBy { it.toString() }
        if (added.isEmpty()) return Resource.success(view(chat, caller, notAdded))

        val counted =
            writer.update(chatId) { latest ->
                if (latest.memberCount + added.size > config.maxGroupMembers) {
                    Resource.error(MessagingError.GroupFull)
                } else {
                    Resource.success(latest.copy(memberCount = latest.memberCount + added.size))
                }
            }
        val updated =
            when (counted) {
                is Resource.Error -> return counted
                is Resource.Success -> counted.data
            }
        store.commit(ChatChanges(joins = added.map { join(chatId, it, caller) }))
        membership.changed(chatId)

        val everyone = current + added
        notifier.membersAdded(everyone, chatId, added, caller)
        return Resource.success(view(updated, caller, notAdded))
    }

    private fun mayAdd(chat: Chat, adder: Member) =
        chat.kind == ChatKind.GROUP && (adder.role.isAdmin || chat.settings.membersCanAdd)

    private fun join(
        chatId: ChatId,
        person: UserId,
        by: UserId,
    ): Join {
        val member = Member(chatId, person, Role.MEMBER, clock.instant())
        return Join(member, ChatKind.GROUP, bus.envelope(MemberAdded(chatId, person, by)))
    }

    private suspend fun view(
        chat: Chat,
        caller: UserId,
        notAdded: List<UserId>,
    ): ChatView {
        val last = chats.userChat(caller, chat.chatId)?.lastMessageId
        return views.view(chat, last, notAdded = notAdded)
    }
}
