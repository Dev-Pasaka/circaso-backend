package com.circaso.integrations.api

import com.circaso.shared.Resource

/**
 * Live voice in, in our language: audio frames go in as the user speaks, and words come back while they are still
 * talking, with a signal when they have finished their turn. The assistant decides what a turn means; the vendor only
 * transcribes and detects the pause.
 */
interface LiveTranscription {
    /**
     * Opens a session. A language the provider can't transcribe live fails with [IntegrationError.UnsupportedLanguage]
     * before any vendor call, so the phone can fall back to clips.
     */
    suspend fun open(options: LiveTranscriptionOptions): Resource<LiveTranscriptionSession>
}

/** [language] is a BCP-47 tag (`en`, `en-KE`); null is English. */
data class LiveTranscriptionOptions(
    val language: String?,
    val encoding: AudioEncoding = AudioEncoding.OPUS,
    val sampleRate: Int = 16_000,
)

enum class AudioEncoding { OPUS, LINEAR16, MULAW }

/**
 * One live transcription. Pull-based, so the contract needs nothing beyond the standard library: the caller sends
 * frames from one coroutine and reads [next] from another.
 */
interface LiveTranscriptionSession {
    /** One frame of the user's audio, in the encoding the session was opened with. Never stored. */
    suspend fun send(audio: ByteArray)

    /** The next event, waiting for one; null once the session has ended (closed by us, or by the vendor). */
    suspend fun next(): TranscriptionEvent?

    /** Ends the session; [next] then returns null. Safe to call twice. */
    suspend fun close()
}

sealed interface TranscriptionEvent {
    /** The user started speaking (voice activity); the assistant uses it to stop its own voice at once. */
    data object SpeechStarted : TranscriptionEvent

    /** Words so far for the part being spoken; replaced by the next [Interim] or settled by a [Final]. */
    data class Interim(val text: String) : TranscriptionEvent

    /**
     * Settled words for a stretch of audio. [endOfTurn] is true when the provider also judged the user done
     * (Nova-3's `speech_final` after its `endpointing` pause).
     */
    data class Final(val text: String, val endOfTurn: Boolean) : TranscriptionEvent

    /** The user has been quiet long enough after their last word (Nova-3's `UtteranceEnd`): the turn is over. */
    data object TurnEnded : TranscriptionEvent

    /** The vendor ended the session on its side; [next] returns null after this. */
    data class Failed(val error: IntegrationError) : TranscriptionEvent
}

/**
 * Live voice out, in our language: a reply goes in sentence by sentence and audio comes back as it is made, so the
 * user hears the first sentence while the rest is still being written.
 */
interface LiveSpeech {
    /** A [language] the voice can't speak fails with [IntegrationError.UnsupportedLanguage] before any vendor call. */
    suspend fun open(language: String?): Resource<LiveSpeechSession>
}

/** What the audio frames of a [LiveSpeechSession] hold, so the phone can play them and the assistant can meter them. */
data class LiveAudioFormat(val encoding: AudioEncoding, val sampleRate: Int) {
    /**
     * How long [bytes] of this audio plays, in seconds: 16-bit PCM is two bytes a sample, mulaw one (8 kHz mulaw is
     * 8,000 bytes a second). Opus is compressed, so its length can't be told from its size.
     */
    fun seconds(bytes: Long): Double {
        val bytesPerSample =
            when (encoding) {
                AudioEncoding.LINEAR16 -> 2
                AudioEncoding.MULAW -> 1
                AudioEncoding.OPUS -> error("Opus audio can't be timed by its size")
            }
        return bytes.toDouble() / (sampleRate.toLong() * bytesPerSample)
    }
}

interface LiveSpeechSession {
    val format: LiveAudioFormat

    /** Queues one sentence (or any piece of text) to be spoken. */
    suspend fun speak(text: String)

    /** Speaks everything queued now, rather than waiting for more text; answered by [SpeechEvent.Flushed]. */
    suspend fun flush()

    /**
     * Drops everything queued and not yet sent, when the user talks over Asha (barge-in); answered by
     * [SpeechEvent.Cleared].
     */
    suspend fun clear()

    /** The next event, waiting for one; null once the session has ended. */
    suspend fun next(): SpeechEvent?

    /** Ends the session; [next] then returns null. Safe to call twice. */
    suspend fun close()
}

sealed interface SpeechEvent {
    /** A piece of the spoken reply, in the session's [LiveSpeechSession.format]. Never stored. */
    class Audio(val bytes: ByteArray) : SpeechEvent

    data object Flushed : SpeechEvent

    data object Cleared : SpeechEvent

    /** The vendor ended the session on its side; [LiveSpeechSession.next] returns null after this. */
    data class Failed(val error: IntegrationError) : SpeechEvent
}
