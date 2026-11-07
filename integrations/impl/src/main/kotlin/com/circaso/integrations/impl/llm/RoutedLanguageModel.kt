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
import org.slf4j.LoggerFactory

/** The model for one purpose, by name for the logs, and the one that answers instead when it can't. */
class ModelRoute(
    val model: LanguageModel,
    val name: String,
    val fallback: LanguageModel? = null,
    val fallbackName: String? = null,
)

/**
 * Asha's text model per [ModelPurpose] (decision 2026-11-07): a typed turn goes to [chat], a spoken one to [voice], each
 * with its own fallback, so Asha never goes silent when her model can't answer.
 *
 * A turn falls back **once**, and only before the user has seen or heard anything of it: the model couldn't be reached,
 * refused the request, declined to answer, or ended without a word or a tool call. The fallback then gets the very same
 * request. Once the first words or a tool call have come, a failure is the turn's own, as it was before (what was
 * spoken stays, the half-written sentence is dropped).
 *
 * Every answer logs which model wrote it, and every fallback why; never anything of the conversation.
 */
class RoutedLanguageModel(private val chat: ModelRoute, private val voice: ModelRoute) : LanguageModel {
    private val log = LoggerFactory.getLogger(RoutedLanguageModel::class.java)

    private fun routeOf(request: ModelRequest) =
        when (request.purpose) {
            ModelPurpose.CHAT -> chat
            ModelPurpose.VOICE -> voice
        }

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        val route = routeOf(request)
        val answer = route.model.complete(request)
        val fallback = route.fallback
        if (answer is Resource.Success || fallback == null) {
            if (answer is Resource.Success) answered(request, route.name, answer.data, isFallback = false)
            return answer
        }
        fellBack(request, route, (answer as Resource.Error).error)
        return fallback.complete(request).also {
            if (it is Resource.Success) answered(request, route.fallbackName, it.data, isFallback = true)
        }
    }

    override suspend fun stream(request: ModelRequest): Resource<ModelStream> {
        val route = routeOf(request)
        val fallback = route.fallback
        return when (val opened = route.model.stream(request)) {
            is Resource.Success -> {
                Resource.success(FallbackStream(request, route, opened.data))
            }

            is Resource.Error -> {
                if (fallback == null) return opened
                fellBack(request, route, opened.error)
                when (val second = fallback.stream(request)) {
                    is Resource.Success -> {
                        Resource.success(
                            FallbackStream(request, route, second.data, isFallback = true),
                        )
                    }

                    is Resource.Error -> {
                        second
                    }
                }
            }
        }
    }

    /** Passes [current] on; if it fails before its first word, carries on with the fallback's answer instead. */
    private inner class FallbackStream(
        private val request: ModelRequest,
        private val route: ModelRoute,
        private var current: ModelStream,
        private var isFallback: Boolean = false,
    ) : ModelStream {
        private var hasStarted = false

        override suspend fun next(): ModelStreamEvent? {
            while (true) {
                val event = current.next()
                when (event) {
                    is ModelStreamEvent.Text -> {
                        if (event.delta.isNotEmpty()) hasStarted = true
                        return event
                    }

                    is ModelStreamEvent.ToolCallDelta -> {
                        hasStarted = true
                        return event
                    }

                    is ModelStreamEvent.Done -> {
                        answered(
                            request,
                            if (isFallback) route.fallbackName else route.name,
                            event.response,
                            isFallback,
                        )
                        return event
                    }

                    is ModelStreamEvent.Failed, null -> {
                        val fallback = route.fallback
                        if (hasStarted || isFallback || fallback == null) return event
                        fellBack(request, route, (event as? ModelStreamEvent.Failed)?.error)
                        current.close()
                        isFallback = true
                        current =
                            when (val second = fallback.stream(request)) {
                                is Resource.Success -> {
                                    second.data
                                }

                                is Resource.Error -> {
                                    return ModelStreamEvent.Failed(
                                        second.error as? IntegrationError ?: IntegrationError.Unavailable("model"),
                                    )
                                }
                            }
                    }
                }
            }
        }

        override suspend fun close() = current.close()
    }

    private fun fellBack(
        request: ModelRequest,
        route: ModelRoute,
        error: DomainError?,
    ) {
        log.warn(
            "asha model fallback: purpose={} from={} to={} reason={}",
            request.purpose,
            route.name,
            route.fallbackName,
            error?.code ?: "ended_without_answer",
        )
    }

    private fun answered(
        request: ModelRequest,
        name: String?,
        response: ModelResponse,
        isFallback: Boolean,
    ) {
        log.info(
            "asha model answered: purpose={} model={} fallback={} toolCalls={}",
            request.purpose,
            response.model ?: name,
            isFallback,
            response.toolCalls.size,
        )
    }
}
