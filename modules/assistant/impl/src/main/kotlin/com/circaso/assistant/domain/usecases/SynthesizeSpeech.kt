package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.today
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.TextToSpeech
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.time.Clock

/**
 * A4: Asha's reply as audio. Metered with voice, at about 15 characters a second.
 *
 * TODO(decision: short replies could be cached by text hash for a day, as the design suggests)
 */
class SynthesizeSpeech(
    private val speech: TextToSpeech,
    private val entitlements: EntitlementRepository,
    private val quota: QuotaCounters,
    private val config: AssistantConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        userId: UserId,
        text: String,
        language: String?,
    ): Resource<SpokenAudio> {
        val spoken = text.trim()
        if (spoken.isEmpty() || spoken.length > config.maxSpeechChars) {
            return Resource.error(
                SharedError.Malformed(
                    mapOf("text" to "must be 1 to ${config.maxSpeechChars} characters"),
                ),
            )
        }
        val seconds = (spoken.length + CHARS_PER_SECOND - 1) / CHARS_PER_SECOND
        val day = clock.today()
        val limit = config.limitsOf(entitlements.planOf(userId)).voiceSecondsPerDay
        if (quota.voiceSeconds(userId, day) + seconds >
            limit
        ) {
            return Resource.error(AssistantError.QuotaExceeded(RESET_HINT_SECONDS))
        }

        return when (val r = speech.speak(spoken, language)) {
            is Resource.Success -> {
                quota.addVoiceSeconds(userId, day, seconds)
                r
            }

            // Nothing is metered on a failure; a language the voice can't speak is the phone's job, not an outage.
            is Resource.Error -> {
                val error =
                    if (r.error is IntegrationError.UnsupportedLanguage) {
                        AssistantError.LanguageNotSupported
                    } else {
                        AssistantError.ModelUnavailable
                    }
                Resource.error(error, r.cause)
            }
        }
    }

    private companion object {
        const val CHARS_PER_SECOND = 15
        const val RESET_HINT_SECONDS = 3_600L
    }
}
