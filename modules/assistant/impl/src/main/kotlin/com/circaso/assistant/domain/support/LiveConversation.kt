package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.LiveEnd
import com.circaso.assistant.domain.models.LiveEvent
import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.integrations.api.AudioSpan
import com.circaso.integrations.api.SpeechEvent
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToLong

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
 *   off half way: a turn that has started is saved even if the user hangs up or talks over it, but nothing more is
 *   sent once the conversation has ended.
 * - **Barge-in.** Speech from the user while Asha is thinking, speaking or still being heard clears the speech
 *   provider's queue, tells the phone to stop playing, and makes the stale reply silent; the new words become the
 *   next turn. The generation check and the queueing of each sentence happen under the same lock as the barge-in, so
 *   a sentence of a reply the user talked over can't slip through.
 * - **Metering** (decisions 2026-10-26). Voice seconds count only speech. The user's is measured on the transcriber's
 *   own clock, from the first word's start to the last word's end of each stretch of speech, so a burst of late events
 *   can't stretch or shrink it (arrival time is the fallback for a provider without timestamps). Asha's is charged as
 *   it plays on the phone, so what a barge-in cuts off is never charged. An open mic, silence and pauses are free.
 *   Seconds go to [meter] as they gather and are taken off only once it accepts them; once it refuses, the socket
 *   closes at the allowance.
 * - **The session is re-checked** at each turn and every [LiveVoiceLine.sessionRecheck]; a revoked session (signed
 *   out, phone replaced, account deleted) ends the conversation.
 * - **Waits are bounded.** No speech from anyone for [LiveVoiceLine.silence] ends the conversation; a device tool the
 *   phone doesn't answer within [LiveVoiceLine.deviceToolTimeout] ends that turn with an error.
 * - **Nothing kept, nothing leaked.** Audio passes through and is dropped. Both provider sessions are closed however
 *   the conversation ends ([release]).
 */
