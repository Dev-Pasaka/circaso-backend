package com.circaso.calls.domain.repositories

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.models.Call
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import java.time.Instant

interface CallRepository {
    /** Records a new call and its participants unless the chat already has one that hasn't ended; true if it did. */
    suspend fun insert(call: Call): Boolean

    suspend fun find(callId: CallId): Call?

    /** The call, its row locked for the rest of the transaction (the parent before its participants). */
    suspend fun lock(callId: CallId): Call?

    /** The chat's call that hasn't ended, if any. */
    suspend fun live(chatId: ChatId): Call?

    /** The calls that haven't ended in which [userId] is a participant (rung, on it, or was on it). */
    suspend fun liveFor(userId: UserId): List<Call>

    /** Writes what changed from [before] to [after]: the call row and the participants that differ. */
    suspend fun save(before: Call, after: Call)

    /** Marks the call active now (a pull or a renegotiation), without touching anything else. */
    suspend fun touch(callId: CallId, at: Instant)

    /** Calls that haven't ended, made before [cutoff], with someone still `INVITED`: their ring has run out. */
    suspend fun ringingBefore(cutoff: Instant): List<CallId>

    /** `ACTIVE` calls with nothing happening since [cutoff]. */
    suspend fun idleSince(cutoff: Instant): List<CallId>
}
