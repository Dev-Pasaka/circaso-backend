package com.circaso.chama.data.cache

import com.circaso.chama.api.ChamaFrames
import com.circaso.chama.api.ContributionAddedFrame
import com.circaso.chama.api.GoalUpdatedFrame
import com.circaso.chama.api.RequestUpdatedFrame
import com.circaso.chama.data.tables.RealtimeTable
import com.circaso.chama.domain.ports.ChamaFrame
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.ports.LoggedFrame
import com.circaso.chama.domain.ports.RealtimeLog
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
 * Each member gets the frame at their next `seq` (`cha:seq:{userId}`), kept for 7 days in `chama.realtime`, then the
 * gateway delivers it (socket, or push where it words one). A failure is logged by type and dropped: the phone
 * catches up from the kept frames.
 */
class RealtimeFramesImpl(
    private val redis: RedisScope,
    private val database: Database,
    private val realtime: Realtime,
    private val clock: Clock,
) : FrameSender,
    RealtimeLog {
    private val log = LoggerFactory.getLogger(RealtimeFramesImpl::class.java)

    override suspend fun send(userIds: Collection<UserId>, frame: ChamaFrame) {
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

/** The envelope for a frame the domain names, at [seq]: its wire `type` and its class from `chama:api`. */
internal fun ChamaFrame.toEnvelope(seq: Long, ts: Instant): RealtimeEnvelope =
    when (this) {
        is ChamaFrame.GoalUpdated -> {
            RealtimeEnvelope.of(
                ChamaFrames.MODULE,
                ChamaFrames.GOAL_UPDATED,
                seq,
                GoalUpdatedFrame(chatId, name, target, contributed, byUserId),
                GoalUpdatedFrame.serializer(),
                ts,
            )
        }

        is ChamaFrame.ContributionAdded -> {
            RealtimeEnvelope.of(
                ChamaFrames.MODULE,
                ChamaFrames.CONTRIBUTION_ADDED,
                seq,
                ContributionAddedFrame(chatId, userId, displayName, goalName, amount, contributed, target),
                ContributionAddedFrame.serializer(),
                ts,
            )
        }

        is ChamaFrame.RequestUpdated -> {
            RealtimeEnvelope.of(
                ChamaFrames.MODULE,
                ChamaFrames.REQUEST_UPDATED,
                seq,
                RequestUpdatedFrame(
                    requestId,
                    chatId,
                    state,
                    requestedBy,
                    requesterName,
                    goalName,
                    amount,
                    approvals,
                    approvalsNeeded,
                    waitingOn,
                ),
                RequestUpdatedFrame.serializer(),
                ts,
            )
        }
    }

/** `chama.purge-expired`: keeps the daily partitions of `realtime` and `outbox` rolling. */
class ChamaExpiry(private val database: Database) {
    suspend fun run(): Int = PARTITIONED.sumOf { database.maintainDailyPartitions(it, RETENTION).count }

    private companion object {
        val PARTITIONED = listOf("realtime", "outbox")
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
