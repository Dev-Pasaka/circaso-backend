package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatCreated
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatSettings
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.oncePerChat
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ReachPolicy
import java.time.Clock

/**
 * M4 `POST /chats/direct`: the one chat between two people, made on first open. The chat row is inserted with
 * `ON CONFLICT DO NOTHING` against the unique index on the pair, with both member rows and `ChatCreated` in the same
 * transaction; when two people open it at the same moment, the second insert waits for the first and then does
 * nothing, and that caller reads the winner's chat, so both get the same one. Reach is checked every time, before
 * anything is looked up.
 */
class OpenDirectChat(
    private val chats: ChatRepository,
    private val transactor: Transactor,
    private val outbox: MessagingOutbox,
    private val reach: ReachPolicy,
    private val views: ChatViews,
    private val bus: EventBus,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, other: UserId): Resource<ChatView> {
        if (caller == other) return Resource.error(SharedError.Malformed("userId", "is you"))
        if (!reach.canReach(caller, other)) return Resource.error(MessagingError.NotReachable)

        chats.directChat(caller, other)?.let { return Resource.success(existing(it)) }
        val chat = newChat(caller, other)
        val created = bus.envelope(ChatCreated(chat.chatId, ChatKind.DIRECT.name, memberIds = listOf(caller, other)))
        val event = created.oncePerChat(chat.chatId)
        val opened =
            transactor.run("chats.open-direct") {
                if (chats.insertChat(chat)) {
                    chats.addMembers(listOf(member(chat, caller), member(chat, other)))
                    outbox.write(listOf(event))
                    chat
                } else {
                    chats.directChat(caller, other)
                }
            }
        if (opened == null) return Resource.error(MessagingError.ChatNotFound)
        if (opened.chatId != chat.chatId) return Resource.success(existing(opened))
        bus.publish(event)
        return Resource.success(views.view(chat, lastMessageId = null, created = true))
    }

    private suspend fun existing(chat: Chat): ChatView =
        views.view(chat, lastMessageId = chats.lastMessageId(chat.chatId), created = false)

    private fun newChat(caller: UserId, other: UserId) =
        Chat(
            chatId = ChatId.new(),
            kind = ChatKind.DIRECT,
            sealedMeta = null,
            metaVersion = 0,
            settings = ChatSettings(),
            memberCount = 2,
            createdBy = caller,
            createdAt = clock.instant(),
            version = 1,
            directPair = caller to other,
        )

    private fun member(chat: Chat, userId: UserId) = Member(chat.chatId, userId, Role.MEMBER, chat.createdAt)
}
