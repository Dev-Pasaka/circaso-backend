package com.circaso.status.data.cache

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.UserId
import com.circaso.status.api.MomentDeletedFrame
import com.circaso.status.api.MomentPostedFrame
import com.circaso.status.api.MomentViewedFrame
import com.circaso.status.api.StatusFrame
import com.circaso.status.api.StatusFrames
import com.circaso.status.data.tables.RealtimeTable
import com.circaso.status.domain.ports.FrameSender
import com.circaso.status.domain.ports.LoggedFrame
import com.circaso.status.domain.ports.RealtimeLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.circaso.status.data")

/** Recipients handled together: their `seq`s taken at once, their rows one insert, their deliveries at once. */
private const val CHUNK = 100

/**
 * Each frame takes the recipient's next `seq` from `sts:seq:{userId}` (renewed on use), is kept 7 days in
 * `status.realtime` (daily partitions, dropped by `status.purge-expired`), then goes to the gateway, as in messaging.
 * A 1,024-person audience is kept with one multi-row insert per [CHUNK]. A failure is logged by type and dropped: the
 * phone catches up from the kept frames, or from ST4.
 */
class RealtimeFramesImpl(
    private val redis: RedisScope,
    private val database: Database,
    private val realtime: Realtime,
    private val clock: Clock,
) : FrameSender,
    RealtimeLog {
    override suspend fun send(userIds: Collection<UserId>, frame: StatusFrame) {
        for (chunk in userIds.distinct().chunked(CHUNK)) {
            try {
                sendChunk(chunk, frame)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("realtime frame not sent: type={} error={}", frame.type, e.javaClass.name)
            }
        }
    }

    private suspend fun sendChunk(userIds: List<UserId>, frame: StatusFrame) {
        val sentAt = clock.instant()
        val envelopes =
            coroutineScope {
                userIds.map { userId -> async { userId to envelopeFor(userId, frame, sentAt) } }.awaitAll()
            }
        database.tx("realtime.keep") {
            RealtimeTable.batchInsert(envelopes, useMultiRowValues = true, shouldReturnGeneratedValues = false) {
                val (userId, envelope) = it
                this[RealtimeTable.userId] = userId.value
                this[RealtimeTable.seq] = envelope.seq
                this[RealtimeTable.frame] = CircasoJson.encodeToString(RealtimeEnvelope.serializer(), envelope)
                this[RealtimeTable.sentAt] = sentAt
            }
        }
        coroutineScope { envelopes.map { (userId, envelope) -> async { deliver(userId, envelope) } }.awaitAll() }
    }

    private suspend fun envelopeFor(
        userId: UserId,
        frame: StatusFrame,
        sentAt: Instant,
    ): RealtimeEnvelope = frame.envelope(redis.incrementRenewing(seqKey(userId), SEQ_TTL), sentAt)

    private suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope) {
        try {
            realtime.deliver(userId, envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("realtime frame not delivered: type={} error={}", envelope.type, e.javaClass.name)
        }
    }

    override suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame> =
        database.tx("realtime.since") {
            RealtimeTable
                .selectAll()
                .where { (RealtimeTable.userId eq userId.value) and (RealtimeTable.seq greater seq) }
                .orderBy(RealtimeTable.seq to SortOrder.ASC)
                .limit(limit)
                .map { LoggedFrame(it[RealtimeTable.seq], it[RealtimeTable.frame]) }
                .toList()
        }

    override suspend fun latest(userId: UserId): Long? = redis.get(seqKey(userId))?.toLongOrNull()

    private fun seqKey(userId: UserId) = "seq:$userId"

    private companion object {
        val SEQ_TTL: Duration = Duration.ofDays(8)
    }
}

/**
 * The envelope for a status frame, its `data` encoded with the frame's own serializer, so no `type` discriminator is
 * added and the compiler checks that every frame type has a line here.
 */
internal fun StatusFrame.envelope(seq: Long, ts: Instant): RealtimeEnvelope =
    when (this) {
        is MomentPostedFrame -> {
            RealtimeEnvelope.of(StatusFrames.MODULE, type, seq, this, MomentPostedFrame.serializer(), ts)
        }

        is MomentDeletedFrame -> {
            RealtimeEnvelope.of(StatusFrames.MODULE, type, seq, this, MomentDeletedFrame.serializer(), ts)
        }

        is MomentViewedFrame -> {
            RealtimeEnvelope.of(StatusFrames.MODULE, type, seq, this, MomentViewedFrame.serializer(), ts)
        }
    }
