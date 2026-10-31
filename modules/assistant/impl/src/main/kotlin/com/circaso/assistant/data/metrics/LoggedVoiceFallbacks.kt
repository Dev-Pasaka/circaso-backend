package com.circaso.assistant.data.metrics

import com.circaso.assistant.domain.models.AshaVoice
import com.circaso.assistant.domain.ports.VoiceFallbacks
import org.slf4j.LoggerFactory

/**
 * A debug line when a request named no voice, or one not on Asha's list, and got the default. The asked-for value is
 * the phone's own text, so it is cut short and stripped of anything but letters, digits and dashes.
 */
class LoggedVoiceFallbacks : VoiceFallbacks {
    private val log = LoggerFactory.getLogger(LoggedVoiceFallbacks::class.java)

    override fun fellBack(requested: String?, used: AshaVoice) {
        if (!log.isDebugEnabled) return
        val asked = requested?.filter { it.isLetterOrDigit() || it == '-' }?.take(MAX_LOGGED)
        log.debug("asha voice fell back to the default: requested={} used={}", asked, used.id)
    }

    private companion object {
        const val MAX_LOGGED = 32
    }
}
