package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.VoiceClip
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.today
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.Transcript
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.time.Clock

/** A3: a voice clip (up to 60 s) becomes text. The transcript becomes the turn; the audio is not kept. */
class TranscribeVoice(
    private val speech: SpeechToText,
    private val entitlements: EntitlementRepository,
    private val quota: QuotaCounters,
    private val config: AssistantConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId, clip: VoiceClip): Resource<Transcript> {
        if (clip.audio.isEmpty()) return Resource.error(SharedError.Malformed(mapOf("audio" to "is empty")))
        if (clip.claimedSeconds > config.maxVoiceSeconds) return Resource.error(AssistantError.VoiceTooLong)

        val day = clock.today()
        val limit = config.limitsOf(entitlements.planOf(userId)).voiceSecondsPerDay
        if (quota.voiceSeconds(userId, day) + clip.claimedSeconds >
            limit
        ) {
            return Resource.error(AssistantError.QuotaExceeded(RESET_HINT_SECONDS))
        }

        val transcript =
            when (val r = speech.transcribe(clip.audio, clip.contentType, clip.language)) {
                is Resource.Success -> r.data
                is Resource.Error -> return Resource.error(AssistantError.ModelUnavailable, r.cause)
            }
        if (transcript.seconds > config.maxVoiceSeconds) return Resource.error(AssistantError.VoiceTooLong)
        // the clip's real length may be longer than claimed: charged up to the cap, never past it
        quota.addVoiceSecondsUpTo(userId, day, transcript.seconds, limit)
        return Resource.success(transcript)
    }

    private companion object {
        const val RESET_HINT_SECONDS = 3_600L
    }
}
