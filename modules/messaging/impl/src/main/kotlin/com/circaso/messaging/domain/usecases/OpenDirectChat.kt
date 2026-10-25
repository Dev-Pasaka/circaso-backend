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
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.Join
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.pairKey
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ReachPolicy
import kotlinx.coroutines.delay
import java.time.Clock

/**
 * M4 `POST /chats/direct`: the one chat between two people, made on first open. The pair is claimed with a
 * lightweight transaction before the chat is written, so two people opening it at the same moment get the same
 * chat. Reach is checked every time, before anything is looked up.
 */
class OpenDirectChat(
    private val chats: ChatRepository,
    private val store: MessagingStore,
    private val reach: ReachPolicy,
    private val views: ChatViews,
    private val bus: EventBus,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, other: UserId): Resource<ChatView> {
        if (caller == other) return Resource.error(SharedError.Malformed(mapOf("userId" to "is you")))
        if (!reach.canReach(caller, other)) return Resource.error(MessagingError.NotReachable)

        val pair = pairKey(caller, other)
        val chatId = ChatId.new()
        if (chats.claimPair(pair, chatId)) {
            val chat = create(chatId, caller, other)
            return Resource.success(views.view(chat, lastMessageId = null, created = true))
        }
        val existing = chats.pairChat(pair) ?: return Resource.error(MessagingError.ChatNotFound)
        val chat = awaitChat(existing) ?: create(existing, caller, other)
        val last = chats.userChat(caller, existing)?.lastMessageId
        return Resource.success(views.view(chat, last, created = false))
    }

    /**
     * The other opener claimed the pair a moment ago and may still be writing the chat. If it never appears (that
     * request failed between the claim and the write), this one writes it under the claimed id.
     */
    private suspend fun awaitChat(chatId: ChatId): Chat? {
        repeat(WAIT_ROUNDS) { round ->
            chats.chat(chatId)?.let { return it }
            if (round < WAIT_ROUNDS - 1) delay(WAIT_MILLIS)
        }
        return null
    }

    private suspend fun create(
        chatId: ChatId,
        caller: UserId,
        other: UserId,
    ): Chat {
        val now = clock.instant()
        val chat =
            Chat(
                chatId = chatId,
                kind = ChatKind.DIRECT,
                sealedMeta = null,
                metaVersion = 0,
                settings = ChatSettings(),
                memberCount = 2,
                createdBy = caller,
                createdAt = now,
                version = 1,
            )
        val joins = listOf(caller, other).map { Join(Member(chatId, it, Role.MEMBER, now), ChatKind.DIRECT) }
        val created = ChatCreated(chatId, ChatKind.DIRECT.name, memberIds = listOf(caller, other))
        store.commit(ChatChanges(chats = listOf(chat), joins = joins, events = listOf(bus.envelope(created))))
        return chat
    }

    private companion object {
        const val WAIT_ROUNDS = 5
        const val WAIT_MILLIS = 100L
    }
}