class LiveConversation(
    val line: LiveVoiceLine,
    /** Asks Asha one question, with the user's agent token for her tools. */
    private val ask: suspend (String, String?) -> Resource<TurnResult>,
    private val answerDevice: suspend (DeviceToolResult) -> Resource<TurnResult>,
    private val meter: suspend (Int) -> Resource<Long>,
    /** Whether this access token (and agent token, when given) are still valid tokens of the line's user. */
    private val checkSession: suspend (String, String?) -> Boolean,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val ended = CompletableDeferred<LiveEnd>()
    private val released = AtomicBoolean(false)
    private val state = Mutex()
    private val utterance = StringBuilder()
    private val generation = AtomicInteger()
    private val unmeteredMicros = AtomicLong()
    private val pendingFlushes = AtomicInteger()

    /** Replies whose audio is sent and still playing on the phone: `reply_done` goes once it has all played. */
    private val playingReplies = ConcurrentLinkedQueue<LiveEvent.ReplyDone>()

    /** Sentences sent to the voice and not yet heard: each goes to the phone as its audio starts, so they line up. */
    private val captions = ConcurrentLinkedQueue<LiveEvent>()

    @Volatile private var captionShown = false

    /** Turns outlive the socket (a started turn is always saved), so they don't run in [run]'s scope. */
    private val turns = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var out: LiveOutput

    /** The generation of the turn being answered, or -1; a turn the user talked over no longer counts. */
    @Volatile private var answering = -1

    @Volatile private var deviceGeneration = -1

    @Volatile private var deviceAskedAt = 0L

    @Volatile private var dropAudio = false

    @Volatile private var playingUntil = 0L

    @Volatile private var lastActivity = 0L

    @Volatile private var lastSessionCheck = 0L

    /**
     * Fallback for a provider without timestamps: the arrival time of the speech being heard now (-1 when nobody
     * speaks), and of its latest word.
     */
    private var speechStart = -1L
    private var lastSpeechAt = 0L

    /**
     * Whether this session's transcriber has ever timed its words. From the first [AudioSpan] on, the arrival-time
     * fallback is ignored, so the two clocks can never both charge the same speech.
     */
    private var hasAudioClock = false

    /** The user's speech on the transcriber's clock, in stream seconds: charged up to [audioFrom], heard up to [audioTo]. */
    private var audioFrom = -1.0
    private var audioTo = 0.0

    /** Asha's audio sent to the phone and not yet played there; charged as [playingUntil] approaches. */
    private val unplayedMicros = AtomicLong()

    /** The user's current tokens: the phone replaces them on the socket before they expire ([refresh]). */
    @Volatile var accessToken: String = line.start.accessToken
        private set

    @Volatile var agentToken: String? = line.start.agentToken
        private set

    /** Turns asked so far, for the closing log line. */
    val turnsAsked = AtomicInteger()

    /** Voice seconds metered so far, both ways. */
    val secondsMetered = AtomicLong()

    /** Runs until the conversation ends, sending everything to [output], and says why it ended. */
    suspend fun run(output: LiveOutput): LiveEnd {
        out = output
        lastActivity = nowMillis()
        lastSessionCheck = lastActivity
        var end: LiveEnd = LiveEnd.PhoneGone
        try {
            end =
                coroutineScope {
                    val workers = listOf(launch { listen() }, launch { relaySpeech() }, launch { watch() })
                    val reason = ended.await()
                    workers.forEach { it.cancel() }
                    reason
                }
            return end
        } finally {
            withContext(NonCancellable) { finish(end) }
        }
    }

    /** Closes both provider sessions and stops sending. Safe to call twice, and without [run] (a failed opening). */
    suspend fun release() {
        ended.complete(LiveEnd.PhoneGone)
        if (!released.compareAndSet(false, true)) return
        runCatching { line.transcription.close() }
        runCatching { line.speech.close() }
        turns.cancel()
    }

    /** One frame of the user's audio. Not metered by itself: only what the transcriber hears as speech counts. */
    suspend fun audio(frame: ByteArray) {
        if (ended.isCompleted) return
        line.transcription.send(frame)
    }

    /** The phone's answer to a device tool; the turn it belongs to carries on. */
    suspend fun deviceResult(result: DeviceToolResult) {
        val asked = deviceGeneration
        if (asked < 0) {
            emit(LiveEvent.TurnFailed(AssistantError.TurnNotFound))
            return
        }
        answering = asked
        turns.launch {
            val answer = withContext(NonCancellable) { answerDevice(result) }
            if (answer is Resource.Error && answer.error is SharedError.Malformed) {
                // a wrong call id: the turn keeps waiting for the right answer
                if (answering == asked) answering = -1
                emit(LiveEvent.TurnFailed(answer.error))
                return@launch
            }
            state.withLock { if (deviceGeneration == asked) deviceGeneration = -1 }
            conclude(asked, answer)
            if (answering == asked) answering = -1
        }
    }

    /** Why a turn was refused is the token's fault: closed like a revoked session. */
    private fun isTokenFailure(error: DomainError) =
        error.kind == ErrorKind.UNAUTHORIZED || error === SharedError.ScopeInsufficient

    /**
     * Fresh tokens from the phone (access tokens live 15 minutes, a conversation can last longer). A token that isn't
     * the same user's valid user token ends the conversation as `unauthorized`.
     */
    suspend fun refresh(accessToken: String, agentToken: String?) {
        if (!checkSession(accessToken, agentToken)) return hangUp(LiveEnd.SessionRevoked)
        this.accessToken = accessToken
        if (agentToken != null) this.agentToken = agentToken
    }

    fun hangUp(end: LiveEnd = LiveEnd.HungUp) {
        ended.complete(end)
    }

    private suspend fun finish(end: LiveEnd) {
        ended.complete(end)
        state.withLock {
            closeSpeech()
            settlePlayback(dropUnplayed = true)
        }
        // the last part second counts, unless the allowance or a revoked session ended it
        if (end !is LiveEnd.OutOfAllowance && end !is LiveEnd.SessionRevoked) settle(final = true)
        release()
    }

    /** Sends [event] unless the conversation has ended: a turn that finishes after a hang-up is saved, not sent. */
    private suspend fun emit(event: LiveEvent) {
        if (!ended.isCompleted) out.send(event)
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
                // voice activity, not words: it resets the silence timer and can stop Asha, but is never charged
                // (noise trips it too, and an open mic is free)
                userSpoke(isWord = false)
            }

            is TranscriptionEvent.Interim -> {
                userSpoke(audio = event.audio)
                emit(LiveEvent.Transcript(joined(event.text), isFinal = false))
            }

            is TranscriptionEvent.Final -> {
                if (event.text.isNotBlank()) {
                    // a final settles words already heard as interims, after the pause that ended them: it starts
                    // speech only when nothing was heard before it, and never stretches it over that pause
                    userSpoke(isWord = speechStart < 0, audio = event.audio)
                    utterance.append(' ').append(event.text.trim())
                    emit(LiveEvent.Transcript(utterance.trim().toString(), isFinal = true))
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

    private suspend fun userSpoke(isWord: Boolean = true, audio: AudioSpan? = null) {
        val now = nowMillis()
        lastActivity = now
        when {
            audio != null -> {
                heardAudio(audio)
            }

            isWord && !hasAudioClock -> {
                if (speechStart < 0) speechStart = now
                lastSpeechAt = now
            }
        }
        if (isAshaBusy()) bargeIn()
    }

    /** Words on the transcriber's clock. A gap of more than a second starts a new stretch; the gap isn't charged. */
    private fun heardAudio(span: AudioSpan) {
        if (!hasAudioClock) {
            // anything the fallback gathered before the first timed words is dropped, not charged twice
            hasAudioClock = true
            speechStart = -1
        }
        if (audioFrom < 0 || span.startSeconds - audioTo > AUDIO_GAP_SECONDS) {
            chargeAudio()
            audioFrom = span.startSeconds
            audioTo = span.startSeconds
        }
        audioTo = max(audioTo, span.endSeconds)
    }

    private fun chargeAudio() {
        if (audioFrom < 0 || audioTo <= audioFrom) return
        unmeteredMicros.addAndGet(((audioTo - audioFrom) * MICROS).roundToLong())
        audioFrom = audioTo
    }

    /** Ends the speech being heard, counting it up to its last word. Called with [state] held. */
    private fun closeSpeech() {
        chargeAudio()
        audioFrom = -1.0
        if (speechStart < 0) return
        unmeteredMicros.addAndGet((lastSpeechAt - speechStart) * 1_000)
        speechStart = -1
    }

    /**
     * Charges the part of Asha's audio that has played by now. [dropUnplayed] forgets the rest: talked over, or the
     * conversation ended before the phone played it. Called with [state] held.
     */
    private fun settlePlayback(dropUnplayed: Boolean = false) {
        val stillToPlay = (playingUntil - nowMillis()).coerceAtLeast(0) * 1_000
        val played = (unplayedMicros.get() - stillToPlay).coerceAtLeast(0)
        unplayedMicros.addAndGet(-played)
        unmeteredMicros.addAndGet(played)
        if (dropUnplayed) unplayedMicros.set(0)
    }

    /** Moves the speech heard so far into the meter; speech with no new word for a while has ended. */
    private suspend fun carrySpeech() =
        state.withLock {
            settlePlayback()
            chargeAudio()
            if (speechStart < 0) return@withLock
            if (nowMillis() - lastSpeechAt > SPEECH_IDLE_MILLIS) {
                closeSpeech()
                return@withLock
            }
            unmeteredMicros.addAndGet((lastSpeechAt - speechStart) * 1_000)
            speechStart = lastSpeechAt
        }

    /** Asha is thinking, waiting for the phone, about to speak, or her audio is still playing on the phone. */
    private fun isAshaBusy(): Boolean =
        answering >= 0 || deviceGeneration >= 0 || pendingFlushes.get() > 0 || nowMillis() < playingUntil

    /** Called with [state] held. */
    private suspend fun bargeIn() {
        settlePlayback(dropUnplayed = true)
        generation.incrementAndGet()
        answering = -1
        deviceGeneration = -1
        dropAudio = true
        pendingFlushes.set(0)
        // a reply cut off half way is still saved: the phone hears of it, unspoken
        captions.filterIsInstance<LiveEvent.ReplyDone>().forEach { emit(it.copy(spoken = false)) }
        generateSequence { playingReplies.poll() }.forEach { emit(it.copy(spoken = false)) }
        captions.clear()
        captionShown = false
        playingUntil = 0
        line.speech.clear()
        emit(LiveEvent.Interrupted)
    }

    /** Called with [state] held. */
    private suspend fun endTurn() {
        closeSpeech()
        val text = utterance.trim().toString()
        utterance.clear()
        if (text.isEmpty()) return

        val asked = generation.incrementAndGet()
        turnsAsked.incrementAndGet()
        lastActivity = nowMillis()
        emit(LiveEvent.TurnStarted(text))
        answer(asked, checked = true) { ask(text, agentToken) }
    }

    /**
     * Runs one turn's use case to the end, whatever happens to the socket, then delivers what it came to. A new turn
     * first re-checks the session (decision 2026-10-26).
     */
    private fun answer(
        asked: Int,
        checked: Boolean,
        turn: suspend () -> Resource<TurnResult>,
    ) {
        if (asked >= 0) answering = asked
        turns.launch {
            if (checked && !checkSession(accessToken, null)) {
                hangUp(LiveEnd.SessionRevoked)
                return@launch
            }
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
        // a token that isn't valid or isn't the user's own (decision 2026-10-26) closes like a revoked session; any
        // other refusal (a question over the length limit) is that turn's error, and the conversation goes on
        if (isTokenFailure(result.error)) {
            hangUp(LiveEnd.SessionRevoked)
            return
        }
        emit(LiveEvent.TurnFailed(result.error))
    }

    private suspend fun answered(asked: Int, result: TurnResult) {
        when (result) {
            is TurnResult.Answer -> {
                if (!speak(asked, result.message)) emit(LiveEvent.ReplyDone(result.message, spoken = false))
            }

            is TurnResult.NeedsDevice -> {
                state.withLock {
                    if (asked == generation.get()) {
                        deviceGeneration = asked
                        deviceAskedAt = nowMillis()
                    }
                }
                emit(LiveEvent.DeviceToolAsked(result))
            }
        }
    }

    /**
     * Sentence by sentence, so the first is heard while the rest is made. Each sentence's text goes to the phone as its
     * audio starts, and `reply_done` after the last sentence's audio. False if the user talked over it first.
     */
    private suspend fun speak(asked: Int, reply: ChatMessage): Boolean {
        for (sentence in sentences(reply.text)) {
            val queued =
                state.withLock {
                    if (asked != generation.get() || ended.isCompleted) return@withLock false
                    dropAudio = false
                    captions.add(LiveEvent.ReplyText(sentence))
                    pendingFlushes.incrementAndGet()
                    line.speech.speak(sentence)
                    line.speech.flush()
                    true
                }
            if (!queued) return false
        }
        return state.withLock {
            if (asked != generation.get()) return@withLock false
            val done = LiveEvent.ReplyDone(reply, spoken = true)
            // it follows the last sentence's audio, and goes once that has played ([deliverPlayedReplies])
            if (pendingFlushes.get() == 0 && captions.isEmpty()) playingReplies.add(done) else captions.add(done)
            true
        }
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

    private suspend fun flushed() =
        state.withLock {
            if (!captionShown) caption()
            captionShown = false
            pendingFlushes.updateAndGet { (it - 1).coerceAtLeast(0) }
            while (captions.peek() is LiveEvent.ReplyDone) playingReplies.add(captions.poll() as LiveEvent.ReplyDone)
        }

    /** The next sentence's text, if it is next in line (not a `reply_done` waiting for audio still to come). */
    private suspend fun caption() {
        if (captions.peek() is LiveEvent.ReplyText) emit(captions.poll())
    }

    private suspend fun played(bytes: ByteArray) {
        val micros = (line.speech.format.seconds(bytes.size.toLong()) * MICROS).roundToLong()
        state.withLock {
            if (dropAudio) return@withLock
            if (!captionShown) {
                caption()
                captionShown = true
            }
            unplayedMicros.addAndGet(micros)
            playingUntil = max(playingUntil, nowMillis()) + micros / 1_000
            emit(LiveEvent.ReplyAudio(bytes))
        }
    }

    // ---- the clock: metering, silence, session and device waits --------------------------------------------

    private suspend fun watch() {
        while (true) {
            delay(TICK_MILLIS)
            carrySpeech()
            deliverPlayedReplies()
            settle(final = false)?.let { return hangUp(it) }
            if (isSessionRevoked()) return hangUp(LiveEnd.SessionRevoked)
            expireDeviceWait()
            val quietSince = max(lastActivity, playingUntil)
            if (!isAshaBusy() && nowMillis() - quietSince >= line.silence.toMillis()) return hangUp(LiveEnd.Silence)
        }
    }

    /** `reply_done` (spoken) for each reply whose audio has finished playing on the phone. */
    private suspend fun deliverPlayedReplies() =
        state.withLock {
            if (playingReplies.isEmpty() || pendingFlushes.get() > 0 || nowMillis() < playingUntil) return@withLock
            generateSequence { playingReplies.poll() }.forEach { emit(it) }
        }

    private suspend fun isSessionRevoked(): Boolean {
        val now = nowMillis()
        if (now - lastSessionCheck < line.sessionRecheck.toMillis()) return false
        lastSessionCheck = now
        return !checkSession(accessToken, null)
    }

    /** A device tool the phone never answered ends its turn, so the conversation (and its silence timer) goes on. */
    private suspend fun expireDeviceWait() {
        val expired =
            state.withLock {
                // not while the phone's answer is being worked on
                val waiting = deviceGeneration >= 0 && answering != deviceGeneration
                val late = nowMillis() - deviceAskedAt >= line.deviceToolTimeout.toMillis()
                if (waiting && late) deviceGeneration = -1
                waiting && late
            }
        if (!expired) return
        lastActivity = nowMillis()
        emit(LiveEvent.TurnFailed(AssistantError.TurnNotFound))
    }

    /**
     * Meters the whole seconds gathered so far, or at the [final] settle the rest rounded to the nearest second. They
     * are taken off only once [meter] accepts them.
     */
    private suspend fun settle(final: Boolean): LiveEnd? {
        val micros = unmeteredMicros.get()
        val seconds = if (final) (micros + MICROS / 2) / MICROS else micros / MICROS
        if (seconds == 0L) return null
        return when (val left = meter(seconds.toInt())) {
            is Resource.Success -> {
                unmeteredMicros.addAndGet(-seconds * MICROS)
                secondsMetered.addAndGet(seconds)
                null
            }

            is Resource.Error -> {
                LiveEnd.OutOfAllowance(left.error)
            }
        }
    }

    private companion object {
        const val TICK_MILLIS = 250L
        const val MICROS = 1_000_000L

        /** A pause longer than this between words on the transcriber's clock starts a new stretch of speech. */
        const val AUDIO_GAP_SECONDS = 1.0

        /** No new word for this long and the speech being heard has ended, even without an end-of-turn event. */
        const val SPEECH_IDLE_MILLIS = 2_000L

        private val sentenceEnd = Regex("(?<=[.!?])\\s+")

        fun sentences(reply: String): List<String> =
            reply
                .trim()
                .split(sentenceEnd)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }
}
