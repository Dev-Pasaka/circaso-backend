package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.Limits
import com.circaso.assistant.domain.models.Plan
import java.time.Duration

/**
 * Asha's settings, read from the environment in `di` (`ASSISTANT_*`, see `.env.example`). The numbers are
 * placeholders until measured cost per turn is known.
 *
 * [isUnlimited] (`ASSISTANT_LIMITS_UNLIMITED=true`, for testing; decision 2026-10-31) lifts the daily text turns, the
 * voice seconds and the context length for everyone, whatever their plan. Off by default.
 *
 * TODO(decision: Asha's free allowance and the Asha Plus price) daily text turns, voice seconds and context length.
 */
data class AssistantConfig(
    val free: Limits =
        Limits(textTurnsPerDay = 20, voiceSecondsPerDay = 120, contextMessages = 6, webSearchesPerDay = 10),
    val plus: Limits =
        Limits(textTurnsPerDay = 300, voiceSecondsPerDay = 1_800, contextMessages = 60, webSearchesPerDay = 60),
    val isUnlimited: Boolean = false,
    /** Model calls in one turn; an agent that chains lookups (contact, then payments, then a chama) needs a few. */
    val maxToolSteps: Int = 10,
    /** Web searches in one turn (decision 2026-11-05): two is a search and a follow-up; more is the model looping. */
    val maxWebSearchesPerTurn: Int = 2,
    val maxVoiceSeconds: Int = 60,
    val maxMessageChars: Int = 4_000,
    val maxSnippets: Int = 30,
    val maxSnippetChars: Int = 500,
    val maxSpeechChars: Int = 1_000,
    val promptVersion: String = AshaPrompt.VERSION,
    /** A live voice socket closes after this long with nobody speaking (Assistant → Live voice). */
    val liveSilence: Duration = Duration.ofSeconds(30),
    /** A live voice socket re-checks the session this often, and at each turn (decision 2026-10-26). */
    val liveSessionRecheck: Duration = Duration.ofSeconds(60),
    /** A device tool asked on the live socket ends its turn with an error if the phone hasn't answered by then. */
    val liveDeviceToolTimeout: Duration = Duration.ofSeconds(30),
    /** The voices a user can choose and the default (decision 2026-10-31); `ASSISTANT_VOICES` replaces the list. */
    val voices: AshaVoices = AshaVoices.DEFAULT,
    /** What each voice says in its preview (A13). */
    val voiceSampleText: String = AshaVoices.SAMPLE_TEXT,
) {
    fun limitsOf(plan: Plan): Limits =
        when {
            isUnlimited -> UNLIMITED
            plan == Plan.PLUS -> plus
            else -> free
        }

    companion object {
        /**
         * "No limit" as numbers the counters can hold: a million turns or voice seconds a day is never reached, and
         * the context is the last 200 messages, as long as the models' context windows comfortably take.
         */
        val UNLIMITED =
            Limits(
                textTurnsPerDay = 1_000_000,
                voiceSecondsPerDay = 1_000_000,
                contextMessages = 200,
                webSearchesPerDay = 1_000_000,
            )
    }
}
