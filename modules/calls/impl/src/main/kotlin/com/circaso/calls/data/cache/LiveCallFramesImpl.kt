package com.circaso.calls.data.cache

import com.circaso.calls.api.CallFrames
import com.circaso.calls.api.CallHeartbeatFrame
import com.circaso.calls.api.CallId
import com.circaso.calls.domain.ports.LiveCallFrames
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * `call.heartbeat` to everyone named with one `Realtime.deliverMany` (one publish), `seq` 0: a frame that is not kept
 * must not take a `seq`, or the phone would see a gap and replay. The log names the frame type only.
 */
class LiveCallFramesImpl(private val realtime: Realtime, private val clock: Clock) : LiveCallFrames {
    private val log = LoggerFactory.getLogger(LiveCallFramesImpl::class.java)

    override suspend fun heartbeat(
        userIds: Collection<UserId>,
        callId: CallId,
        from: UserId,
    ) {
        if (userIds.isEmpty()) return
        val envelope =
            RealtimeEnvelope.of(
                CallFrames.MODULE,
                CallFrames.HEARTBEAT,
                NOT_REPLAYED,
                CallHeartbeatFrame(callId, from),
                CallHeartbeatFrame.serializer(),
                clock.instant(),
            )
        try {
            val result = realtime.deliverMany(userIds, envelope)
            if (result is Resource.Error) {
                log.warn(
                    "live frame not sent: type={} code={}",
                    CallFrames.HEARTBEAT,
                    result.error.code,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("live frame not sent: type={} error={}", CallFrames.HEARTBEAT, e.javaClass.name)
        }
    }

    private companion object {
        const val NOT_REPLAYED = 0L
    }
}
