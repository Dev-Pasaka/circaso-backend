package com.circaso.calls.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.DomainEvent
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

// Published on `events:calls`.

/**
 * A call ended, once per call: how it ended (`COMPLETED`, `CANCELLED`, `MISSED`, `DECLINED`, `DROPPED`), who was in
 * it, and how long it lasted ([startedAt] is null when nobody answered). For a call log in the chat later.
 */
@Serializable
data class CallEnded(
    val callId: CallId,
    val chatId: ChatId,
    val kind: String,
    val startedBy: UserId,
    val endReason: String,
    val joinedUserIds: List<UserId>,
    val createdAt: String,
    val startedAt: String? = null,
    val endedAt: String,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "calls.CallEnded"
    }
}
