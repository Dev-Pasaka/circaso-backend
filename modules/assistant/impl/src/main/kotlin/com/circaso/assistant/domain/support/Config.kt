package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.Limits
import com.circaso.assistant.domain.models.Plan
import java.time.Duration

/**
 * Asha's settings. The numbers are placeholders until measured cost per turn is known.
 *
 * TODO(decision: Asha's free allowance and the Asha Plus price) daily text turns, voice seconds and context length.
 */
data class AssistantConfig(
    val free: Limits = Limits(textTurnsPerDay = 20, voiceSecondsPerDay = 120, contextMessages = 6),
    val plus: Limits = Limits(textTurnsPerDay = 300, voiceSecondsPerDay = 1_800, contextMessages = 60),
    val maxToolSteps: Int = 6,
    val maxVoiceSeconds: Int = 60,
    val maxMessageChars: Int = 4_000,
    val maxSnippets: Int = 30,
    val maxSnippetChars: Int = 500,
    val maxSpeechChars: Int = 1_000,
    val promptVersion: String = AshaPrompt.VERSION,
    /** A live voice socket closes after this long with nobody speaking (Assistant → Live voice). */
    val liveSilence: Duration = Duration.ofSeconds(30),
) {
    fun limitsOf(plan: Plan) = if (plan == Plan.PLUS) plus else free
}
