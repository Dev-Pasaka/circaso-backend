package com.circaso.assistant.data.cache

import com.circaso.assistant.domain.models.AshaStatus
import com.circaso.assistant.domain.models.ProbeState
import com.circaso.assistant.domain.ports.AshaHealth
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Asha's health in Redis (`ast:health:*`, decision 2026-11-07), so every node agrees:
 * - `health:down`: when she went down (epoch ms), set only if absent; gone while she is up. `markUp` takes it in one
 *   step, so exactly one node marks her up;
 * - `health:up`: when she last came back;
 * - `health:affected`: a hash of the user ids who met her down, nothing else, for a day at most;
 * - `health:notice:{userId}`: a claim on the one "she's back" notice per person per window;
 * - `health:probe`: the monitor's failed probes in this outage and when the next is due.
 */
class RedisAshaHealth(private val redis: RedisScope) : AshaHealth {
    override suspend fun status(): AshaStatus {
        val (down, up) = redis.getPipelined(listOf(DOWN, UP))
        down?.toLongOrNull()?.let { return AshaStatus(isAvailable = false, since = Instant.ofEpochMilli(it)) }
        return AshaStatus(isAvailable = true, since = up?.toLongOrNull()?.let(Instant::ofEpochMilli))
    }

    override suspend fun markDown(at: Instant): Boolean {
        val isNew = redis.setIfAbsent(DOWN, at.toEpochMilli().toString(), KEPT)
        if (isNew) redis.delete(PROBE)
        return isNew
    }

    override suspend fun markUp(at: Instant): Boolean {
        redis.getAndDelete(DOWN) ?: return false
        redis.set(UP, at.toEpochMilli().toString(), KEPT)
        redis.delete(PROBE)
        return true
    }

    override suspend fun recordAffected(userId: UserId) =
        redis.hashSet(AFFECTED, mapOf(userId.toString() to "1"), AFFECTED_TTL)

    override suspend fun takeAffected(): List<UserId> =
        redis.hashTake(AFFECTED).keys.mapNotNull { runCatching { UserId(UUID.fromString(it)) }.getOrNull() }

    override suspend fun claimNotice(userId: UserId, window: Duration): Boolean =
        redis.setIfAbsent("$NOTICE$userId", "1", window)

    override suspend fun probe(): ProbeState? {
        val (failures, nextAt) = redis.get(PROBE)?.split(':')?.takeIf { it.size == 2 } ?: return null
        return ProbeState(
            failures.toIntOrNull() ?: return null,
            Instant.ofEpochMilli(nextAt.toLongOrNull() ?: return null),
        )
    }

    override suspend fun saveProbe(state: ProbeState) =
        redis.set(PROBE, "${state.failures}:${state.nextAt.toEpochMilli()}", KEPT)

    private companion object {
        const val DOWN = "health:down"
        const val UP = "health:up"
        const val AFFECTED = "health:affected"
        const val NOTICE = "health:notice:"
        const val PROBE = "health:probe"

        /** Every key expires (Architecture → Redis): a week is longer than any outage we would sit through. */
        val KEPT: Duration = Duration.ofDays(7)

        /** Who met her down is kept a day; someone who met her down longer ago won't be told she's back. */
        val AFFECTED_TTL: Duration = Duration.ofHours(24)
    }
}
