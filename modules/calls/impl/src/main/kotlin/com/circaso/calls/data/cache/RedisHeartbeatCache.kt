package com.circaso.calls.data.cache

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.ports.HeartbeatCache
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import java.time.Duration

/**
 * `cal:hb:beat:{callId}:{userId}` (a one-second marker, so a phone repeating itself faster costs nothing more) and
 * `cal:hb:joined:{callId}` (who is joined, comma-separated, for [JOINED_TTL]: someone joining or leaving is seen by
 * the heartbeats within that long, which is all a heartbeat needs).
 */
class RedisHeartbeatCache(private val redis: RedisScope) : HeartbeatCache {
    override suspend fun take(callId: CallId, userId: UserId): Boolean =
        redis.setIfAbsent("hb:beat:$callId:$userId", "1", BEAT_TTL)

    override suspend fun joined(callId: CallId): Set<UserId>? {
        val raw = redis.get("hb:joined:$callId") ?: return null
        if (raw.isEmpty()) return emptySet()
        return raw.split(',').mapNotNull { UserId.parse(it) }.toSet()
    }

    override suspend fun keepJoined(callId: CallId, userIds: Set<UserId>) {
        redis.set("hb:joined:$callId", userIds.joinToString(","), JOINED_TTL)
    }

    private companion object {
        val BEAT_TTL: Duration = Duration.ofSeconds(1)
        val JOINED_TTL: Duration = Duration.ofSeconds(5)
    }
}
