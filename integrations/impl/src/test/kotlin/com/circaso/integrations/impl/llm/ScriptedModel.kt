package com.circaso.integrations.impl.llm

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ModelUsage
import com.circaso.shared.Resource

/** A model that answers each call with the next scripted outcome: events, or a refusal to open. */
internal class ScriptedModel(val name: String, vararg outcomes: Resource<List<ModelStreamEvent>>) : LanguageModel {
    private val script = ArrayDeque(outcomes.toList())
    val requests = mutableListOf<ModelRequest>()
    var closed = 0
    var probes = 0

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> =
        when (val opened = stream(request)) {
            is Resource.Error -> {
                opened
            }

            is Resource.Success -> {
                when (val last = opened.data.drain().last()) {
                    is ModelStreamEvent.Done -> Resource.success(last.response)
                    is ModelStreamEvent.Failed -> Resource.error(last.error)
                    else -> Resource.error(IntegrationError.Unavailable(name))
                }
            }
        }

    override suspend fun stream(request: ModelRequest): Resource<ModelStream> {
        requests += request
        return when (val next = script.removeFirst()) {
            is Resource.Error -> {
                next
            }

            is Resource.Success -> {
                val events = ArrayDeque(next.data)
                Resource.success(
                    object : ModelStream {
                        override suspend fun next() = events.removeFirstOrNull()

                        override suspend fun close() {
                            closed++
                        }
                    },
                )
            }
        }
    }

    override suspend fun probe(): Resource<Unit> {
        probes++
        return Resource.success(Unit)
    }
}

internal suspend fun ModelStream.drain(): List<ModelStreamEvent> {
    val all = mutableListOf<ModelStreamEvent>()
    while (true) all += next() ?: return all
}

internal fun done(text: String) = ModelStreamEvent.Done(ModelResponse(text, emptyList(), ModelUsage(10, 2)))

internal fun answer(vararg pieces: String): Resource<List<ModelStreamEvent>> =
    Resource.success(pieces.map { ModelStreamEvent.Text(it) } + done(pieces.joinToString("")))

internal fun failing(error: IntegrationError = IntegrationError.Unavailable("Anthropic")) =
    Resource.success(listOf<ModelStreamEvent>(ModelStreamEvent.Failed(error)))
