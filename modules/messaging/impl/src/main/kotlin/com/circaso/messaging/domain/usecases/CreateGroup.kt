package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatCreated
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatSettings
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.CreateGroupCommand
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.groupChatId
import com.circaso.messaging.domain.support.isValidSealedMeta
import com.circaso.messaging.domain.support.oncePerChat
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ReachPolicy
import java.time.Clock

/**
 * M5 `POST /chats/groups`: a group with its creator as owner and the people they can reach. Anyone they can't reach
 * is left out and listed in `notAdded`, with no reason, as in users. The 1,000 cap counts everyone asked for, so
 * the answer doesn't depend on who turned out to be reachable.
 *
 * The chat, every member row, `ChatCreated` and each `MemberAdded` are one transaction, so a create is never half
 * written. The chat id comes from the request's `Idempotency-Key` (decision 2026-10-26): a retry finds the group the
 * first attempt made and returns it as it is now, adding nobody, so someone who left in between is never put back;
 * a creator who has left it since gets `messaging.chat_not_found`.
 */
class CreateGroup(
    private val chats: ChatRepository,
    private val transactor: Transactor,
    private val outbox: MessagingOutbox,
    private val reach: ReachPolicy,
    private val views: ChatViews,
    private val notifier: ChatNotifier,
    private val bus: EventBus,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: CreateGroupCommand): Resource<ChatView> {
        if (!isValidSealedMeta(command.sealedMeta, config.maxSealedMetaBytes)) {
            return Resource.error(MessagingError.GroupInvalid)
        }
        val asked = command.memberIds - command.creator
        if (asked.size + 1 > config.maxGroupMembers) return Resource.error(MessagingError.GroupFull)

        val added = reach.reachable(command.creator, asked)
        val notAdded = (asked - added).sortedBy { it.toString() }
        val group = newGroup(groupChatId(command.creator, command.requestKey), command, memberCount = added.size + 1)
        val events = events(group, added)
        val created =
            transactor.run("chats.create-group") {
                if (chats.insertChat(group)) {
                    chats.addMembers(listOf(owner(group)) + added.map { member(group, it) })
                    outbox.write(events)
                    true
                } else {
                    false
                }
            }
        if (!created) return retried(group, command.creator, notAdded)
        events.forEach { bus.publish(it) }
        notifyAdded(group, added)
        return Resource.success(views.view(group, lastMessageId = null, notAdded = notAdded))
    }

    /**
     * A retry of a create that committed: the group as it is now. A creator who has left it since sees no group, as
     * anyone outside a chat does.
     */
    private suspend fun retried(
        group: Chat,
        creator: UserId,
        notAdded: List<UserId>,
    ): Resource<ChatView> {
        val chat = chats.chat(group.chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (chats.member(chat.chatId, creator) == null) return Resource.error(MessagingError.ChatNotFound)
        return Resource.success(views.view(chat, lastMessageId = chats.lastMessageId(chat.chatId), notAdded = notAdded))
    }

    private fun newGroup(
        chatId: ChatId,
        command: CreateGroupCommand,
        memberCount: Int,
    ) = Chat(
        chatId = chatId,
        kind = ChatKind.GROUP,
        sealedMeta = command.sealedMeta,
        metaVersion = 1,
        settings = ChatSettings(),
        memberCount = memberCount,
        createdBy = command.creator,
        createdAt = clock.instant(),
        version = CREATED_VERSION,
        ownerId = command.creator,
    )

    /** `ChatCreated` (one id per chat) and a `MemberAdded` for each person added besides the creator. */
    private fun events(chat: Chat, added: Set<UserId>): List<EventEnvelope> {
        val created = ChatCreated(chat.chatId, ChatKind.GROUP.name, createdBy = chat.createdBy)
        val joined = added.map { bus.envelope(MemberAdded(chat.chatId, it, chat.createdBy)) }
        return listOf(bus.envelope(created).oncePerChat(chat.chatId)) + joined
    }

    private fun owner(chat: Chat) = member(chat, chat.createdBy).copy(role = Role.OWNER)

    private fun member(chat: Chat, userId: UserId) =
        Member(
            chat.chatId,
            userId,
            Role.MEMBER,
            chat.createdAt,
        )

    private suspend fun notifyAdded(chat: Chat, added: Set<UserId>) {
        if (added.isEmpty()) return
        notifier.membersAdded(
            recipients = added + chat.createdBy,
            chatId = chat.chatId,
            added = added,
            by = chat.createdBy,
        )
    }

    private companion object {
        /** The version a chat is written with; any change after moves it on. */
        const val CREATED_VERSION = 1L
    }
}
