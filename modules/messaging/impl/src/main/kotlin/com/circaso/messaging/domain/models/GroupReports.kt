package com.circaso.messaging.domain.models

import com.circaso.messaging.api.ChatId
import java.time.Instant

// What staff read about groups on the ops gateway (decision 2026-11-08): ids, counts and roles only. A group's name
// and photo are sealed on the phones; the server never had them to show.

/** Where a newest-first page of groups stopped: the last group's `(created_at, chat_id)`. */
data class GroupKey(val createdAt: Instant, val chatId: ChatId)

data class GroupResults(val groups: List<Chat>, val nextCursor: String?)

/** A member with the role the chat gives them ([Chat.roleOf]: the owner is whoever the chat names). */
data class GroupMemberView(val member: Member, val role: Role)
