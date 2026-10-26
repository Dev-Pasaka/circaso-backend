package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.AudioEncoding
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        private fun event(text: String): TranscriptionEvent? {
            val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            return when (message.string("type")) {
                "Results" -> result(message)
                "SpeechStarted" -> TranscriptionEvent.SpeechStarted
                "UtteranceEnd" -> TranscriptionEvent.TurnEnded
                else -> null // Metadata, and anything Deepgram adds later
            }
        }

        private fun result(message: JsonObject): TranscriptionEvent? {
            val transcript =
                runCatching {
                    message["channel"]!!
                        .jsonObject["alternatives"]!!
                        .jsonArray
                        .first()
                        .jsonObject
                        .string("transcript")
                }.getOrNull().orEmpty().trim()
            val isFinal = message.flag("is_final")
            val speechFinal = message.flag("speech_final")
            return when {
                isFinal || speechFinal -> TranscriptionEvent.Final(transcript, endOfTurn = speechFinal)
                transcript.isNotEmpty() -> TranscriptionEvent.Interim(transcript)
                else -> null
            }
        }

        private fun JsonObject.string(name: String): String? =
            runCatching { get(name)!!.jsonPrimitive.content }.getOrNull()

        private fun JsonObject.flag(name: String): Boolean =
            runCatching { get(name)?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
    }

    companion object {
        const val DEFAULT_ENDPOINTING_MILLIS = 400

        /** Deepgram's smallest allowed value. */
        const val DEFAULT_UTTERANCE_END_MILLIS = 1_000

        private const val KEEP_ALIVE_MILLIS = 5_000L
        private const val KEEP_ALIVE = """{"type":"KeepAlive"}"""
        private const val CLOSE_STREAM = """{"type":"CloseStream"}"""

        /**
         * Languages Nova-3 transcribes live on Workers AI (Cloudflare's Nova-3 changelog of 2026-03-06). Swahili isn't
         * one: the phone records a clip for Whisper instead (Assistant → Live voice).
         */
        val LIVE_LANGUAGES = setOf("en", "es", "fr", "de", "hi", "ru", "pt", "ja", "it", "nl")
    }
}
