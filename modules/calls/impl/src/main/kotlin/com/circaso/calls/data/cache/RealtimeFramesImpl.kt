package com.circaso.calls.data.cache

import com.circaso.calls.api.CallFrames
import com.circaso.calls.api.CallParticipantSnapshot
import com.circaso.calls.api.CallRingStoppedFrame
import com.circaso.calls.api.CallRingingFrame
import com.circaso.calls.api.CallSnapshot
import com.circaso.calls.api.CallTrackSnapshot
import com.circaso.calls.api.IceServerSnapshot
import com.circaso.calls.data.tables.RealtimeTable
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.ports.CallFrame
import com.circaso.calls.domain.ports.FrameSender
import com.circaso.calls.domain.ports.LoggedFrame
import com.circaso.calls.domain.ports.RealtimeLog
import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Each person gets the frame at their next `seq` (`cal:seq:{userId}`), kept for 7 days in `calls.realtime`, then the
 * gateway delivers it (socket, or push where it words one). A failure is logged by type and dropped: the phone
 * catches up from the kept frames, or from CL2.
 */
class RealtimeFramesImpl(
    private val redis: RedisScope,
    private val database: Database,
    private val realtime: Realtime,
    private val clock: Clock,
) : FrameSender,
    RealtimeLog {
    private val log = LoggerFactory.getLogger(RealtimeFramesImpl::class.java)

    override suspend fun send(userIds: Collection<UserId>, frame: CallFrame) {
        userIds.distinct().forEach { userId ->
            try {
                val seq = redis.incrementRenewing("seq:$userId", SEQ_TTL)
                val sentAt = clock.instant()
                val envelope = frame.toEnvelope(seq, sentAt)
                database.tx("realtime.keep") {
                    RealtimeTable.insert {
                        it[this.userId] = userId.value
                        it[this.seq] = seq
                        it[this.frame] = CircasoJson.encodeToString(RealtimeEnvelope.serializer(), envelope)
                        it[this.sentAt] = sentAt
                    }
                }
                realtime.deliver(userId, envelope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(
                    "realtime frame not sent: type={} error={}",
                    frame.javaClass.simpleName,
                    e.javaClass.simpleName,
                )
            }
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

    override suspend fun latest(userId: UserId): Long? = redis.get("seq:$userId")?.toLongOrNull()

    private companion object {
        val SEQ_TTL: Duration = Duration.ofDays(8)
    }
}

/** The envelope for a frame the domain names, at [seq]: its wire `type` and its class from `calls:api`. */
internal fun CallFrame.toEnvelope(seq: Long, ts: Instant): RealtimeEnvelope =
    when (this) {
        is CallFrame.Ringing -> {
            RealtimeEnvelope.of(
                CallFrames.MODULE,
                CallFrames.RINGING,
                seq,
                CallRingingFrame(callId, chatId, kind.name, callerId, callerName),
                CallRingingFrame.serializer(),
                ts,
            )
        }

        is CallFrame.Updated -> {
            RealtimeEnvelope.of(
                CallFrames.MODULE,
                CallFrames.UPDATED,
                seq,
                view.toSnapshot(),
                CallSnapshot.serializer(),
                ts,
            )
        }

        is CallFrame.Ended -> {
            RealtimeEnvelope.of(
                CallFrames.MODULE,
                CallFrames.ENDED,
                seq,
                view.toSnapshot(),
                CallSnapshot.serializer(),
                ts,
            )
        }

        is CallFrame.RingStopped -> {
            RealtimeEnvelope.of(
                CallFrames.MODULE,
                CallFrames.RING_STOPPED,
                seq,
                CallRingStoppedFrame(callId, chatId, reason),
                CallRingStoppedFrame.serializer(),
                ts,
            )
        }
    }

/** The call in the shape of `calls:api`'s [CallSnapshot]; sessions and mids stay on the server. */
internal fun CallView.toSnapshot(): CallSnapshot =
    CallSnapshot(
        callId = call.callId,
        chatId = call.chatId,
        kind = call.kind.name,
        state = call.state.name,
        startedBy = call.startedBy,
        createdAt = call.createdAt.toString(),
        startedAt = call.startedAt?.toString(),
        endedAt = call.endedAt?.toString(),
        endReason = call.endReason?.name,
        participants =
            call.participants.map { participant ->
                CallParticipantSnapshot(
                    userId = participant.userId,
                    displayName = names[participant.userId] ?: "Someone",
                    state = participant.state.name,
                    audio = participant.audio,
                    video = participant.video,
                    tracks = participant.tracks.map { CallTrackSnapshot(it.trackName, it.kind) },
                    joinedAt = participant.joinedAt?.toString(),
                    connection =
                        com.circaso.calls.domain.support.Connection
                            .tag(participant.sessionId),
                    connected = participant.isConnected,
                )
            },
        iceServers = iceServers.map { IceServerSnapshot(it.urls, it.username, it.credential) },
    )

/** `calls.purge-expired`: keeps the daily partitions of `realtime` and `outbox` rolling. */
class CallsExpiry(private val database: Database) {
    suspend fun run(): Int = PARTITIONED.sumOf { database.maintainDailyPartitions(it, RETENTION).count }

    private companion object {
        val PARTITIONED = listOf("realtime", "outbox")
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
