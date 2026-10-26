package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.AudioEncoding
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LiveAudioFormat
import com.circaso.integrations.api.LiveSpeech
import com.circaso.integrations.api.LiveSpeechSession
import com.circaso.integrations.api.SpeechEvent
import com.circaso.shared.Resource
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asha's live voice: Deepgram Aura 2 (`@cf/deepgram/aura-2-en`) on Workers AI's live socket. The assistant sends the
 * reply a sentence at a time (`Speak`, then `Flush`), and audio comes back as binary frames; `Clear` drops what hasn't
 * been sent when the user talks over Asha.
 *
 * The assistant flushes once per reply, not per sentence, to stay under Deepgram's limit on `Flush` messages (its docs
 * give 20 per 60 s; the smoke test confirms the limit Workers AI applies); a flush over it is answered with a `Warning`.
 *
 * Aura's live socket only makes uncompressed audio (Deepgram's streaming encodings are `linear16`, `mulaw` and
 * `alaw`). The phone receives mulaw at 8 kHz by default, 64 kbit/s and clear enough for speech (decision 2026-10-26);
 * [encoding] and [sampleRate] are settings, so it can be raised to 16-bit PCM. Aura speaks English only: another language is refused
 * before any vendor call, and the phone speaks it with its own voice (decision 2026-10-25). `mip_opt_out=true` is
 * always sent. Neither the text nor the audio is logged.
 */
class WorkersAiLiveSpeech(
    private val socket: WorkersAiLiveSocket,
    private val model: String,
    private val encoding: AudioEncoding = DEFAULT_ENCODING,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : LiveSpeech {
    init {
        require(encoding != AudioEncoding.OPUS) { "Aura's live socket makes linear16 or mulaw, not Opus" }
    }

    override suspend fun open(language: String?): Resource<LiveSpeechSession> {
        if (language != null && !language.lowercase().startsWith("en")) {
            return Resource.error(IntegrationError.UnsupportedLanguage(language))
        }
        val query =
            mapOf(
                "encoding" to encoding.name.lowercase(),
                "sample_rate" to sampleRate.toString(),
                "mip_opt_out" to "true",
            )
        return when (val opened = socket.open(model, "live_speak", query)) {
            is Resource.Success -> {
                Resource.success(AuraSession(opened.data, LiveAudioFormat(encoding, sampleRate)))
            }

            is Resource.Error -> {
                opened
            }
        }
    }

    private class AuraSession(private val ws: DefaultClientWebSocketSession, override val format: LiveAudioFormat) :
        LiveSpeechSession {
        private val log = LoggerFactory.getLogger(WorkersAiLiveSpeech::class.java)
        private val closedByUs = AtomicBoolean(false)
        private val failureReported = AtomicBoolean(false)

        override suspend fun speak(text: String) =
            command(
                buildJsonObject {
                    put("type", "Speak")
                    put("text", text)
                }.toString(),
            )

        override suspend fun flush() = command(FLUSH)

        override suspend fun clear() = command(CLEAR)

        override suspend fun next(): SpeechEvent? {
            while (true) {
                val frame = ws.incoming.receiveCatching().getOrNull() ?: return ended()
                when (frame) {
                    is Frame.Binary -> return SpeechEvent.Audio(frame.readBytes())
                    is Frame.Text -> control(frame.readText())?.let { return it }
                    else -> continue
                }
            }
        }

        override suspend fun close() {
            if (!closedByUs.compareAndSet(false, true)) return
            runCatching {
                ws.send(Frame.Text(CLOSE))
                ws.close()
            }
        }

        private suspend fun command(json: String) {
            if (closedByUs.get()) return
            try {
                ws.send(Frame.Text(json))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("live speech command not sent: error={}", e::class.simpleName)
            }
        }

        /** `Flushed` and `Cleared` matter to the assistant; `Metadata` and `Warning` are dropped. */
        private fun control(text: String): SpeechEvent? {
            val type =
                runCatching {
                    Json
                        .parseToJsonElement(text)
                        .jsonObject["type"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()
            return when (type) {
                "Flushed" -> {
                    SpeechEvent.Flushed
                }

                "Cleared" -> {
                    SpeechEvent.Cleared
                }

                "Warning" -> {
                    log.warn("live speech warning from the vendor")
                    SpeechEvent.Warning
                }

                else -> {
                    null
                }
            }
        }

        private fun ended(): SpeechEvent? {
            if (closedByUs.get() || !failureReported.compareAndSet(false, true)) return null
            log.warn("live speech ended by the vendor")
            return SpeechEvent.Failed(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
    }

    companion object {
        /** mulaw at 8 kHz: 64 kbit/s, a quarter of 16 kHz PCM, on a phone's data bundle (decision 2026-10-26). */
        val DEFAULT_ENCODING = AudioEncoding.MULAW
        const val DEFAULT_SAMPLE_RATE = 8_000

        private const val FLUSH = """{"type":"Flush"}"""
        private const val CLEAR = """{"type":"Clear"}"""
        private const val CLOSE = """{"type":"Close"}"""
    }
}
