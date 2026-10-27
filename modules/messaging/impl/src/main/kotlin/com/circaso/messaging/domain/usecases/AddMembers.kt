package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.MembersAdded
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
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
 * are skipped.
 *
 * Reach is asked of users first, outside the transaction. Then, in one transaction, the chat's row is locked before
 * anything else (the lock order every membership change keeps), the adder's right and who is already in are read
 * again, the places are reserved with the conditional `member_count` update (0 rows is `messaging.group_full`), and
 * the member rows and their `MemberAdded` events are written. Two adds at once run one after the other, so they
 * can't pass 1,000 between them, and the count always equals the rows.
 */
class AddMembers(
    private val chats: ChatRepository,
    private val transactor: Transactor,
    private val outbox: MessagingOutbox,
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
        val asked = memberIds - membership.members(chatId)
        if (chat.memberCount + asked.size > config.maxGroupMembers) return Resource.error(MessagingError.GroupFull)
        val reachable = reach.reachable(caller, asked)
        val notAdded = (asked - reachable).sortedBy { it.toString() }
        if (reachable.isEmpty()) return Resource.success(viewOf(chat, notAdded))

        val added =
            when (val written = add(caller, chatId, reachable)) {
                is Resource.Error -> return written
                is Resource.Success -> written.data
            }
        if (added.joined.isNotEmpty()) {
            membership.changed(chatId)
            added.events.forEach { bus.publish(it) }
            notifier.membersAdded(
                recipients = membership.members(chatId),
                chatId = chatId,
                added = added.joined,
                by = caller,
            )
        }
        return Resource.success(viewOf(added.chat, notAdded))
    }

    /** The decision and the writes, under the chat's row lock. */
    private suspend fun add(
        caller: UserId,
        chatId: ChatId,
        reachable: Set<UserId>,
    ): Resource<MembersAdded> =
        transactor.attempt("chats.add-members") {
            val latest = chats.lockChat(chatId) ?: return@attempt Resource.error(MessagingError.ChatNotFound)
            val adder = chats.member(chatId, caller) ?: return@attempt Resource.error(MessagingError.ChatNotFound)
            if (!mayAdd(latest, adder)) return@attempt Resource.error(MessagingError.NotAdmin)
            val present = chats.members(chatId).map { it.userId }.toSet()
            val joining = reachable - present
            if (joining.isEmpty()) return@attempt Resource.success(MembersAdded(latest, emptySet(), emptyList()))

            val reserved =
                chats.reserveSeats(chatId, joining.size, config.maxGroupMembers)
                    ?: return@attempt Resource.error(MessagingError.GroupFull)
            val now = clock.instant()
            chats.addMembers(joining.map { Member(chatId, it, Role.MEMBER, now) })
            val events = joining.map { bus.envelope(MemberAdded(chatId, it, caller)) }
            outbox.write(events)
            Resource.success(MembersAdded(reserved, joining, events))
        }

    private fun mayAdd(chat: Chat, adder: Member) =
        chat.kind == ChatKind.GROUP && (chat.roleOf(adder).isAdmin || chat.settings.membersCanAdd)

    private suspend fun viewOf(chat: Chat, notAdded: List<UserId>): ChatView =
        views.view(chat, lastMessageId = chats.lastMessageId(chat.chatId), notAdded = notAdded)
}
