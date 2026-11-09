package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatCreated
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatSettings
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.CreateGroupCommand
import com.circaso.messaging.domain.models.InvitesSent
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.Invitations
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.groupChatId
import com.circaso.messaging.domain.support.isValidSealedMeta
import com.circaso.messaging.domain.support.oncePerChat
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ReachPolicy
import java.time.Clock

/**
 * M5 `POST /chats/groups`: a group with its creator as owner and only member, and an invite for each person they can
 * reach (decision 2026-11-09: people join a group by accepting, never by being put in it). Anyone they can't reach is
 * left out and listed in `notAdded`, with no reason, as in users; the people invited are listed in `invited`. The
 * 1,000 cap counts everyone asked for, so the answer doesn't depend on who turned out to be reachable.
 *
 * The chat, the owner's row, `ChatCreated` and the invites are one transaction, so a create is never half written.
 * The chat id comes from the request's `Idempotency-Key` (decision 2026-10-26): a retry finds the group the first
 * attempt made and returns it as it is now, inviting nobody again; a creator who has left it since gets
 * `messaging.chat_not_found`.
 */
class CreateGroup(
    private val chats: ChatRepository,
    private val invites: InviteRepository,
    private val transactor: Transactor,
    private val outbox: MessagingOutbox,
    private val reach: ReachPolicy,
    private val views: ChatViews,
    private val invitations: Invitations,
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
        val keys =
            when (val checked = invitations.keysOf(command.inviteKeys)) {
                is Resource.Error -> return checked
                is Resource.Success -> checked.data
            }

        val reachable = reach.reachable(command.creator, asked)
        val notAdded = (asked - reachable).sortedBy { it.toString() }
        val group = newGroup(command)
        val created = bus.envelope(ChatCreated(group.chatId, ChatKind.GROUP.name, createdBy = group.createdBy))
        val event = created.oncePerChat(group.chatId)
        val sent: InvitesSent? =
            transactor.run("chats.create-group") {
                if (chats.insertChat(group)) {
                    chats.addMembers(listOf(Member(group.chatId, group.createdBy, Role.OWNER, group.createdAt)))
                    outbox.write(listOf(event))
                    invitations.write(group, group.createdBy, reachable, keys)
                } else {
                    null
                }
            }
        if (sent == null) return retried(group, command.creator, reachable, notAdded)
        bus.publish(event)
        invitations.announce(sent, group.createdBy)
        val invited = reachable.sortedBy { it.toString() }
        return Resource.success(views.view(group, lastMessageId = null, notAdded = notAdded, invited = invited))
    }

    /**
     * A retry of a create that committed: the group as it is now, with whoever of the people asked for still has an
     * open invite. A creator who has left it since sees no group, as anyone outside a chat does.
     */
    private suspend fun retried(
        group: Chat,
        creator: UserId,
        reachable: Set<UserId>,
        notAdded: List<UserId>,
    ): Resource<ChatView> {
        val chat = chats.chat(group.chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (chats.member(chat.chatId, creator) == null) return Resource.error(MessagingError.ChatNotFound)
        val invited =
            invites
                .openFor(chat.chatId, reachable, clock.instant())
                .map { it.userId }
                .sortedBy { it.toString() }
        val lastMessageId = chats.lastMessageId(chat.chatId)
        return Resource.success(views.view(chat, lastMessageId, notAdded = notAdded, invited = invited))
    }

    private fun newGroup(command: CreateGroupCommand) =
        Chat(
            chatId = groupChatId(command.creator, command.requestKey),
            kind = ChatKind.GROUP,
            sealedMeta = command.sealedMeta,
            metaVersion = 1,
            settings = ChatSettings(),
            memberCount = 1,
            createdBy = command.creator,
            createdAt = clock.instant(),
            version = CREATED_VERSION,
            ownerId = command.creator,
        )

    private companion object {
        /** The version a chat is written with; any change after moves it on. */
        const val CREATED_VERSION = 1L
    }
}
