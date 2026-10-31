package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.ports.VoiceSamples
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.TextToSpeech
import com.circaso.shared.Resource

/**
 * A13: a voice's preview, the same short line in each voice, so the user can hear one before choosing it (decision
 * 2026-10-31). Not counted against the voice allowance: the text is fixed and the clip is kept a day ([VoiceSamples]),
 * so trying every voice costs a handful of provider calls a day in all, not per user.
 */
class GetVoiceSample(
    private val speech: TextToSpeech,
    private val samples: VoiceSamples,
    private val config: AssistantConfig,
) {
    suspend operator fun invoke(voiceId: String): Resource<SpokenAudio> {
        val voice = config.voices.find(voiceId) ?: return Resource.error(AssistantError.VoiceNotFound)
        val text = config.voiceSampleText
        samples.find(voice.id, text)?.let { return Resource.success(it) }

        return when (val spoken = speech.speak(text, SAMPLE_LANGUAGE, voice.id)) {
            is Resource.Success -> {
                samples.save(voice.id, text, spoken.data)
                spoken
            }

            is Resource.Error -> {
                Resource.error(AssistantError.ModelUnavailable, spoken.cause)
            }
        }
    }

    private companion object {
        const val SAMPLE_LANGUAGE = "en"
    }
}
