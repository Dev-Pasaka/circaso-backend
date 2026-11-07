package com.circaso.support.data.cache

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.UserId
import com.circaso.support.api.SupportFrame
import com.circaso.support.api.SupportFrames
import com.circaso.support.api.TicketRepliedFrame
import com.circaso.support.api.TicketUpdatedFrame
import com.circaso.support.data.tables.RealtimeTable
import com.circaso.support.domain.ports.FrameSender
import com.circaso.support.domain.ports.LoggedFrame
import com.circaso.support.domain.ports.RealtimeLog
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
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.circaso.support.data")

/**
 * Each frame takes the user's next `seq` from `sup:seq:{userId}` (renewed on use, 8 days, so it outlives the 7 days
 * of kept frames), is kept in `support.realtime` (daily partitions, dropped by `support.purge-expired`), then goes to
 * the gateway, which pushes it when the phone has no socket. A failure is logged by type and dropped: the reply is
 * already saved, and the phone catches up from the kept frames or from SU2.
 */
class RealtimeFramesImpl(
    private val redis: RedisScope,
    private val database: Database,
    private val realtime: Realtime,
    private val clock: Clock,
) : FrameSender,
    RealtimeLog {
    override suspend fun send(userId: UserId, frame: SupportFrame) {
        try {
            val sentAt = clock.instant()
            val envelope = frame.envelope(redis.incrementRenewing(seqKey(userId), SEQ_TTL), sentAt)
            database.tx("realtime.keep") {
                RealtimeTable.insert {
                    it[RealtimeTable.userId] = userId.value
                    it[seq] = envelope.seq
                    it[RealtimeTable.frame] = CircasoJson.encodeToString(RealtimeEnvelope.serializer(), envelope)
                    it[RealtimeTable.sentAt] = sentAt
                }
            }
            realtime.deliver(userId, envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("realtime frame not sent: type={} error={}", frame.type, e.javaClass.name)
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
 * The envelope for a support frame, its `data` encoded with the frame's own serializer, so no `type` discriminator is
 * added and the compiler checks that every frame type has a line here.
 */
internal fun SupportFrame.envelope(seq: Long, ts: Instant): RealtimeEnvelope =
    when (this) {
        is TicketRepliedFrame -> {
            RealtimeEnvelope.of(SupportFrames.MODULE, type, seq, this, TicketRepliedFrame.serializer(), ts)
        }

        is TicketUpdatedFrame -> {
            RealtimeEnvelope.of(SupportFrames.MODULE, type, seq, this, TicketUpdatedFrame.serializer(), ts)
        }
    }
