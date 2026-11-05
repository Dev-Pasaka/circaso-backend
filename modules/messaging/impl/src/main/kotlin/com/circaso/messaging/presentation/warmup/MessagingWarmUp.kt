package com.circaso.messaging.presentation.warmup

import com.circaso.infrastructure.api.WarmUp
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.repositories.MessagingWarmUpSample
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.messaging.presentation.dto.ChatsResponse
import com.circaso.messaging.presentation.dto.InboxResponse
import com.circaso.messaging.presentation.mappers.toDto
import com.circaso.messaging.presentation.mappers.toResponse
import com.circaso.messaging.presentation.services.ChatService
import com.circaso.messaging.presentation.services.MessageService
import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.web.DefaultResponse
import com.circaso.web.toDefaultResponse

private const val SAMPLE = 20
private const val INBOX_PAGE = 50

/**
 * Messaging's hot paths, read-only: `GET /chats` for a real member (the chat list, members' profiles and their avatar
 * links, then the response's JSON), `GET /inbox` for a random id (a real inbox read may delete an undeliverable row),
 * and what a send reads before its transaction (the chat, its members, the sender's membership, settings and keys).
 * Nothing is written: not the database, and not the members cache (the repository is read, not `Membership`).
 */
class MessagingWarmUp(
    private val chatService: ChatService,
    private val messageService: MessageService,
    private val chats: ChatRepository,
    private val delivery: DeliveryRepository,
    private val settings: SettingsRepository,
    private val keys: KeyRepository,
    private val sample: MessagingWarmUpSample,
) : WarmUp {
    override val name = "messaging"

    override suspend fun run() {
        val nobody = UserId.new()
        val (chatId, userId) = sample.memberships(SAMPLE).randomOrNull() ?: (ChatId.new() to nobody)

        val list = chatService.list(userId).toDefaultResponse { views -> ChatsResponse(views.map { it.toDto() }) }
        CircasoJson.encodeToString(DefaultResponse.serializer(ChatsResponse.serializer()), list)

        val inbox = messageService.inbox(nobody, null, INBOX_PAGE).toDefaultResponse { it.toResponse() }
        CircasoJson.encodeToString(DefaultResponse.serializer(InboxResponse.serializer()), inbox)

        chats.chat(chatId)
        chats.members(chatId)
        chats.members(listOf(chatId))
        chats.member(chatId, userId)
        chats.userChat(userId, chatId)
        chats.lastMessageId(chatId)
        chats.chatIdsOf(userId)
        settings.settings(userId)
        keys.bundle(userId)
        delivery.messageRecord(Uuid7.generate())
    }
}
