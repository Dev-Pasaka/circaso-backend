package com.circaso.status

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.api.StatusFrame
import com.circaso.status.domain.models.AudienceMember
import com.circaso.status.domain.models.MediaState
import com.circaso.status.domain.models.Moment
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.models.ViewRecord
import com.circaso.status.domain.ports.FrameSender
import com.circaso.status.domain.ports.MediaStorage
import com.circaso.status.domain.ports.Transactor
import com.circaso.status.domain.repositories.MediaRepository
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.users.api.ReachPolicy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** `moments` and `audience` in memory, with the same conditions as the SQL. */
class MemoryMoments : MomentRepository {
    val moments = linkedMapOf<MomentId, Moment>()
    val audience = linkedMapOf<MomentId, MutableMap<UserId, AudienceMember>>()

    override suspend fun insert(moment: Moment, audience: List<AudienceMember>): Boolean {
        if (moments.values.any { it.posterId == moment.posterId && it.clientMomentId == moment.clientMomentId }) {
            return false
        }
        moments[moment.momentId] = moment
        this.audience[moment.momentId] = audience.associateBy { it.userId }.toMutableMap()
        return true
    }

    override suspend fun findByClientId(posterId: UserId, clientMomentId: UUID) =
        moments.values.firstOrNull { it.posterId == posterId && it.clientMomentId == clientMomentId }

    override suspend fun findLive(momentId: MomentId, now: Instant) = moments[momentId]?.takeIf { it.expiresAt > now }

    override suspend fun member(momentId: MomentId, userId: UserId) = audience[momentId]?.get(userId)

    override suspend fun audienceOf(momentId: MomentId) = audience[momentId].orEmpty().keys.toList()

    override suspend fun postedSince(posterId: UserId, since: Instant): Pair<Int, Instant?> {
        val times = moments.values.filter { it.posterId == posterId && it.postedAt > since }.map { it.postedAt }
        return times.size to times.minOrNull()
    }

    override suspend fun visibleTo(
        viewer: UserId,
        after: MomentId?,
        now: Instant,
        limit: Int,
    ) = moments.values
        .filter { it.expiresAt > now && (after == null || it.momentId.value > after.value) }
        .mapNotNull { moment -> audience[moment.momentId]?.get(viewer)?.let { moment to it } }
        .sortedBy { it.first.momentId.value }
        .take(limit)

    override suspend fun postedBy(
        posterId: UserId,
        after: MomentId?,
        now: Instant,
        limit: Int,
    ) = moments.values
        .filter { it.posterId == posterId && it.expiresAt > now && (after == null || it.momentId.value > after.value) }
        .sortedBy { it.momentId.value }
        .take(limit)

    override suspend fun viewCounts(momentIds: Collection<MomentId>) =
        momentIds
            .associateWith { id -> audience[id].orEmpty().values.count { it.viewedAt != null } }
            .filterValues { it > 0 }

    override suspend fun recordView(
        momentId: MomentId,
        viewer: UserId,
        at: Instant,
        reacted: Boolean?,
    ): AudienceMember? {
        val rows = audience[momentId] ?: return null
        val row = rows[viewer] ?: return null
        val after = row.copy(viewedAt = row.viewedAt ?: at, reacted = reacted ?: row.reacted)
        rows[viewer] = after
        return after
    }

    override suspend fun views(momentId: MomentId) =
        audience[momentId]
            .orEmpty()
            .values
            .filter { it.viewedAt != null }
            .sortedBy { it.viewedAt }
            .map { ViewRecord(it.userId, it.viewedAt!!, it.reacted) }

    override suspend fun delete(momentId: MomentId): Boolean {
        audience.remove(momentId)
        return moments.remove(momentId) != null
    }

    override suspend fun allPostedBy(posterId: UserId) = moments.values.filter { it.posterId == posterId }

    override suspend fun leaveAudiences(userId: UserId) = audience.values.count { it.remove(userId) != null }

    fun snapshot() = moments.toMap() to audience.mapValues { it.value.toMap() }

    fun restore(saved: Pair<Map<MomentId, Moment>, Map<MomentId, Map<UserId, AudienceMember>>>) {
        moments.clear()
        moments.putAll(saved.first)
        audience.clear()
        saved.second.forEach { (id, rows) -> audience[id] = rows.toMutableMap() }
    }
}

/** `media` in memory. */
class MemoryMedia : MediaRepository {
    val rows = linkedMapOf<UUID, MomentMedia>()

