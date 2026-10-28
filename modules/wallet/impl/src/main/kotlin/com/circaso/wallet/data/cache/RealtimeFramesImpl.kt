package com.circaso.wallet.data.cache

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.UserId
import com.circaso.wallet.data.tables.RealtimeTable
import com.circaso.wallet.domain.ports.FrameSender
import com.circaso.wallet.domain.ports.LoggedFrame
import com.circaso.wallet.domain.ports.RealtimeLog
import com.circaso.wallet.domain.ports.WalletFrame
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

/**
 * Each frame takes the next `seq` from `wal:seq:{userId}` (renewed on use), is kept for 7 days in `wallet.realtime`
 * (daily partitions, dropped by `wallet.purge-expired`), then goes to the gateway (socket, or push for the frames
 * worth one). A failure to deliver is logged by frame type and dropped: the phone catches up from the kept frames.
 */
class RealtimeFramesImpl(
    private val redis: RedisScope,
    private val database: Database,
    private val realtime: Realtime,
    private val clock: Clock,
) : FrameSender,
    RealtimeLog {
    private val log = LoggerFactory.getLogger(RealtimeFramesImpl::class.java)

    override suspend fun send(userId: UserId, frame: WalletFrame) {
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
            log.warn("realtime frame not sent: type={} error={}", frame.javaClass.simpleName, e.javaClass.simpleName)
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
