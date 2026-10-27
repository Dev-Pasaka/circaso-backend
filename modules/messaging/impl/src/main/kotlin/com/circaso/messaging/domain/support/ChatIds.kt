package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventId
import com.circaso.shared.UserId
import java.util.Base64
import java.util.UUID

/**
 * A group's chat id, made from its creator and the request's `Idempotency-Key` (decision 2026-10-26): a retry of the
 * same request names the same group and resumes it, so a create that failed half-way never leaves two groups.
 */
fun groupChatId(creator: UserId, requestKey: String): ChatId =
    ChatId(UUID.nameUUIDFromBytes("group:$creator:$requestKey".toByteArray()))

/**
 * Gives a chat-level event (`ChatCreated`, `GroupDeleted`) an id made from its chat, so a chat written twice (a
 * resumed create, two openers of a direct chat) still has one such event: consumers skip a repeated id.
 */
fun EventEnvelope.oncePerChat(chatId: ChatId): EventEnvelope =
    copy(eventId = EventId(UUID.nameUUIDFromBytes("$type:$chatId".toByteArray())))

/** A group's sealed name and avatar: base64 the phone made, at most [maxBytes] once decoded. */
fun isValidSealedMeta(raw: String, maxBytes: Int): Boolean {
    val bytes = runCatching { Base64.getDecoder().decode(raw) }.getOrNull() ?: return false
    return bytes.size in 1..maxBytes
}
