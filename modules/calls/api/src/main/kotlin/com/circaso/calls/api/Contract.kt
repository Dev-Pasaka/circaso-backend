package com.circaso.calls.api

import com.circaso.messaging.api.ChatId

/** What other modules may ask calls: whether a chat has a call going on (a chat list's "join call" badge). */
interface Calls {
    /** The chat's call that is ringing or active, or null. */
    suspend fun liveCall(chatId: ChatId): CallId?
}
