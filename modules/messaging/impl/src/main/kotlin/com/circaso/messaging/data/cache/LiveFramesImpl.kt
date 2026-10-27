package com.circaso.messaging.data.cache

import com.circaso.messaging.api.MessagingFrame
import com.circaso.messaging.domain.ports.LiveFrames
import com.circaso.shared.Realtime
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.circaso.messaging.data")

/**
 * The frames that are not replayed go once to all their users through `Realtime.deliverMany`: one publish on
 * `rt:fanout` however many there are (decision 2026-10-25). One envelope can't carry each user's own `seq`, and a
 * frame that is not kept must not take one (the phone would see a gap and replay), so they carry `seq` 0. The log
 * line names the frame type only, never ids or a ciphertext.
 */
class LiveFramesImpl(private val realtime: Realtime, private val clock: Clock) : LiveFrames {
    override suspend fun send(userIds: Collection<UserId>, frame: MessagingFrame) {
        if (userIds.isEmpty()) return
        val type = frame.type
        val envelope = frame.envelope(NOT_REPLAYED, clock.instant())
        try {
            val result = realtime.deliverMany(userIds, envelope)
            if (result is Resource.Error) log.warn("live frame not sent: type={} code={}", type, result.error.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("live frame not sent: type={} error={}", type, e.javaClass.name)
        }
    }

    private companion object {
        /** The `seq` of a frame that is never replayed; the phone doesn't record it as its last. */
        const val NOT_REPLAYED = 0L
    }
}
