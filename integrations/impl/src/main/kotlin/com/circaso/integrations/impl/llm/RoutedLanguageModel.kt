package com.circaso.integrations.impl.llm

import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.shared.Resource

/** The model for one purpose, and its name for the logs. */
class ModelRoute(val model: LanguageModel, val name: String)

/**
 * Asha's text model per [ModelPurpose]: a typed turn goes to [chat], a spoken one to [voice]. There is no fallback model
 * (decision 2026-11-07, "Claude only"): a turn Claude can't answer, after its one quick retry ([RetryingLanguageModel]),
 * fails, and the assistant tells the person Asha is down for maintenance.
 */
class RoutedLanguageModel(private val chat: ModelRoute, private val voice: ModelRoute) : LanguageModel {
    private fun routeOf(request: ModelRequest) =
        when (request.purpose) {
            ModelPurpose.CHAT -> chat
            ModelPurpose.VOICE -> voice
        }

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> =
        routeOf(request).model.complete(request)

    override suspend fun stream(request: ModelRequest): Resource<ModelStream> = routeOf(request).model.stream(request)

    /** Both models answer, or the first that doesn't is the probe's failure. */
    override suspend fun probe(): Resource<Unit> {
        val first = chat.model.probe()
        if (first is Resource.Error || voice.model === chat.model) return first
        return voice.model.probe()
    }
}
