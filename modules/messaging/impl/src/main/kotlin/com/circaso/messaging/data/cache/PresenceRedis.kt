package com.circaso.messaging.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.messaging.domain.models.PresenceCheck
import com.circaso.messaging.domain.models.PresenceRecord
import com.circaso.messaging.domain.ports.PresenceStore
import com.circaso.messaging.domain.ports.PresenceWatchers
import com.circaso.shared.UserId
import java.time.Duration
import java.time.Instant

/**
 * `msg:presence:{userId}` = `active:<epoch ms>` or `away:<epoch ms>`, the last moment they were active, for 30 days
 * (the last seen the app shows). Online is worked out from the time, not from the key's expiry, so the 75 seconds
 * live in the domain and the same record answers "last seen".
 *
 * `msg:presence-due:{bucket}`: a hash of `userId` → the heartbeat's epoch ms, one per 5-second bucket of when that
 * heartbeat's 75 seconds run out, kept 10 minutes. The sweep takes the buckets that have ended (`hashTake`, so each
 * check is taken once whichever node sweeps), looking back 2 minutes so a sweep that missed a few runs catches up.
 *
 * `msg:presence-swept`: the last bucket a sweep took, kept 10 minutes, so the next sweep starts after it.
 */
class RedisPresenceStore(private val redis: RedisScope) : PresenceStore {
    override suspend fun read(userIds: Collection<UserId>): Map<UserId, PresenceRecord> {
        val ids = userIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return ids
            .zip(redis.getPipelined(ids.map(::key)))
            .mapNotNull { (id, raw) -> raw?.let(::parse)?.let { id to it } }
            .toMap()
    }

    override suspend fun save(userId: UserId, record: PresenceRecord) {
        val state = if (record.active) ACTIVE else AWAY
        redis.set(key(userId), "$state:${record.lastActiveAt.toEpochMilli()}", RETENTION)
    }

    override suspend fun scheduleCheck(
        userId: UserId,
        lastActiveAt: Instant,
        dueAt: Instant,
    ) = redis.hashSet(
        dueKey(bucket(dueAt)),
        mapOf(userId.toString() to lastActiveAt.toEpochMilli().toString()),
        DUE_TTL,
    )

    /**
     * Takes only the ended buckets after the last one swept (`msg:presence-swept`), within the 2-minute lookback:
     * one bucket on a sweep that runs on time, so one `GET`, one take and one `SET` rather than a take for each of
     * the lookback's 24 buckets, nearly all empty. The mark is written after the takes, so a sweep that fails part-way
     * leaves it where it was and the next one takes those buckets again. Two nodes that sweep at once take each
     * bucket's checks once between them (`hashTake`); a mark that ends up a bucket behind costs an empty take.
     */
    override suspend fun takeDue(now: Instant): List<PresenceCheck> {
        val current = bucket(now)
        val first = bucket(now.minus(LOOKBACK))
        val swept = redis.get(SWEPT_KEY)?.toLongOrNull()
        val from = if (swept == null) first else maxOf(first, swept + 1)
        val due =
            (from until current).flatMap { bucket ->
                redis.hashTake(dueKey(bucket)).mapNotNull { (field, value) ->
                    val userId = UserId.parse(field) ?: return@mapNotNull null
                    val at = value.toLongOrNull() ?: return@mapNotNull null
                    PresenceCheck(userId, Instant.ofEpochMilli(at))
                }
            }
        if (from < current) redis.set(SWEPT_KEY, (current - 1).toString(), DUE_TTL)
        return due
    }

    private fun parse(raw: String): PresenceRecord? {
        val at = raw.substringAfter(':', "").toLongOrNull() ?: return null
        return when (raw.substringBefore(':')) {
            ACTIVE -> PresenceRecord(true, Instant.ofEpochMilli(at))
            AWAY -> PresenceRecord(false, Instant.ofEpochMilli(at))
            else -> null
        }
    }

    private fun bucket(at: Instant): Long = at.toEpochMilli() / BUCKET.toMillis()

    private fun key(userId: UserId) = "presence:$userId"

    private fun dueKey(bucket: Long) = "presence-due:$bucket"

    companion object {
        const val ACTIVE = "active"
        const val AWAY = "away"
        val RETENTION: Duration = Duration.ofDays(30)
        val BUCKET: Duration = Duration.ofSeconds(5)
        val LOOKBACK: Duration = Duration.ofMinutes(2)
        val DUE_TTL: Duration = Duration.ofMinutes(10)
        const val SWEPT_KEY = "presence-swept"
    }
}

/**
 * `msg:presence-watchers:{userId}`: a hash of watcher id → the epoch ms their watch lapses. Each watch renews the
 * hash's expiry to its own window, so the hash goes once nobody has watched for that long; a lapsed field read in the
 * meantime is skipped and removed.
 */
class RedisPresenceWatchers(private val redis: RedisScope, private val window: Duration) : PresenceWatchers {
    override suspend fun watch(
        watcher: UserId,
        watched: Collection<UserId>,
        until: Instant,
    ) {
        val field = mapOf(watcher.toString() to until.toEpochMilli().toString())
        watched.forEach { redis.hashSet(key(it), field, window) }
    }

    override suspend fun watchersOf(userId: UserId, now: Instant): Set<UserId> {
        val (live, lapsed) =
            redis.hashGetAll(key(userId)).entries.partition { (_, until) ->
                (until.toLongOrNull() ?: 0) > now.toEpochMilli()
            }
        lapsed.forEach { redis.hashDelete(key(userId), it.key) }
        return live.mapNotNull { UserId.parse(it.key) }.toSet()
    }

    private fun key(userId: UserId) = "presence-watchers:$userId"
}