    override suspend fun insert(media: MomentMedia) {
        rows[media.mediaId] = media
    }

    override suspend fun find(mediaId: UUID) = rows[mediaId]

    override suspend fun markReady(mediaId: UUID): Boolean {
        val row = rows[mediaId]?.takeIf { it.state == MediaState.PENDING } ?: return false
        rows[mediaId] = row.copy(state = MediaState.READY)
        return true
    }

    override suspend fun attach(
        mediaId: UUID,
        posterId: UserId,
        momentId: MomentId,
        now: Instant,
        expiresAt: Instant,
    ): Boolean {
        val row = rows[mediaId] ?: return false
        val usable =
            row.posterId == posterId && row.state == MediaState.READY && row.momentId == null && row.expiresAt > now
        if (!usable) return false
        rows[mediaId] = row.copy(momentId = momentId, expiresAt = expiresAt)
        return true
    }

    override suspend fun expireFor(momentId: MomentId, now: Instant) =
        rows.values.filter { it.momentId == momentId }.map { row ->
            row.copy(expiresAt = now).also { rows[row.mediaId] = it }
        }

    override suspend fun purgeable(now: Instant, limit: Int) =
        rows.values
            .filter { it.expiresAt <= now }
            .sortedBy { it.expiresAt }
            .take(limit)

    override suspend fun delete(mediaId: UUID) = rows.remove(mediaId) != null

    override suspend fun allOf(posterId: UserId) = rows.values.filter { it.posterId == posterId }
}

/** Runs the block as if in a transaction: an error leaves both tables as they were. */
class MemoryTransactor(private val moments: MemoryMoments, private val media: MemoryMedia) : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = block()

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> {
        val savedMoments = moments.snapshot()
        val savedMedia = media.rows.toMap()
        val result = block()
        if (result is Resource.Error) {
            moments.restore(savedMoments)
            media.rows.clear()
            media.rows.putAll(savedMedia)
        }
        return result
    }
}

/** Object storage in memory: [put] stands in for the phone's PUT; [failing] makes every delete fail. */
class MemoryStorage : MediaStorage {
    val objects = mutableMapOf<String, Long>()
    var failing = false

    private fun key(posterId: UserId, mediaId: UUID) = "media/$posterId/$mediaId"

    fun put(
        posterId: UserId,
        mediaId: UUID,
        sizeBytes: Long,
    ) {
        objects[key(posterId, mediaId)] = sizeBytes
    }

    fun has(posterId: UserId, mediaId: UUID) = objects.containsKey(key(posterId, mediaId))

    override suspend fun prepareUpload(
        posterId: UserId,
        mediaId: UUID,
        expiresIn: Duration,
    ) = Resource.success("https://storage.test/upload/${key(posterId, mediaId)}")

    override suspend fun sizeOf(posterId: UserId, mediaId: UUID) = objects[key(posterId, mediaId)]

    override suspend fun prepareDownload(
        posterId: UserId,
        mediaId: UUID,
        expiresIn: Duration,
    ) = Resource.success("https://storage.test/download/${key(posterId, mediaId)}")

    override suspend fun delete(posterId: UserId, mediaId: UUID): Boolean {
        if (failing) return false
        objects.remove(key(posterId, mediaId))
        return true
    }
}

/** Every frame sent, to whom. */
class RecordingFrames : FrameSender {
    val sent = mutableListOf<Pair<UserId, StatusFrame>>()

    override suspend fun send(userIds: Collection<UserId>, frame: StatusFrame) {
        userIds.forEach { sent += it to frame }
    }

    fun to(userId: UserId) = sent.filter { it.first == userId }.map { it.second }
}

/**
 * Everyone reaches everyone, except the one-way gaps in [cannot] (`from` can't reach `to`) and the pairs in
 * [blocked], which also can't reach each other.
 */
class FakeReach : ReachPolicy {
    val cannot = mutableSetOf<Pair<UserId, UserId>>()
    val blocked = mutableSetOf<Set<UserId>>()

    override suspend fun canReach(from: UserId, to: UserId) =
        from != to && (from to to) !in cannot && setOf(from, to) !in blocked

    override suspend fun reachable(from: UserId, to: Set<UserId>) = to.filterTo(mutableSetOf()) { canReach(from, it) }

    override suspend fun blockedEitherWay(a: UserId, b: UserId) = setOf(a, b) in blocked
}

/** A clock the test moves. */
class MovableClock(var now: Instant = Instant.parse("2026-11-03T09:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}
