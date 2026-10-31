package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.AudioEncoding
import com.circaso.integrations.api.AudioSpan
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LiveTranscription
import com.circaso.integrations.api.LiveTranscriptionOptions
import com.circaso.integrations.api.LiveTranscriptionSession
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.shared.Resource
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Asha's live ears: Deepgram Nova-3 (`@cf/deepgram/nova-3`) on Workers AI's live socket, fed the phone's Opus frames
 * as they arrive (decision 2026-10-25: Opus is about a tenth of the mobile data of the PCM Flux needs).
 *
 * The turn ends either way Nova-3 reports it: a final result with `speech_final` once the user has paused for
 * [endpointingMillis], or an `UtteranceEnd` message [utteranceEndMillis] after the last word, which still fires when
 * background noise keeps endpointing from seeing a pause. `vad_events` reports the start of speech, which the
 * assistant uses to stop Asha talking over the user. `mip_opt_out=true` keeps the audio out of Deepgram's model
 * improvement programme (decision 2026-10-25). Neither audio nor transcripts are logged.
 */
class WorkersAiLiveTranscription(
    private val socket: WorkersAiLiveSocket,
    private val model: String,
    private val endpointingMillis: Int = DEFAULT_ENDPOINTING_MILLIS,
    private val utteranceEndMillis: Int = DEFAULT_UTTERANCE_END_MILLIS,
) : LiveTranscription {
    override suspend fun open(options: LiveTranscriptionOptions): Resource<LiveTranscriptionSession> {
        val language = options.language?.takeIf { it.isNotBlank() } ?: "en"
        if (language.primary() !in LIVE_LANGUAGES) return Resource.error(IntegrationError.UnsupportedLanguage(language))

        val query =
            mapOf(
                "encoding" to options.encoding.wire(),
                "sample_rate" to options.sampleRate.toString(),
                "channels" to "1",
                "language" to language,
                "interim_results" to "true",
                "endpointing" to endpointingMillis.toString(),
                "utterance_end_ms" to utteranceEndMillis.toString(),
                "vad_events" to "true",
                "smart_format" to "true",
                "punctuate" to "true",
                "mip_opt_out" to "true",
            )
        return when (val opened = socket.open(model, "live_transcribe", query)) {
            is Resource.Success -> Resource.success(NovaSession(opened.data))
            is Resource.Error -> opened
        }
    }

    private fun AudioEncoding.wire() =
        when (this) {
            AudioEncoding.OPUS -> "opus"
            AudioEncoding.LINEAR16 -> "linear16"
            AudioEncoding.MULAW -> "mulaw"
        }

    private fun String.primary() = substringBefore('-').lowercase()

    /** One Nova-3 stream. A `KeepAlive` goes out when the phone has sent nothing for a while, so Deepgram keeps it. */
    private class NovaSession(private val ws: DefaultClientWebSocketSession) : LiveTranscriptionSession {
        private val log = LoggerFactory.getLogger(WorkersAiLiveTranscription::class.java)
        private val closedByUs = AtomicBoolean(false)
        private val failureReported = AtomicBoolean(false)
        private val lastSentAt = AtomicLong(System.currentTimeMillis())

        init {
            ws.launch {
                while (isActive) {
                    delay(KEEP_ALIVE_MILLIS)
                    if (System.currentTimeMillis() - lastSentAt.get() >= KEEP_ALIVE_MILLIS) {
                        runCatching { ws.send(Frame.Text(KEEP_ALIVE)) }
                    }
                }
            }
        }

        override suspend fun send(audio: ByteArray) {
            if (closedByUs.get()) return
            lastSentAt.set(System.currentTimeMillis())
            try {
                ws.send(Frame.Binary(true, audio))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("live transcription frame not sent: error={}", e::class.simpleName)
            }
        }

        override suspend fun next(): TranscriptionEvent? {
            while (true) {
                val frame = ws.incoming.receiveCatching().getOrNull() ?: return ended()
                if (frame !is Frame.Text) continue
                event(frame.readText())?.let { return it }
            }
        }

        override suspend fun close() {
            if (!closedByUs.compareAndSet(false, true)) return
            runCatching {
                ws.send(Frame.Text(CLOSE_STREAM))
                ws.close()
            }
        }

        /** The vendor closing a socket we didn't close is a failure, reported once. */
        private fun ended(): TranscriptionEvent? {
            if (closedByUs.get() || !failureReported.compareAndSet(false, true)) return null
            log.warn("live transcription ended by the vendor")
            return TranscriptionEvent.Failed(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }

        /** Metadata, and any message Deepgram adds later, doesn't decode as one of ours and is skipped. */
        private fun event(text: String): TranscriptionEvent? =
            when (
                val message =
                    runCatching {
                        WorkersAiJson.decodeFromString(
                            NovaMessage.serializer(),
                            text,
                        )
                    }.getOrNull()
            ) {
                is NovaMessage.Results -> result(message)
                is NovaMessage.SpeechStarted -> TranscriptionEvent.SpeechStarted
                is NovaMessage.UtteranceEnd -> TranscriptionEvent.TurnEnded
                null -> null
            }

        private fun result(message: NovaMessage.Results): TranscriptionEvent? {
            val best = message.channel?.alternatives?.firstOrNull()
            val transcript = best?.transcript.orEmpty().trim()
            val audio = if (transcript.isEmpty()) null else wordsSpan(best?.words.orEmpty()) ?: windowSpan(message)
            return when {
                message.isFinal || message.speechFinal -> {
                    TranscriptionEvent.Final(transcript, endOfTurn = message.speechFinal, audio = audio)
                }

                transcript.isNotEmpty() -> {
                    TranscriptionEvent.Interim(transcript, audio)
                }

                else -> {
                    null
                }
            }
        }

        /** The first word's `start` to the last word's `end`, in seconds from the start of the stream. */
        private fun wordsSpan(words: List<NovaWord>): AudioSpan? {
            if (words.isEmpty()) return null
            return AudioSpan(words.first().start, words.last().end)
        }

        /** Without words, the result's own window (`start`, `duration`), which may hold some silence. */
        private fun windowSpan(message: NovaMessage.Results): AudioSpan? {
            val start = message.start ?: return null
            val duration = message.duration ?: return null
            return AudioSpan(start, start + duration)
        }
    }

    companion object {
        /**
         * The pause that ends a turn (decision 2026-10-31): 600 ms. At 300 ms (2026-10-30) a natural pause mid-sentence
         * ended the turn, and carrying on counted as talking over Asha, so the first half of the question was dropped.
         */
        const val DEFAULT_ENDPOINTING_MILLIS = 600

        /** Deepgram's smallest allowed value, so it stays at 1000 ms while endpointing gets shorter. */
        const val DEFAULT_UTTERANCE_END_MILLIS = 1_000

        private const val KEEP_ALIVE_MILLIS = 5_000L
        private val KEEP_ALIVE = WorkersAiJson.encodeToString(NovaCommand.serializer(), NovaCommand.KeepAlive)
        private val CLOSE_STREAM = WorkersAiJson.encodeToString(NovaCommand.serializer(), NovaCommand.CloseStream)

        /**
         * Languages Nova-3 transcribes live on Workers AI (Cloudflare's Nova-3 changelog of 2026-03-06). Swahili isn't
         * one: the phone records a clip for Whisper instead (Assistant → Live voice).
         */
        val LIVE_LANGUAGES = setOf("en", "es", "fr", "de", "hi", "ru", "pt", "ja", "it", "nl")
    }
}

/** What Nova-3 sends, told apart by `type`; the ones we don't act on (`Metadata`) aren't listed. */
@Serializable
private sealed interface NovaMessage {
    @Serializable
    @SerialName("Results")
    class Results(
        val channel: NovaChannel? = null,
        @SerialName("is_final") val isFinal: Boolean = false,
        @SerialName("speech_final") val speechFinal: Boolean = false,
        val start: Double? = null,
        val duration: Double? = null,
    ) : NovaMessage

    @Serializable
    @SerialName("SpeechStarted")
    data object SpeechStarted : NovaMessage

    @Serializable
    @SerialName("UtteranceEnd")
    data object UtteranceEnd : NovaMessage
}

@Serializable
private class NovaChannel(val alternatives: List<NovaAlternative> = emptyList())

@Serializable
private class NovaAlternative(val transcript: String = "", val words: List<NovaWord> = emptyList())

@Serializable
private class NovaWord(val start: Double, val end: Double)

/** What we send Nova-3 besides audio. */
@Serializable
private sealed interface NovaCommand {
    /** Keeps the stream open while the phone sends nothing. */
    @Serializable
    @SerialName("KeepAlive")
    data object KeepAlive : NovaCommand

    @Serializable
    @SerialName("CloseStream")
    data object CloseStream : NovaCommand
}
