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
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.Join
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.ChatWriter
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * M5 `POST /chats/groups`: a group with its creator as owner and the people they can reach. Anyone they can't reach
 * is left out and listed in `notAdded`, with no reason, as in users. The 1,000 cap counts everyone asked for, so
 * the answer doesn't depend on who turned out to be reachable.
 *
 * A big group is written in several batches, so a create can fail part-way. The chat id comes from the request's
 * `Idempotency-Key` (decision 2026-10-26): the retry finds the chat and writes only the members still missing, and
 * after a failed write the member count is set to the rows there are. `ChatCreated` has one id per chat.
 */
class CreateGroup(
    private val chats: ChatRepository,
    private val writer: ChatWriter,
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
        val chatId = groupChatId(command.creator, command.requestKey)
        val chat = chats.chat(chatId)
        val written =
            if (chat == null) {
                val group =
                    newGroup(
                        chatId,
                        command,
                        memberCount = added.size + 1,
                    )
                create(group, added)
            } else {
                resume(chat, added)
            }
        val group =
            when (written) {
                is Resource.Error -> return written
                is Resource.Success -> written.data
            }
        val view =
            views.view(
                group,
                lastMessageId = null,
                notAdded = notAdded,
            )
        return Resource.success(view)
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
        version = 1,
        ownerId = command.creator,
    )

    private suspend fun create(chat: Chat, added: Set<UserId>): Resource<Chat> {
        val owner = Join(owner(chat), ChatKind.GROUP)
        val created =
            ChatCreated(
                chat.chatId,
                ChatKind.GROUP.name,
                createdBy = chat.createdBy,
            )
        val changes =
            ChatChanges(
                chats = listOf(chat),
                joins = listOf(owner) + joins(chat, added),
                events = listOf(bus.envelope(created).oncePerChat(chat.chatId)),
            )
        writeOrRecount(chat.chatId, changes)
        notifyAdded(chat, added)
        return Resource.success(chat)
    }

    /** A retry of the same request: the chat exists, so only the people with no member row yet are written. */
    private suspend fun resume(chat: Chat, added: Set<UserId>): Resource<Chat> {
        val present = chats.members(chat.chatId).map { it.userId }.toSet()
        val ownerJoin =
            if (chat.createdBy in present) {
                emptyList()
            } else {
                listOf(Join(owner(chat), ChatKind.GROUP))
            }
        val missing = added - present
        writeOrRecount(chat.chatId, ChatChanges(joins = ownerJoin + joins(chat, missing)))
        val counted =
            writer.update(chat.chatId) { latest ->
                Resource.success(latest.copy(memberCount = maxOf(latest.memberCount, chats.members(chat.chatId).size)))
            }
        notifyAdded(chat, missing)
        return counted
    }

    /** Writes [changes]; if a batch fails, the count is set to the rows written before the error goes on. */
    private suspend fun writeOrRecount(chatId: ChatId, changes: ChatChanges) {
        try {
            store.commit(changes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writer.recount(chatId)
            throw e
        }
    }

    private fun joins(chat: Chat, people: Set<UserId>): List<Join> =
        people.map { person ->
            val added =
                MemberAdded(
                    chat.chatId,
                    person,
                    chat.createdBy,
                )
            Join(
                member(chat, person),
                ChatKind.GROUP,
                bus.envelope(added),
            )
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
        val everyone = chats.members(chat.chatId).map { it.userId }
        notifier.membersAdded(
            recipients = everyone,
            chatId = chat.chatId,
            added = added,
            by = chat.createdBy,
        )
    }
}
