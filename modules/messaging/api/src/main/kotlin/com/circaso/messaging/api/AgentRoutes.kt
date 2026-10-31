package com.circaso.messaging.api

import kotlinx.serialization.Serializable

// What messaging's read-only agent route (`/v1/agent/messaging/chats`) answers. It lives here, not in messaging's
// presentation, because the assistant reads it over HTTP with the agent token, as it reads users' (decision
// 2026-10-27; this route decision 2026-10-31). Only what the server holds in the clear: who is in which chat. Group
// names and every message are end-to-end encrypted and never appear; Asha reads those on the phone (device tools).

/** Someone else in the chat: `role` is `OWNER`, `ADMIN` or `MEMBER`. */
@Serializable
data class AgentChatMemberDto(
    val userId: String,
    val displayName: String? = null,
    val handle: String? = null,
    val role: String,
)

/**
 * One of the user's chats. `kind` is `DIRECT` or `GROUP`; `members` are the other people (at most 10, the caller
 * left out), `memberCount` counts everyone. `lastMessageAt` (ISO-8601 UTC) is when the newest message the server
 * still knows of was sent, null after 30 days without one.
 */
@Serializable
data class AgentChatDto(
    val chatId: String,
    val kind: String,
    val memberCount: Int,
    val members: List<AgentChatMemberDto>,
    val isAdmin: Boolean,
    val lastMessageAt: String? = null,
)

/** The user's chats, most recently active first, at most `limit` (default 30, at most 100). */
@Serializable
data class AgentChatsResponse(val chats: List<AgentChatDto>)
