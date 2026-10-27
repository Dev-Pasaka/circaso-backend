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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Asha's live voice: Deepgram Aura 2 (`@cf/deepgram/aura-2-en`) on Workers AI's live socket. The assistant sends the
 * reply a sentence at a time (`Speak`, then `Flush`), and audio comes back as binary frames; `Clear` drops what hasn't
 * been sent when the user talks over Asha.
 *
 * The assistant flushes once per reply, not per sentence, to stay under Deepgram's limit on `Flush` messages (its docs
 * give 20 per 60 s, and the smoke test confirmed Workers AI applies it); a flush over it is answered with a `Warning`.
 *
 * Aura's live socket only makes uncompressed audio (Deepgram's streaming encodings are `linear16`, `mulaw` and
 * `alaw`). The phone receives mulaw at 8 kHz by default, 64 kbit/s and clear enough for speech (decision 2026-10-26);
 * [encoding] and [sampleRate] are settings, so it can be raised to 16-bit PCM. Aura speaks English only: another language is refused
 * before any vendor call, and the phone speaks it with its own voice (decision 2026-10-25). `mip_opt_out=true` is
 * always sent. Neither the text nor the audio is logged.
 *
 * Aura's socket connects to Workers AI directly, without the AI Gateway headers: the gateway refuses it as an
 * unsupported websocket model (decision 2026-10-27). With no gateway in the way there is no gateway log to switch off.
 *
 * Ending: we send `Close` and Aura closes the socket, with `1011` and an empty reason in practice (seen in the smoke
 * test), so whatever code follows our own `Close` is the normal end, never a failure. On `mip_opt_out` for Aura, see
 * [WorkersAiTextToSpeech].
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
        val opener = suspend { socket.open(model, "live_speak", query, throughGateway = false) }
        return when (val opened = opener()) {
            is Resource.Success -> {
                Resource.success(AuraSession(opened.data, opener, LiveAudioFormat(encoding, sampleRate)))
            }

            is Resource.Error -> {
                opened
            }
        }
    }

    /** Where an Aura session's socket stands: open, lost (it couldn't be reopened), or closed by us. */
    private sealed interface Line {
        class Open(val ws: DefaultClientWebSocketSession) : Line

        data object Lost : Line

        data object Closed : Line
    }

    /**
     * One live voice session over one Aura socket at a time.
     *
     * Workers AI closes an Aura socket 5 s after the last message we sent (`1011`), even while Aura is still making a
     * long reply's audio (seen in the smoke test of 2026-10-27: a 25 s reply was cut off at 12.8 s), and the WebSocket
     * ping doesn't count. Deepgram's `KeepAlive` isn't accepted on Aura (the socket closes at once); a `Speak` of one
     * space is, and changes nothing that is heard. So a space goes out after [KEEP_ALIVE_MILLIS] without any other
     * message, which kept a 25 s reply and a 12 s silence alive in the smoke test.
     *
     * Should the socket still close while idle (nothing being spoken), that isn't a failure: the next [speak] opens a
     * new socket and [next] carries on reading from it. Only a close while text is still being spoken, or a socket
     * that can't be reopened, is [SpeechEvent.Failed].
     *
     * TODO(decision: the 5 s idle close and the one-space keep-alive are what Workers AI does today, not documented
     *  behaviour; confirm with Cloudflare, or speak replies as clips if it changes)
     */
    private class AuraSession(
        first: DefaultClientWebSocketSession,
        private val reopen: suspend () -> Resource<DefaultClientWebSocketSession>,
        override val format: LiveAudioFormat,
    ) : LiveSpeechSession {
        private val log = LoggerFactory.getLogger(WorkersAiLiveSpeech::class.java)
        private val line = MutableStateFlow<Line>(Line.Open(first))
        private val reopening = Mutex()
        private val closedByUs = AtomicBoolean(false)
        private val failureReported = AtomicBoolean(false)
        private val lastSentAt = AtomicLong(System.currentTimeMillis())
        private val keeper = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Text sent that Aura hasn't finished with (no `Flushed` or `Cleared` yet): losing the socket now loses it. */
        @Volatile private var isSpeaking = false

        init {
            keeper.launch {
                while (true) {
                    delay(KEEP_ALIVE_CHECK_MILLIS)
                    if (System.currentTimeMillis() - lastSentAt.get() >= KEEP_ALIVE_MILLIS) keepAlive()
                }
            }
        }

        /** A space to the open socket, if there is one; it never reopens a socket. */
        private suspend fun keepAlive() {
            val ws = (line.value as? Line.Open)?.ws?.takeIf { it.isOpen() } ?: return
            lastSentAt.set(System.currentTimeMillis())
            runCatching { ws.send(Frame.Text(AuraCommand.Speak(" ").json())) }
        }

        override suspend fun speak(text: String) {
            isSpeaking = true
            command(AuraCommand.Speak(text))
        }

        override suspend fun flush() = command(AuraCommand.Flush)

        override suspend fun clear() = command(AuraCommand.Clear)

        override suspend fun next(): SpeechEvent? {
            while (true) {
                val ws =
                    when (val now = line.value) {
                        is Line.Open -> now.ws
                        Line.Lost -> return failed(null)
                        Line.Closed -> return null
                    }
                val frame = ws.incoming.receiveCatching().getOrNull()
                if (frame == null) {
                    if (line.value == Line.Closed) return null
                    if (isSpeaking) return failed(ws)
                    log.debug("live speech socket closed while idle; the next sentence reopens it")
                    line.first { it !is Line.Open || it.ws !== ws }
                    continue
                }
                when (frame) {
                    is Frame.Binary -> return SpeechEvent.Audio(frame.readBytes())
                    is Frame.Text -> control(frame.readText())?.let { return it }
                    else -> continue
                }
            }
        }

        /**
         * Asks Aura to end, and lets it close the socket: it answers `Close` by closing with `1011` and no reason,
         * which is its normal end here. A socket Aura hasn't closed within [CLOSE_WAIT_MILLIS] is closed by us.
         */
        override suspend fun close() {
            if (!closedByUs.compareAndSet(false, true)) return
            keeper.cancel()
            val ws = (line.getAndUpdate { Line.Closed } as? Line.Open)?.ws ?: return
            if (!ws.isOpen()) return
            runCatching { ws.send(Frame.Text(AuraCommand.Close.json())) }
            val reason = withTimeoutOrNull(CLOSE_WAIT_MILLIS) { ws.closeReason.await() }
            if (reason == null) {
                runCatching { ws.close() }
                return
            }
            log.debug("live speech ended: code={}", reason.code)
        }

        private suspend fun command(command: AuraCommand) {
            val ws = openSocket() ?: return
            lastSentAt.set(System.currentTimeMillis())
            try {
                ws.send(Frame.Text(command.json()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("live speech command not sent: error={}", e::class.simpleName)
            }
        }

        /** The socket to send on, reopened if Aura closed it while idle; null once closed by us or lost. */
        private suspend fun openSocket(): DefaultClientWebSocketSession? =
            reopening.withLock {
                val now = line.value as? Line.Open ?: return@withLock null
                if (now.ws.isOpen()) return@withLock now.ws
                when (val reopened = reopen()) {
                    is Resource.Success -> {
                        line.compareAndSet(now, Line.Open(reopened.data))
                        reopened.data
                    }

                    is Resource.Error -> {
                        line.compareAndSet(now, Line.Lost)
                        null
                    }
                }
            }

        private fun DefaultClientWebSocketSession.isOpen() = isActive && !closeReason.isCompleted

        /** `Flushed`, `Cleared` and `Warning` matter to the assistant; `Metadata` doesn't decode and is dropped. */
        private fun control(text: String): SpeechEvent? =
            when (runCatching { WorkersAiJson.decodeFromString(AuraEvent.serializer(), text) }.getOrNull()) {
                AuraEvent.Flushed -> {
                    isSpeaking = false
                    SpeechEvent.Flushed
                }

                AuraEvent.Cleared -> {
                    isSpeaking = false
                    SpeechEvent.Cleared
                }

                AuraEvent.Warning -> {
                    // a refused Flush: the assistant takes what came as the whole reply
                    isSpeaking = false
                    log.warn("live speech warning from the vendor")
                    SpeechEvent.Warning
                }

                null -> {
                    null
                }
            }

        /** The vendor ended the session: reported once, then [next] returns null. */
        private suspend fun failed(ws: DefaultClientWebSocketSession?): SpeechEvent? {
            if (closedByUs.get() || !failureReported.compareAndSet(false, true)) return null
            // the code only: a vendor's close reason is free text
            val code = ws?.let { withTimeoutOrNull(CLOSE_REASON_WAIT_MILLIS) { it.closeReason.await() }?.code }
            log.warn("live speech ended by the vendor: code={}", code)
            line.value = Line.Lost
            keeper.cancel()
            return SpeechEvent.Failed(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
    }

    companion object {
        /** mulaw at 8 kHz: 64 kbit/s, a quarter of 16 kHz PCM, on a phone's data bundle (decision 2026-10-26). */
        val DEFAULT_ENCODING = AudioEncoding.MULAW
        const val DEFAULT_SAMPLE_RATE = 8_000

        /** How long Aura gets to close the socket after our `Close`. */
        private const val CLOSE_WAIT_MILLIS = 1_000L

        /** Workers AI closes Aura's socket 5 s after our last message; a space goes out well before. */
        private const val KEEP_ALIVE_MILLIS = 3_000L
        private const val KEEP_ALIVE_CHECK_MILLIS = 500L

        /** The socket has already ended when this is asked; its close reason is a moment away at most. */
        private const val CLOSE_REASON_WAIT_MILLIS = 100L
    }
}

/** What we send Aura, by `type`: text to speak, and the controls. */
@Serializable
private sealed interface AuraCommand {
    @Serializable
    @SerialName("Speak")
    class Speak(val text: String) : AuraCommand

    @Serializable
    @SerialName("Flush")
    data object Flush : AuraCommand

    @Serializable
    @SerialName("Clear")
    data object Clear : AuraCommand

    @Serializable
    @SerialName("Close")
    data object Close : AuraCommand

    fun json(): String = WorkersAiJson.encodeToString(AuraCommand.serializer(), this)
}

/** Aura's control messages we act on, by `type` (`Metadata` and anything newer aren't listed). */
@Serializable
private sealed interface AuraEvent {
    @Serializable
    @SerialName("Flushed")
    data object Flushed : AuraEvent

    @Serializable
    @SerialName("Cleared")
    data object Cleared : AuraEvent

    /** Aura couldn't act on a command (a `Flush` over its rate limit). */
    @Serializable
    @SerialName("Warning")
    data object Warning : AuraEvent
}
