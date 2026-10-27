package com.circaso.users.data.cache

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.UserId
import com.circaso.users.data.tables.RealtimeTable
import com.circaso.users.domain.ports.LoggedFrame
import com.circaso.users.domain.ports.RealtimeFrames
import com.circaso.users.domain.ports.RealtimeLog
import com.circaso.users.domain.ports.UsersFrame
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

private val log = LoggerFactory.getLogger("com.circaso.users.data")

/**
 * Each frame takes the next `seq` from `usr:seq:{userId}` (renewed on use), is kept for 7 days in `users.realtime`
 * (daily partitions, dropped by `users.purge-expired`), then goes to the gateway. A failure to deliver is logged and
 * dropped: the phone catches up from the kept frames.
 */
class RealtimeFramesImpl(
    private val redis: RedisScope,
    private val database: Database,
    private val realtime: Realtime,
    private val clock: Clock,
) : RealtimeFrames,
    RealtimeLog {
    override suspend fun send(userId: UserId, frame: UsersFrame) {
        runCatching {
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
        }.onFailure { log.warn("realtime frame not sent: type={}", frame.javaClass.simpleName, it) }
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
                .toList()
                .map { LoggedFrame(it[RealtimeTable.seq], it[RealtimeTable.frame]) }
        }

    override suspend fun latest(userId: UserId): Long? = redis.get("seq:$userId")?.toLongOrNull()

    private companion object {
        val SEQ_TTL: Duration = Duration.ofDays(8)
    }
}
