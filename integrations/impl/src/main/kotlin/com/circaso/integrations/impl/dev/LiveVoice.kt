package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.AudioEncoding
import com.circaso.integrations.api.AudioSpan
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LiveAudioFormat
import com.circaso.integrations.api.LiveSpeech
import com.circaso.integrations.api.LiveSpeechSession
import com.circaso.integrations.api.LiveTranscription
import com.circaso.integrations.api.LiveTranscriptionOptions
import com.circaso.integrations.api.LiveTranscriptionSession
import com.circaso.integrations.api.SpeechEvent
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.shared.Resource
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local runs, the dev server and tests: a frame of "audio" that is readable UTF-8 text is what the user said, and makes
 * one whole turn (speech started, the words, end of turn). Anything else, real Opus included, counts as silence, so
 * a phone can be tried against a local server without a provider. Like Aura it works in English only, so the
 * Swahili path (the live session refused, then clips) can be tried too. Tests can also push any event themselves
 * ([FakeLiveTranscriptionSession.push]).
 */
class FakeLiveTranscription : LiveTranscription {
    val sessions = CopyOnWriteArrayList<FakeLiveTranscriptionSession>()

    /** Set to make the next opens fail as if the provider were down. */
    @Volatile var unavailable = false

    override suspend fun open(options: LiveTranscriptionOptions): Resource<LiveTranscriptionSession> {
        if (!isEnglish(
                options.language,
            )
        ) {
            return Resource.error(IntegrationError.UnsupportedLanguage(options.language!!))
        }
        if (unavailable) return Resource.error(IntegrationError.Unavailable("live transcription"))
        return Resource.success(FakeLiveTranscriptionSession().also { sessions += it })
    }
}

class FakeLiveTranscriptionSession : LiveTranscriptionSession {
    private val events = Channel<TranscriptionEvent>(Channel.UNLIMITED)
    val framesReceived = AtomicInteger()

    /** Where the stream is, in seconds: each spoken word takes [SECONDS_PER_WORD] of it, as if said at a steady pace. */
    @Volatile private var streamSeconds = 0.0

    @Volatile var isClosed = false
        private set

    /** Hands [event] to the reader, as if the provider had sent it. */
    fun push(event: TranscriptionEvent) {
        events.trySend(event)
    }

    override suspend fun send(audio: ByteArray) {
        if (isClosed) return
        framesReceived.incrementAndGet()
        val words = readableText(audio) ?: return
        val start = streamSeconds
        streamSeconds += words.split(Regex("\\s+")).size * SECONDS_PER_WORD
        val span = AudioSpan(start, streamSeconds)
        push(TranscriptionEvent.SpeechStarted)
        push(TranscriptionEvent.Interim(words, span))
        push(TranscriptionEvent.Final(words, endOfTurn = true, audio = span))
    }

    override suspend fun next(): TranscriptionEvent? = events.receiveCatching().getOrNull()

    override suspend fun close() {
        isClosed = true
        events.close()
    }

    private companion object {
        const val SECONDS_PER_WORD = 0.4
    }

    /** Text the fake treats as speech: valid UTF-8 of printable characters, not binary audio. */
    private fun readableText(audio: ByteArray): String? {
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(audio))
                    .toString()
            } catch (e: CharacterCodingException) {
                return null
            }
        val printable = text.isNotBlank() && text.none { it.isISOControl() && it != '\n' }
        return text.trim().takeIf { printable }
    }
}

/**
 * Local runs, the dev server and tests: the spoken audio of a sentence is its text's bytes, sent as one frame per
 * sentence on `flush`. English only, like Aura. Its format is Aura's default, mulaw at 8 kHz, so each byte counts
 * 1/8000 of a second when metered.
 */
class FakeLiveSpeech(private val sampleRate: Int = 8_000) : LiveSpeech {
    val sessions = CopyOnWriteArrayList<FakeLiveSpeechSession>()

    override suspend fun open(language: String?): Resource<LiveSpeechSession> {
        if (!isEnglish(language)) return Resource.error(IntegrationError.UnsupportedLanguage(language!!))
        return Resource.success(
            FakeLiveSpeechSession(LiveAudioFormat(AudioEncoding.MULAW, sampleRate)).also { sessions += it },
        )
    }
}

class FakeLiveSpeechSession(override val format: LiveAudioFormat) : LiveSpeechSession {
    private val events = Channel<SpeechEvent>(Channel.UNLIMITED)
    private val queued = StringBuilder()

    /** Every sentence queued, and how many times the speech was cleared (barge-in). */
    val spoken = CopyOnWriteArrayList<String>()
    val clears = AtomicInteger()

    /** When true, `flush` holds its audio back until [release], so a test can talk over Asha mid-sentence. */
    @Volatile var holdAudio = false
    private val held = CopyOnWriteArrayList<String>()

    @Volatile var isClosed = false
        private set

    override suspend fun speak(text: String) {
        spoken += text
        synchronized(queued) { queued.append(text) }
    }

    override suspend fun flush() {
        val text = synchronized(queued) { queued.toString().also { queued.clear() } }
        if (holdAudio) {
            held += text
            return
        }
        emit(text)
    }

    /** Sends what [flush] held back. */
    fun release() {
        held.toList().forEach { emit(it) }
        held.clear()
    }

    override suspend fun clear() {
        clears.incrementAndGet()
        synchronized(queued) { queued.clear() }
        held.clear()
        events.trySend(SpeechEvent.Cleared)
    }

    override suspend fun next(): SpeechEvent? = events.receiveCatching().getOrNull()

    override suspend fun close() {
        isClosed = true
        events.close()
    }

    private fun emit(text: String) {
        if (text.isNotEmpty()) events.trySend(SpeechEvent.Audio(text.toByteArray()))
        events.trySend(SpeechEvent.Flushed)
    }
}

private fun isEnglish(language: String?) = language == null || language.lowercase().startsWith("en")
