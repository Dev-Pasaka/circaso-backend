package com.circaso.messaging.data.cache

import com.circaso.infrastructure.api.RedisScope
import com.circaso.messaging.data.entities.MessagingRealtimeRow
import com.circaso.messaging.domain.ports.LoggedFrame
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.messaging.domain.ports.RealtimeLog
import com.circaso.shared.CircasoJson
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.repository.KandraSuspendRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.circaso.messaging.data")

/** At most this many recipients are being written and delivered at the same time. */
private const val PARALLEL = 16

/**
 * Each replayed frame takes the recipient's next `seq` from `msg:seq:{userId}` (renewed on use), is kept 7 days in
 * `realtime_by_user`, then goes to the gateway, as in users. The `seq` is per user, so a replayed frame is one
 * envelope per recipient through `Realtime.deliver`: `deliverMany` carries one envelope, one `seq`, for everyone.
 * A failure to deliver is logged and dropped: the phone catches up from the kept frames.
 */
class RealtimeFramesImpl(
    private val redis: RedisScope,
    private val frames: KandraSuspendRepository<MessagingRealtimeRow>,
    private val realtime: Realtime,
    private val clock: Clock,
) : RealtimeFrames,
    RealtimeLog {
    override suspend fun send(
        userIds: Collection<UserId>,
        type: String,
        data: Map<String, Any>,
    ) {
        val payload = JsonObject(data.mapValues { (_, value) -> value.toJson() })
        for (batch in userIds.distinct().chunked(PARALLEL)) {
            coroutineScope {
                batch.map { userId -> async { sendOne(userId, type, payload) } }.awaitAll()
            }
        }
    }

    private suspend fun sendOne(
        userId: UserId,
        type: String,
        payload: JsonObject,
    ) {
        try {
            val seq = redis.incrementRenewing(seqKey(userId), SEQ_TTL)
            val envelope = RealtimeEnvelope(MODULE, type, Uuid7.generate().toString(), seq, clock.instant(), payload)
            frames.save(
                MessagingRealtimeRow(
                    userId.value,
                    seq,
                    CircasoJson.encodeToString(RealtimeEnvelope.serializer(), envelope),
                ),
            )
            realtime.deliver(userId, envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("realtime frame not sent: type={}", type, e)
        }
    }

    override suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame> =
        frames
            .findAll(limit) {
                KandraColumnRef<UUID>("user_id") eq userId.value
                KandraColumnRef<Long>("seq") gt seq
            }.map { LoggedFrame(it.seq, it.frame) }
            .sortedBy { it.seq }

    override suspend fun latest(userId: UserId): Long? = redis.get(seqKey(userId))?.toLongOrNull()

    private fun seqKey(userId: UserId) = "seq:$userId"

    private fun Any.toJson(): JsonPrimitive =
        when (this) {
            is Number -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            else -> JsonPrimitive(toString())
        }

    private companion object {
        const val MODULE = "messaging"
        val SEQ_TTL: Duration = Duration.ofDays(8)
    }
}
