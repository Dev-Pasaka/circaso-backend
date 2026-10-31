package com.circaso.messaging.domain.models

import com.circaso.shared.UserId
import java.time.Duration
import java.time.Instant

/**
 * What the server keeps of one person's presence (`msg:presence:{userId}`): whether their phone last said `active`
 * or `away`, and the last moment it was active. Whether they are online is worked out from it at read time, so a
 * phone that was killed without saying `away` goes offline by itself once its heartbeat is [ttl] old.
 */
data class PresenceRecord(val active: Boolean, val lastActiveAt: Instant) {
    fun isOnline(now: Instant, ttl: Duration): Boolean = active && now.isBefore(lastActiveAt.plus(ttl))
}

/** One person's presence as the app sees it: `presence.changed` and `GET /v1/chats/{chatId}/presence`. */
data class Presence(val userId: UserId, val online: Boolean, val lastSeenAt: Instant?)

/** [record] read at [now]: offline with no last seen when the server never saw them, or not for 30 days. */
fun PresenceRecord?.toPresence(
    userId: UserId,
    now: Instant,
    ttl: Duration,
): Presence = Presence(userId, this?.isOnline(now, ttl) == true, this?.lastActiveAt)

/** A heartbeat to look at once its [ttl] has run out: [lastActiveAt] is the one it was scheduled for. */
data class PresenceCheck(val userId: UserId, val lastActiveAt: Instant)
