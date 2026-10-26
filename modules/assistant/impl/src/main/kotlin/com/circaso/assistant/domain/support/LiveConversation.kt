package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.LiveEnd
import com.circaso.assistant.domain.models.LiveEvent
import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.integrations.api.SpeechEvent
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.shared.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Where the conversation's events go: the socket, in production. */
fun interface LiveOutput {
    suspend fun send(event: LiveEvent)
}

/**
 * One live voice conversation (A11): the phone's audio in, the transcription's turns out to Asha, her reply back as
 * text and speech, sentence by sentence.
 *
 * - **A turn is A2.** When the transcription says the user has finished, the words go to [ask], which the service binds
 *   to the same use case as A2, so tools, limits and audit are exactly a typed turn's. The model call is never cut
 *   off half way: a turn that has started is saved even if the user hangs up or talks over it.
 * - **Barge-in.** Speech from the user while Asha is thinking, speaking or still being heard clears the speech
 *   provider's queue, tells the phone to stop playing, and makes the stale reply silent; the new words become the
 *   next turn.
 * - **Metering.** Voice seconds count both ways: the time the phone streams audio (gaps over a second left out) and
 *   the playing time of Asha's audio. They go to [meter] a second at a time; once it refuses, the socket closes at
 *   the allowance.
 * - **Silence.** No speech from anyone for [LiveVoiceLine.silence] ends the conversation.
 * - **Nothing kept.** Audio passes through and is dropped; only the turn's text is saved, by the turn itself.
 */
