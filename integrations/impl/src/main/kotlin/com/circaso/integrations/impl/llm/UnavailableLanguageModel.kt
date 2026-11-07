package com.circaso.integrations.impl.llm

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.shared.Resource

/**
 * A model that never answers: `anthropic` without `ANTHROPIC_API_KEY` outside a local server. Every turn, and every
 * probe, is unavailable and not worth a retry, so Asha shows as down for maintenance until the key is set.
 */
class UnavailableLanguageModel(private val vendor: String) : LanguageModel {
    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> =
        Resource.error(IntegrationError.Unavailable(vendor, isTransient = false))

    override suspend fun probe(): Resource<Unit> =
        Resource.error(IntegrationError.Unavailable(vendor, isTransient = false))
}
