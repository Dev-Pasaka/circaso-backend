package com.circaso.ramp.data.cache

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.RedisScope
import com.circaso.ramp.api.OrderUpdatedFrame
import com.circaso.ramp.api.RampFrames
import com.circaso.ramp.data.tables.RealtimeTable
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.ports.FrameSender
import com.circaso.ramp.domain.ports.LoggedFrame
import com.circaso.ramp.domain.ports.RealtimeLog
import com.circaso.ramp.domain.support.Amounts
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
 * The user gets `ramp/order.updated` at their next `seq` (`rmp:seq:{userId}`), kept for 7 days in `ramp.realtime`,
 * then the gateway delivers it (socket, or push where it words one). It works the same from the ops process, which
 * completes an order: the sequence and the kept frame are shared, and delivery crosses to the app's node over Redis,
 * as a staff reply to a ticket does. A failure is logged and dropped: the frame is a doorbell, and the phone reads
 * the order over HTTP.
 */
class RealtimeFramesImpl(
    private val redis: RedisScope,
    private val database: Database,
    private val realtime: Realtime,
    private val clock: Clock,
) : FrameSender,
    RealtimeLog {
    private val log = LoggerFactory.getLogger(RealtimeFramesImpl::class.java)

    override suspend fun orderUpdated(order: Order) {
        try {
            val seq = redis.incrementRenewing("seq:${order.userId}", SEQ_TTL)
            val sentAt = clock.instant()
            val envelope = order.toEnvelope(seq, sentAt)
            database.tx("realtime.keep") {
                RealtimeTable.insert {
                    it[userId] = order.userId.value
                    it[this.seq] = seq
                    it[frame] = CircasoJson.encodeToString(RealtimeEnvelope.serializer(), envelope)
                    it[this.sentAt] = sentAt
                }
            }
            realtime.deliver(order.userId, envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("ramp frame not sent: state={} error={}", order.state, e.javaClass.simpleName)
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

/** The `order.updated` envelope for [this] order at [seq]. */
internal fun Order.toEnvelope(seq: Long, ts: Instant): RealtimeEnvelope =
    RealtimeEnvelope.of(
        RampFrames.MODULE,
        RampFrames.ORDER_UPDATED,
        seq,
        OrderUpdatedFrame(
            orderId = orderId,
            direction = direction.name,
            state = state.name,
            blockchain = blockchain,
            pay = Amounts.fiat(fiat),
            receive = Amounts.usdc(assetMinor),
            failureReason = failureReason,
        ),
        OrderUpdatedFrame.serializer(),
        ts,
    )

/**
 * `ramp.purge-expired`: deletes expired quotes and portal sessions, and keeps the daily partitions of `realtime` and
 * `outbox` rolling.
 */
class RampExpiry(private val database: Database) {
    suspend fun run(): Int =
        EXPIRING.sumOf { database.purgeExpired(it) } +
            PARTITIONED.sumOf { database.maintainDailyPartitions(it, RETENTION).count }

    private companion object {
        val EXPIRING = listOf("quotes", "merchant_sessions")
        val PARTITIONED = listOf("realtime", "outbox")
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
