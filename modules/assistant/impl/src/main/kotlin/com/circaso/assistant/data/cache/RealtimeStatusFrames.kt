package com.circaso.assistant.data.cache

import com.circaso.assistant.api.AssistantFrames
import com.circaso.assistant.api.AssistantStatusFrame
import com.circaso.assistant.domain.models.AshaStatus
import com.circaso.assistant.domain.ports.StatusFrames
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock

private val log = LoggerFactory.getLogger("com.circaso.assistant.data")

/**
 * `assistant/status` to many users through `Realtime.deliverMany`: one publish, and the gateway pushes "Asha is back"
 * to those without an open socket. Not kept or replayed, so it carries `seq` 0 (the app reads A14 on opening Asha); a
 * failure is logged and dropped.
 */
class RealtimeStatusFrames(private val realtime: Realtime, private val clock: Clock) : StatusFrames {
    override suspend fun send(userIds: Collection<UserId>, status: AshaStatus) {
        if (userIds.isEmpty()) return
        val envelope =
            RealtimeEnvelope.of(
                AssistantFrames.MODULE,
                AssistantFrames.STATUS,
                NOT_REPLAYED,
                AssistantStatusFrame(status.isAvailable, status.since?.toString()),
                AssistantStatusFrame.serializer(),
                clock.instant(),
            )
        try {
            val result = realtime.deliverMany(userIds, envelope)
            if (result is Resource.Error) log.warn("status frame not sent: code={}", result.error.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("status frame not sent: error={}", e.javaClass.name)
        }
    }

    private companion object {
        const val NOT_REPLAYED = 0L
    }
}
