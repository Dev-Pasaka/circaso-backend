package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.LiveVoiceStart
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.isOwnAgentToken
import com.circaso.assistant.domain.support.secondsUntilReset
import com.circaso.assistant.domain.support.today
import com.circaso.assistant.domain.support.verifiedUser
import com.circaso.identity.api.TokenVerifier
import com.circaso.integrations.api.AudioEncoding
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LiveSpeech
import com.circaso.integrations.api.LiveTranscription
import com.circaso.integrations.api.LiveTranscriptionOptions
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.time.Clock

/**
 * A11, opening: the live voice socket's first frame. The user token is checked like on the main socket; any failure
 * there (a bad, expired, revoked or agent token, or the check itself failing) is one outcome, `TokenInvalid`, which
 * closes the socket as `unauthorized`. Then the voice allowance, then the language: the
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
        val userId = verifiedUser(verifier, start.accessToken) ?: return Resource.error(SharedError.TokenInvalid)
        if (!isOwnAgentToken(verifier, userId, start.agentToken)) return Resource.error(SharedError.TokenInvalid)
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
            LiveVoiceLine(
                userId,
                start,
                listening,
                speaking,
                left,
                limit,
                config.liveSilence,
                config.liveSessionRecheck,
                config.liveDeviceToolTimeout,
            ),
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
