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
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.Join
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.isValidSealedMeta
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ReachPolicy
import java.time.Clock

/**
 * M5 `POST /chats/groups`: a group with its creator as owner and the people they can reach. Anyone they can't reach
 * is left out and listed in `notAdded`, with no reason, as in users. The 1,000 cap counts everyone asked for, so
 * the answer doesn't depend on who turned out to be reachable.
 */
class CreateGroup(
    private val store: MessagingStore,
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
        val chat = newGroup(command, memberCount = added.size + 1)
        store.commit(changes(chat, added))

        val everyone = added + command.creator
        if (added.isNotEmpty()) notifier.membersAdded(everyone, chat.chatId, added, command.creator)
        return Resource.success(views.view(chat, lastMessageId = null, notAdded = notAdded))
    }

    private fun newGroup(command: CreateGroupCommand, memberCount: Int) =
        Chat(
            chatId = ChatId.new(),
            kind = ChatKind.GROUP,
            sealedMeta = command.sealedMeta,
            metaVersion = 1,
            settings = ChatSettings(),
            memberCount = memberCount,
            createdBy = command.creator,
            createdAt = clock.instant(),
            version = 1,
        )

    /** The owner first, then each person added with their own `MemberAdded`, in one or more batches. */
    private fun changes(chat: Chat, added: Set<UserId>): ChatChanges {
        val owner = Join(Member(chat.chatId, chat.createdBy, Role.OWNER, chat.createdAt), ChatKind.GROUP)
        val members =
            added.map { person ->
                val event = bus.envelope(MemberAdded(chat.chatId, person, chat.createdBy))
                Join(Member(chat.chatId, person, Role.MEMBER, chat.createdAt), ChatKind.GROUP, event)
            }
        val created = ChatCreated(chat.chatId, ChatKind.GROUP.name, createdBy = chat.createdBy)
        return ChatChanges(
            chats = listOf(chat),
            joins = listOf(owner) + members,
            events = listOf(bus.envelope(created)),
        )
    }
}
