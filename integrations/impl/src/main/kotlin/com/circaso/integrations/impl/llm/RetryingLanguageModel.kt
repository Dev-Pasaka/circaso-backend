package com.circaso.integrations.impl.llm

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration

/** How long a turn of each purpose waits before its one retry, and the longest `Retry-After` it will wait for. */
data class RetryBudget(val wait: Duration, val longestRetryAfter: Duration)

/**
 * One quick retry of the same model (decision 2026-11-07: Claude only, no fallback model). A turn whose model fails
 * **before its first word** with a transient [IntegrationError.Unavailable] (a dropped connection, an overloaded or
 * failing server, a rate limit) is asked once more, with the very same request, after [budgets]' wait for its purpose:
 * short for a spoken turn, which is waiting in someone's ear, longer for a typed one. A `Retry-After` longer than the
 * purpose allows isn't waited for. Once the first words or a tool call have come, a failure is the turn's own (what was
 * spoken stays, the half-written sentence is dropped), and the second failure is the turn's too.
 *
 * Every retry logs why; never anything of the conversation. [probe] is never retried: it is the health monitor's.
 */
class RetryingLanguageModel(
    private val model: LanguageModel,
    private val name: String,
    private val budgets: Map<ModelPurpose, RetryBudget> = DEFAULT_BUDGETS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : LanguageModel {
    private val log = LoggerFactory.getLogger(RetryingLanguageModel::class.java)

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        val first = model.complete(request)
        if (first !is Resource.Error || !retries(request, first.error)) return first
        return model.complete(request)
    }

    override suspend fun stream(request: ModelRequest): Resource<ModelStream> =
        when (val opened = model.stream(request)) {
            is Resource.Success -> {
                Resource.success(RetryStream(request, opened.data))
            }

            is Resource.Error -> {
                if (!retries(request, opened.error)) {
                    opened
                } else {
                    model.stream(request)
                }
            }
        }

    override suspend fun probe(): Resource<Unit> = model.probe()

    /** Whether [error] is worth the one retry; if so, waits for it and says so in the log. */
    private suspend fun retries(request: ModelRequest, error: DomainError?): Boolean {
        val budget = budgets.getValue(request.purpose)
        val unavailable = error as? IntegrationError.Unavailable
        val retryAfter = unavailable?.retryAfterSeconds?.let { Duration.ofSeconds(it) }
        val worth =
            when {
                // the stream ended without a word or an error: as transient as a dropped connection
                error == null -> true

                unavailable == null || !unavailable.isTransient -> false

                retryAfter != null && retryAfter > budget.longestRetryAfter -> false

                else -> true
            }
        if (!worth) return false
        val wait = maxOf(budget.wait, retryAfter ?: Duration.ZERO)
        log.warn(
            "asha model retry: purpose={} model={} reason={} waitMs={}",
            request.purpose,
            name,
            error?.code ?: "ended_without_answer",
            wait.toMillis(),
        )
        sleep(wait.toMillis())
        return true
    }

    /** Passes [current] on; if it fails before its first word, carries on with one more answer of the same model. */
    private inner class RetryStream(private val request: ModelRequest, private var current: ModelStream) : ModelStream {
        private var hasStarted = false
        private var hasRetried = false

        override suspend fun next(): ModelStreamEvent? {
            while (true) {
                when (val event = current.next()) {
                    is ModelStreamEvent.Text -> {
                        if (event.delta.isNotEmpty()) hasStarted = true
                        return event
                    }

                    is ModelStreamEvent.ToolCallDelta -> {
                        hasStarted = true
                        return event
                    }

                    is ModelStreamEvent.Done -> {
                        return event
                    }

                    is ModelStreamEvent.Failed, null -> {
                        val error = (event as? ModelStreamEvent.Failed)?.error
                        if (hasStarted || hasRetried || !retries(request, error)) return event
                        hasRetried = true
                        current.close()
                        current =
                            when (val second = model.stream(request)) {
                                is Resource.Success -> second.data
                                is Resource.Error -> return failedWith(second.error)
                            }
                    }
                }
            }
        }

        override suspend fun close() = current.close()
    }

    private fun failedWith(error: DomainError) =
        ModelStreamEvent.Failed(error as? IntegrationError ?: IntegrationError.Unavailable(name))

    companion object {
        /**
         * A spoken turn waits 300 ms and no `Retry-After` beyond a second; a typed one a second and up to two. Either
         * stays well inside what a person waits for a first word.
         */
        val DEFAULT_BUDGETS: Map<ModelPurpose, RetryBudget> =
            mapOf(
                ModelPurpose.VOICE to RetryBudget(Duration.ofMillis(300), Duration.ofSeconds(1)),
                ModelPurpose.CHAT to RetryBudget(Duration.ofSeconds(1), Duration.ofSeconds(2)),
            )
    }
}
