package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.support.AshaVoices
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.shared.Resource

/** A12: the voices a user can choose for Asha, and which one is used when they haven't chosen. */
class ListVoices(private val config: AssistantConfig) {
    suspend operator fun invoke(): Resource<AshaVoices> = Resource.success(config.voices)
}
