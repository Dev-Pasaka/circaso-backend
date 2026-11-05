package com.circaso.spaces.data.cache

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.UserId
import com.circaso.spaces.api.InviteAcceptedFrame
import com.circaso.spaces.api.InviteRemovedFrame
import com.circaso.spaces.api.InvitedFrame
import com.circaso.spaces.api.MembershipUpdatedFrame
import com.circaso.spaces.api.PostNewFrame
import com.circaso.spaces.api.PostUpdatedFrame
import com.circaso.spaces.api.PriceChangedFrame
import com.circaso.spaces.api.ReportNewFrame
import com.circaso.spaces.api.ReportResolvedFrame
import com.circaso.spaces.api.RequestNewFrame
import com.circaso.spaces.api.SpaceFrame
import com.circaso.spaces.api.SpaceUpdatedFrame
import com.circaso.spaces.api.SpacesFrames
import com.circaso.spaces.data.tables.RealtimeTable
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.ports.LoggedFrame
import com.circaso.spaces.domain.ports.RealtimeLog
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

private val log = LoggerFactory.getLogger("com.circaso.spaces.data")

/** Recipients handled together: their `seq`s taken at once, their rows one insert, their deliveries at once. */
private const val CHUNK = 100

/**
 * Each frame takes the recipient's next `seq` from `spc:seq:{userId}` (renewed on use), is kept 7 days in
 * `spaces.realtime` (daily partitions, dropped by `spaces.purge-expired`), then goes to the gateway, as in messaging.
 * A big space is kept with one multi-row insert per [CHUNK]. A failure is logged by type and dropped: the phone
 * catches up from the kept frames, or by reading the space again.
 */
class RealtimeFramesImpl(
    private val redis: RedisScope,
    private val database: Database,
    private val realtime: Realtime,
    private val clock: Clock,
) : FrameSender,
    RealtimeLog {
    override suspend fun send(userIds: Collection<UserId>, frame: SpaceFrame) {
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

    private suspend fun sendChunk(userIds: List<UserId>, frame: SpaceFrame) {
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
        frame: SpaceFrame,
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
 * The envelope for a spaces frame, its `data` encoded with the frame's own serializer, so no `type` discriminator is
 * added and the compiler checks that every frame type has a line here.
 */
internal fun SpaceFrame.envelope(seq: Long, ts: Instant): RealtimeEnvelope =
    when (this) {
        is PostNewFrame -> {
            RealtimeEnvelope.of(SpacesFrames.MODULE, type, seq, this, PostNewFrame.serializer(), ts)
        }

        is PostUpdatedFrame -> {
            RealtimeEnvelope.of(
                SpacesFrames.MODULE,
                type,
                seq,
                this,
                PostUpdatedFrame.serializer(),
                ts,
            )
        }

        is SpaceUpdatedFrame -> {
            RealtimeEnvelope.of(
                SpacesFrames.MODULE,
                type,
                seq,
                this,
                SpaceUpdatedFrame.serializer(),
                ts,
            )
        }

        is MembershipUpdatedFrame -> {
            RealtimeEnvelope.of(
                SpacesFrames.MODULE,
                type,
                seq,
                this,
                MembershipUpdatedFrame.serializer(),
                ts,
            )
        }

        is PriceChangedFrame -> {
            RealtimeEnvelope.of(
                SpacesFrames.MODULE,
                type,
                seq,
                this,
                PriceChangedFrame.serializer(),
                ts,
            )
        }

        is InvitedFrame -> {
            RealtimeEnvelope.of(SpacesFrames.MODULE, type, seq, this, InvitedFrame.serializer(), ts)
        }

        is InviteRemovedFrame -> {
            RealtimeEnvelope.of(
                SpacesFrames.MODULE,
                type,
                seq,
                this,
                InviteRemovedFrame.serializer(),
                ts,
            )
        }

        is InviteAcceptedFrame -> {
            RealtimeEnvelope.of(
                SpacesFrames.MODULE,
                type,
                seq,
                this,
                InviteAcceptedFrame.serializer(),
                ts,
            )
        }

        is RequestNewFrame -> {
            RealtimeEnvelope.of(
                SpacesFrames.MODULE,
                type,
                seq,
                this,
                RequestNewFrame.serializer(),
                ts,
            )
        }

        is ReportNewFrame -> {
            RealtimeEnvelope.of(SpacesFrames.MODULE, type, seq, this, ReportNewFrame.serializer(), ts)
        }

        is ReportResolvedFrame -> {
            RealtimeEnvelope.of(
                SpacesFrames.MODULE,
                type,
                seq,
                this,
                ReportResolvedFrame.serializer(),
                ts,
            )
        }
    }
