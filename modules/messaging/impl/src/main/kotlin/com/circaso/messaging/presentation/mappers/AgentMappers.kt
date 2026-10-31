package com.circaso.messaging.presentation.mappers

import com.circaso.messaging.api.AgentChatDto
import com.circaso.messaging.api.AgentChatMemberDto
import com.circaso.messaging.api.AgentChatsResponse
import com.circaso.messaging.domain.models.ChatView
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7

/** `limit` on the agent's chat list: default 30, at most 100. */
private const val AGENT_DEFAULT_CHATS = 30
private const val AGENT_MAX_CHATS = 100
private const val AGENT_MAX_MEMBERS = 10

fun agentChatLimit(raw: String?): Int = raw?.trim()?.toIntOrNull()?.coerceIn(1, AGENT_MAX_CHATS) ?: AGENT_DEFAULT_CHATS

/**
 * The caller's chats for the assistant, most recently active first, the caller left out of each member list. Only
 * what the server holds in the clear: never `sealedMeta` (the group's sealed name) and never a message.
 */
fun List<ChatView>.toAgentResponse(caller: UserId, limit: Int): AgentChatsResponse =
    AgentChatsResponse(
        sortedByDescending { view -> view.lastMessageId?.let(Uuid7::timestampOf) }
            .take(limit)
            .map { it.toAgentDto(caller) },
    )

private fun ChatView.toAgentDto(caller: UserId): AgentChatDto =
    AgentChatDto(
        chatId = chat.chatId.toString(),
        kind = chat.kind.name,
        memberCount = chat.memberCount,
        members =
            members
                .filter { it.profile.userId != caller }
                .take(AGENT_MAX_MEMBERS)
                .map {
                    AgentChatMemberDto(
                        userId = it.profile.userId.toString(),
                        displayName = it.profile.displayName,
                        handle = it.profile.handle,
                        role = it.role.name,
                    )
                },
        isAdmin = members.any { it.profile.userId == caller && it.role.isAdmin },
        lastMessageAt = lastMessageId?.let(Uuid7::timestampOf)?.toString(),
    )
