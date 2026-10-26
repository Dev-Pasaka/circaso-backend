package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.LiveVoiceStart
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.secondsUntilReset
import com.circaso.assistant.domain.support.today
import com.circaso.identity.api.TokenVerifier
import com.circaso.integrations.api.AudioEncoding
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LiveSpeech
import com.circaso.integrations.api.LiveTranscription
import com.circaso.integrations.api.LiveTranscriptionOptions
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import java.time.Clock

/**
 * A11, opening: the live voice socket's first frame. The user token is checked like on the main socket (the agent
 * token is refused: Asha can't open a conversation with herself), then the voice allowance, then the language: the
 * live path only exists where both the live transcription and the live voice speak the user's language, so Swahili is
 * refused and the phone records clips for A3 and speaks the reply itself (Assistant → Live voice, step 5). Only then
 * are the provider sessions opened.
 */
class OpenLiveVoice(
    private val verifier: TokenVerifier,
    private val transcription: LiveTranscription,
    private val speech: LiveSpeech,
    private val entitlements: EntitlementRepository,
    private val quota: QuotaCounters,
    private val config: AssistantConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(start: LiveVoiceStart): Resource<LiveVoiceLine> {
        val principal =
            when (val verified = verifier.verify(start.accessToken)) {
                is Resource.Success -> verified.data
                is Resource.Error -> return verified
            }
        if (principal.scope != Scope.APP) return Resource.error(SharedError.ScopeInsufficient)

        val userId = principal.userId
        val limit = config.limitsOf(entitlements.planOf(userId)).voiceSecondsPerDay
        val left = limit - quota.voiceSeconds(userId, clock.today())
        if (left <= 0) return Resource.error(AssistantError.QuotaExceeded(clock.secondsUntilReset()))

        val listening =
            when (
                val opened =
                    transcription.open(LiveTranscriptionOptions(start.language, AudioEncoding.OPUS, OPUS_RATE))
            ) {
                is Resource.Success -> opened.data
                is Resource.Error -> return Resource.error(refusal(opened.error), opened.cause)
            }
        val speaking =
            when (val opened = speech.open(start.language)) {
                is Resource.Success -> {
                    opened.data
                }

                is Resource.Error -> {
                    listening.close()
                    return Resource.error(refusal(opened.error), opened.cause)
                }
            }
        return Resource.success(
            LiveVoiceLine(userId, start, listening, speaking, left, limit, config.liveSilence),
        )
    }

    /** A language the providers can't do live is the phone's cue to use clips; anything else is an outage. */
    private fun refusal(error: DomainError): DomainError =
        if (error is IntegrationError.UnsupportedLanguage) {
            AssistantError.LanguageNotSupported
        } else {
            AssistantError.ModelUnavailable
        }

    private companion object {
        /** The phone records Opus at 16 kHz mono, about 24 kbit/s (Assistant → Live voice). */
        const val OPUS_RATE = 16_000
    }
}
