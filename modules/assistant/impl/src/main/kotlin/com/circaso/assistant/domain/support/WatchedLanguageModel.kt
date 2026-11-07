package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.ports.AshaHealth
import com.circaso.assistant.domain.ports.HealthLog
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * Asha's model as the agent sees it, watched for her health (decision 2026-11-07). A turn whose model is unavailable
 * before its first word (after the provider's one quick retry) marks her down; any answer marks her up again. A model
 * that refused the request ([IntegrationError.Rejected]) is not down, and a failure after the first words is the
 * turn's own.
 *
 * The health store is asked at most every few seconds per node while she is up, so a healthy turn costs nothing, and
 * a health store that can't be reached never fails a turn.
 */
class WatchedLanguageModel(
    private val model: LanguageModel,
    private val health: AshaHealth,
    private val clock: Clock,
    private val log: HealthLog = HealthLog.None,
) : LanguageModel {
    @Volatile private var knownDown = false

    @Volatile private var checkedAt = Long.MIN_VALUE

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> =
        model.complete(request).also {
            when (it) {
                is Resource.Success -> answered()
                is Resource.Error -> failed(it.error)
            }
        }

    override suspend fun stream(request: ModelRequest): Resource<ModelStream> =
        when (val opened = model.stream(request)) {
            is Resource.Success -> {
                Resource.success(Watched(opened.data))
            }

            is Resource.Error -> {
                failed(opened.error)
                opened
            }
        }

    override suspend fun probe(): Resource<Unit> = model.probe()

    private suspend fun failed(error: DomainError?) {
        if (error != null && error !is IntegrationError.Unavailable) return
        knownDown = true
        quietly {
            if (health.markDown(clock.instant())) log.down(error?.code ?: "ended_without_answer")
        }
    }

    private suspend fun answered() {
        val now = clock.millis()
        if (!knownDown && now - checkedAt < RECHECK_MILLIS) return
        checkedAt = now
        knownDown = false
        quietly { if (health.markUp(clock.instant())) log.back("turn", null) }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.storeFailed(e.javaClass.simpleName)
        }
    }

    private inner class Watched(private val stream: ModelStream) : ModelStream {
        private var hasStarted = false

        override suspend fun next(): ModelStreamEvent? {
            val event = stream.next()
            when (event) {
                is ModelStreamEvent.Text -> if (event.delta.isNotEmpty()) hasStarted = true
                is ModelStreamEvent.ToolCallDelta -> hasStarted = true
                is ModelStreamEvent.Done -> answered()
                is ModelStreamEvent.Failed -> if (!hasStarted) failed(event.error)
                null -> if (!hasStarted) failed(null)
            }
            return event
        }

        override suspend fun close() = stream.close()
    }

    private companion object {
        /** While she is up, a node asks the health store at most this often whether she was down. */
        const val RECHECK_MILLIS = 5_000L
    }
}
