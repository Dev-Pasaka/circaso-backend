package com.circaso.messaging.presentation.api

import com.circaso.messaging.presentation.services.RealtimeService
import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.shared.getOrNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * What the gateway calls after a reconnect with the app's last messaging `seq`: the kept frames it missed, or a
 * single `resync` frame when the gap is older than the 7 days they are kept, after which the app reloads its chats
 * over HTTP (`GET /chats`). `message.new` is never replayed: the inbox holds undelivered messages.
 */
class MessagingRealtimeReplay(private val realtime: RealtimeService) : RealtimeReplay {
    override suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>> {
        val frames = realtime.missedFrames(userId, seq).getOrNull() ?: return Resource.success(listOf(resync(seq)))
        return Resource.success(frames.map { CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), it.json) })
    }

    private fun resync(seq: Long) =
        RealtimeEnvelope("messaging", "resync", Uuid7.generate().toString(), seq, Instant.now(), JsonObject(emptyMap()))
}
