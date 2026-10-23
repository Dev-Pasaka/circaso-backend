package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.Limits
import com.circaso.assistant.domain.models.Plan

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
) {
    fun limitsOf(plan: Plan) = if (plan == Plan.PLUS) plus else free
}