class LiveConversation(
    val line: LiveVoiceLine,
    private val ask: suspend (String) -> Resource<TurnResult>,
    private val answerDevice: suspend (DeviceToolResult) -> Resource<TurnResult>,
    private val meter: suspend (Int) -> Resource<Long>,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val ended = CompletableDeferred<LiveEnd>()
    private val state = Mutex()
    private val utterance = StringBuilder()
    private val generation = AtomicInteger()
    private val unmeteredMillis = AtomicLong()
    private val pendingFlushes = AtomicInteger()

    /** Sentences sent to the voice and not yet heard: each goes to the phone as its audio starts, so they line up. */
    private val captions = ConcurrentLinkedQueue<String>()

    @Volatile private var captionShown = false

    /** Turns outlive the socket (a started turn is always saved), so they don't run in [run]'s scope. */
    private val turns = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var out: LiveOutput

    /** The generation of the turn being answered, or -1; a turn the user talked over no longer counts. */
    @Volatile private var answering = -1

    @Volatile private var deviceGeneration = -1

    @Volatile private var dropAudio = false

    @Volatile private var playingUntil = 0L

    @Volatile private var lastActivity = 0L

    @Volatile private var lastAudioAt = -1L

    /** Turns asked so far, for the closing log line. */
    val turnsAsked = AtomicInteger()

    /** Voice seconds metered so far, both ways. */
    val secondsMetered = AtomicLong()

    /** Runs until the conversation ends, sending everything to [output], and says why it ended. */
    suspend fun run(output: LiveOutput): LiveEnd {
        out = output
        lastActivity = nowMillis()
        val end =
            coroutineScope {
                val workers = listOf(launch { listen() }, launch { relaySpeech() }, launch { watch() })
                val end = ended.await()
                workers.forEach { it.cancel() }
                end
            }
        line.transcription.close()
        line.speech.close()
        // the last part second counts, unless the allowance is what ended it: it is a cap
        if (end !is LiveEnd.OutOfAllowance) settle(roundUp = true)
        turns.cancel()
        return end
    }

    /** One frame of the user's audio. */
    suspend fun audio(frame: ByteArray) {
        if (ended.isCompleted) return
        val now = nowMillis()
        val last = lastAudioAt
        lastAudioAt = now
        if (last >= 0 && now - last <= MAX_AUDIO_GAP_MILLIS) unmeteredMillis.addAndGet(now - last)
        line.transcription.send(frame)
    }

    /** The phone's answer to a device tool; the turn it belongs to carries on. */
    fun deviceResult(result: DeviceToolResult) {
        val asked = deviceGeneration
        deviceGeneration = -1
        answer(asked) { answerDevice(result) }
    }

    fun hangUp(end: LiveEnd = LiveEnd.HungUp) {
        ended.complete(end)
    }

    // ---- listening -------------------------------------------------------------------------------------------

    private suspend fun listen() {
        while (true) {
            val event = line.transcription.next() ?: return hangUp(LiveEnd.ProviderFailed)
            state.withLock { heard(event) }
        }
    }

    private suspend fun heard(event: TranscriptionEvent) {
        when (event) {
            is TranscriptionEvent.SpeechStarted -> {
                userSpoke()
            }

            is TranscriptionEvent.Interim -> {
                userSpoke()
                out.send(LiveEvent.Transcript(joined(event.text), isFinal = false))
            }

            is TranscriptionEvent.Final -> {
                if (event.text.isNotBlank()) {
                    userSpoke()
                    utterance.append(' ').append(event.text.trim())
                    out.send(LiveEvent.Transcript(utterance.trim().toString(), isFinal = true))
                }
                if (event.endOfTurn) endTurn()
            }

            is TranscriptionEvent.TurnEnded -> {
                endTurn()
            }

            is TranscriptionEvent.Failed -> {
                hangUp(LiveEnd.ProviderFailed)
            }
        }
    }

    private fun joined(interim: String) = (utterance.toString() + " " + interim).trim()

    private suspend fun userSpoke() {
        lastActivity = nowMillis()
        if (isAshaBusy()) bargeIn()
    }

    /** Asha is thinking, waiting for the phone, about to speak, or her audio is still playing on the phone. */
    private fun isAshaBusy(): Boolean =
        answering >= 0 || deviceGeneration >= 0 || pendingFlushes.get() > 0 || nowMillis() < playingUntil

    private suspend fun bargeIn() {
        generation.incrementAndGet()
        answering = -1
        deviceGeneration = -1
        dropAudio = true
        pendingFlushes.set(0)
        captions.clear()
        captionShown = false
        playingUntil = 0
        line.speech.clear()
        out.send(LiveEvent.Interrupted)
    }

    private suspend fun endTurn() {
        val text = utterance.trim().toString()
        utterance.clear()
        if (text.isEmpty()) return

        val asked = generation.incrementAndGet()
        turnsAsked.incrementAndGet()
        lastActivity = nowMillis()
        out.send(LiveEvent.TurnStarted(text))
        answer(asked) { ask(text) }
    }

    /** Runs one turn's use case to the end, whatever happens to the socket, then delivers what it came to. */
    private fun answer(asked: Int, turn: suspend () -> Resource<TurnResult>) {
        if (asked >= 0) answering = asked
        turns.launch {
            val result = withContext(NonCancellable) { turn() }
            conclude(asked, result)
            if (answering == asked) answering = -1
        }
    }

    // ---- answering -------------------------------------------------------------------------------------------

    private suspend fun conclude(asked: Int, result: Resource<TurnResult>) {
        when (result) {
            is Resource.Error -> failed(result)
            is Resource.Success -> answered(asked, result.data)
        }
        lastActivity = nowMillis()
    }

    private suspend fun failed(result: Resource.Error) {
        if (result.error is AssistantError.QuotaExceeded) {
            hangUp(LiveEnd.OutOfAllowance(result.error))
            return
        }
        out.send(LiveEvent.TurnFailed(result.error))
    }

    private suspend fun answered(asked: Int, result: TurnResult) {
        when (result) {
            is TurnResult.Answer -> {
                val spoken = speak(asked, result.message.text)
                out.send(LiveEvent.ReplyDone(result.message, spoken))
            }

            is TurnResult.NeedsDevice -> {
                if (asked == generation.get()) deviceGeneration = asked
                out.send(LiveEvent.DeviceToolAsked(result))
            }
        }
    }

    /** Sentence by sentence, so the first is heard while the rest is made; false if the user talked over it. */
    private suspend fun speak(asked: Int, reply: String): Boolean {
        for (sentence in sentences(reply)) {
            if (asked != generation.get()) return false
            dropAudio = false
            captions.add(sentence)
            pendingFlushes.incrementAndGet()
            line.speech.speak(sentence)
            line.speech.flush()
        }
        return asked == generation.get()
    }

    private suspend fun relaySpeech() {
        while (true) {
            when (val event = line.speech.next() ?: return hangUp(LiveEnd.ProviderFailed)) {
                is SpeechEvent.Audio -> played(event.bytes)
                is SpeechEvent.Flushed -> flushed()
                is SpeechEvent.Cleared -> dropAudio = false
                is SpeechEvent.Failed -> hangUp(LiveEnd.ProviderFailed)
            }
        }
    }

    private suspend fun flushed() {
        if (!captionShown) caption()
        captionShown = false
        pendingFlushes.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    private suspend fun caption() {
        captions.poll()?.let { out.send(LiveEvent.ReplyText(it)) }
    }

    private suspend fun played(bytes: ByteArray) {
        if (dropAudio) return
        if (!captionShown) {
            caption()
            captionShown = true
        }
        val millis = (line.speech.format.seconds(bytes.size.toLong()) * 1_000).toLong()
        unmeteredMillis.addAndGet(millis)
        playingUntil = max(playingUntil, nowMillis()) + millis
        out.send(LiveEvent.ReplyAudio(bytes))
    }

    // ---- the clock: metering and silence ---------------------------------------------------------------------

    private suspend fun watch() {
        while (true) {
            delay(TICK_MILLIS)
            settle(roundUp = false)?.let { return hangUp(it) }
            val quietSince = max(lastActivity, playingUntil)
            if (!isAshaBusy() && nowMillis() - quietSince >= line.silence.toMillis()) return hangUp(LiveEnd.Silence)
        }
    }

    /** Meters the whole seconds gathered so far (and the last part second when the conversation ends). */
    private suspend fun settle(roundUp: Boolean): LiveEnd? {
        val millis = unmeteredMillis.get()
        val seconds = (millis / 1_000 + if (roundUp && millis % 1_000 > 0) 1 else 0).toInt()
        if (seconds == 0) return null
        unmeteredMillis.addAndGet(-seconds * 1_000L)
        secondsMetered.addAndGet(seconds.toLong())
        return when (val left = meter(seconds)) {
            is Resource.Success -> null
            is Resource.Error -> LiveEnd.OutOfAllowance(left.error)
        }
    }

    private companion object {
        const val TICK_MILLIS = 250L

        /** A pause in the phone's stream longer than this isn't voice time (the phone muted, or the network hung). */
        const val MAX_AUDIO_GAP_MILLIS = 1_000L

        private val sentenceEnd = Regex("(?<=[.!?])\\s+")

        fun sentences(reply: String): List<String> =
            reply
                .trim()
                .split(sentenceEnd)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }
}
