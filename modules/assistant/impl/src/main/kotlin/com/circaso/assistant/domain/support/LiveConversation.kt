package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.LiveEnd
import com.circaso.assistant.domain.models.LiveEvent
import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.MascotHints
import com.circaso.assistant.domain.models.ToolStatus
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
import kotlin.math.roundToInt
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
 * - **Each turn is timed** (decision 2026-10-30) on [nowMillis]'s clock, from the transcriber's end of turn to the
 *   model's first words, the first sentence queued with the voice and the first of its audio sent to the phone. The
 *   [LiveEvent.Timing] goes to the phone and to [timed] once per turn: at its first audio, or as soon as the turn can
 *   no longer have any (talked over, failed, nothing to say, the next turn, the end), with what never happened null.
 */
class LiveConversation(
    val line: LiveVoiceLine,
    /** Asks Asha one question, with the user's agent token for her tools; her reply streams to the listener. */
    private val ask: suspend (String, String?, ReplyListener) -> Resource<TurnResult>,
    private val answerDevice: suspend (DeviceToolResult, ReplyListener) -> Resource<TurnResult>,
    private val meter: suspend (Int) -> Resource<Long>,
    /** Whether this access token (and agent token, when given) are still valid tokens of the line's user. */
    private val checkSession: suspend (String, String?) -> Boolean,
    /** Stores the day's usage totals, once, after the conversation's last seconds are metered. */
    private val saveUsage: suspend () -> Unit = {},
    /** Records each turn's [LiveEvent.Timing] (the log line and the latency metric), sent to the phone or not. */
    private val timed: suspend (LiveEvent.Timing) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val ended = CompletableDeferred<LiveEnd>()
    private val released = AtomicBoolean(false)
    private val state = Mutex()
    private val utterance = StringBuilder()
    private val generation = AtomicInteger()
    private val unmeteredMicros = AtomicLong()

    /** Replies whose audio is all sent and still playing on the phone: `reply_done` goes once it has played. */
    private val playingReplies = ConcurrentLinkedQueue<LiveEvent.ReplyDone>()

    /**
     * The reply being spoken, from its first sentence until the voice answers its one `Flush`. Sentences are queued
     * with the voice as the model writes them (decision 2026-10-27), and the reply is flushed once, when the turn
     * ends or pauses for the phone. Its sentences go to the phone as the audio reaches them, timed at
     * [SPOKEN_CHARS_PER_SECOND] (the voice doesn't say where a sentence starts).
     */
    private class Speaking(val generation: Int) {
        val captions = ArrayDeque<Caption>()
        var chars = 0
        var audioSeconds = 0.0
        var isFlushed = false

        /** The saved reply, once the turn has ended with one; a reply paused for the phone has none yet. */
        var done: LiveEvent.ReplyDone? = null
    }

    /** One turn's reply on its way to the voice: each sentence is spoken as soon as the model has written it. */
    private inner class SpokenReply(private val asked: Int) : ReplyListener {
        private val unfinished = StringBuilder()

        override suspend fun text(delta: String) {
            clockOf(asked)?.firstText?.compareAndSet(-1, nowMillis())
            unfinished.append(delta)
            val parts = unfinished.split(sentenceEnd)
            if (parts.size < 2) return
            parts
                .dropLast(1)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { queueSentence(asked, it) }
            unfinished.setLength(0)
            unfinished.append(parts.last())
        }

        /** The phone shows what Asha is doing while she looks something up, unless the user has moved on. */
        override suspend fun toolStarted(status: ToolStatus) {
            if (generation.get() == asked) emit(LiveEvent.ToolStarted(status))
        }

        override suspend fun toolFinished(status: ToolStatus, isOk: Boolean) {
            if (generation.get() == asked) emit(LiveEvent.ToolFinished(status, isOk))
        }

        /** The mascot's hints go to the phone at once, unless the user has moved on. */
        override suspend fun mascot(hints: MascotHints) {
            if (generation.get() == asked) emit(LiveEvent.Mascot(hints))
        }

        /** Her last hints wait for the reply to be heard: they ride on its `reply_done`. */
        override suspend fun mascotDone(hints: MascotHints) {
            endHints = hints
        }

        var endHints: MascotHints? = null
            private set

        /**
         * The turn has ended (or paused for the phone): the last words are spoken, unless the turn failed half way
         * ([speakRest] false), and the reply is flushed. False if the user talked over it first.
         */
        suspend fun finish(done: LiveEvent.ReplyDone?, speakRest: Boolean): Boolean {
            val rest = unfinished.trim().toString()
            unfinished.setLength(0)
            if (speakRest && rest.isNotEmpty()) queueSentence(asked, rest)
            return flushReply(asked, done)
        }
    }

    private class Caption(val text: String, val atSeconds: Double)

    /** One turn's stamps on [nowMillis]'s clock, from [endedAt], the end of the user's speech; -1 until they happen. */
    private class TurnClock(val turn: Int, val endedAt: Long) {
        val firstText = AtomicLong(-1)

        /** Written with [state] held, like [firstAudio]. */
        var firstSentence = -1L
        var firstAudio = -1L
        val isReported = AtomicBoolean(false)

        fun timing() = LiveEvent.Timing(turn, since(firstText.get()), since(firstSentence), since(firstAudio))

        private fun since(at: Long) = if (at < 0) null else at - endedAt
    }

    /** The latest turn's clock; an earlier turn's has been reported by the time it is replaced. */
    @Volatile private var clock: TurnClock? = null

    private fun clockOf(turn: Int): TurnClock? = clock?.takeIf { it.turn == turn }

    /** Sends turn [of]'s timing, once. */
    private suspend fun report(of: TurnClock?) {
        if (of == null || !of.isReported.compareAndSet(false, true)) return
        val timing = of.timing()
        emit(timing)
        timed(timing)
    }

    private var speaking: Speaking? = null

    /** Flushes of replies talked over, whose `Flushed` may still arrive and must not finish the next reply. */
    private var staleFlushes = 0

    /** When the voice last sent anything; a flush unanswered for [FLUSH_STALL_MILLIS] is taken as done. */
    @Volatile private var lastVoiceEventAt = 0L

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
        val reply = SpokenReply(asked)
        turns.launch {
            val answer = withContext(NonCancellable) { answerDevice(result, reply) }
            if (answer is Resource.Error && answer.error is SharedError.Malformed) {
                // a wrong call id: the turn keeps waiting for the right answer
                if (answering == asked) answering = -1
                emit(LiveEvent.TurnFailed(answer.error))
                return@launch
            }
            state.withLock { if (deviceGeneration == asked) deviceGeneration = -1 }
            conclude(asked, answer, reply)
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
            // a turn still unheard is logged with what it reached; the phone has gone
            report(clock)
        }
        // the last part second counts, unless the allowance or a revoked session ended it
        if (end !is LiveEnd.OutOfAllowance && end !is LiveEnd.SessionRevoked) settle(final = true)
        release()
        // after the providers are closed, so a slow database never holds them open; whatever ended the conversation,
        // the seconds metered so far reach the cost report
        saveUsage()
    }

    /** Sends [event] unless the conversation has ended: a turn that finishes after a hang-up is saved, not sent. */
    private suspend fun emit(event: LiveEvent) {
        if (ended.isCompleted) return
        out.send(event)
        // the mascot is done once the reply has been heard (or talked over): its last hints follow `reply_done`
        if (event is LiveEvent.ReplyDone) event.mascot?.let { out.send(LiveEvent.Mascot(it)) }
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
                // (noise trips it too, and an open mic is free). While her voice plays it may be her own voice back
                // from the loudspeaker, so then only words decide (decision 2026-10-29).
                if (voiceMayEcho()) lastActivity = nowMillis() else userSpoke(isWord = false)
            }

            is TranscriptionEvent.Interim -> {
                if (isEcho(event.text)) return
                userSpoke(audio = event.audio)
                emit(LiveEvent.Transcript(joined(event.text), isFinal = false))
            }

            is TranscriptionEvent.Final -> {
                if (event.text.isNotBlank() && !isEcho(event.text)) {
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

    /**
     * Her voice is playing on the phone, or just stopped: what the transcriber hears now may be her own words back
     * from the loudspeaker, however good the phone's echo canceller. Voice activity alone is then not a barge-in.
     */
    private fun voiceMayEcho(): Boolean = speaking != null || nowMillis() < playingUntil + ECHO_TAIL_MILLIS

    /**
     * Words that are hers, heard back: a run of the transcript's words appears, in order, in what she has just said.
     * Only while [voiceMayEcho]; otherwise anything heard is the person. An echo is not the person speaking: it is
     * not a barge-in, not charged, not shown and never becomes a question.
     */
    private fun isEcho(text: String): Boolean {
        if (!voiceMayEcho()) return false
        val heard = words(text)
        if (heard.isEmpty()) return false
        val hers = recentWords.toList()
        if (hers.isEmpty()) return false
        // the longest run of heard words found in order inside her recent words
        var best = 0
        for (start in hers.indices) {
            var h = start
            var run = 0
            for (w in heard) {
                val at = hers.subList(h, hers.size).indexOf(w)
                if (at < 0) break
                run++
                h += at + 1
            }
            if (run > best) best = run
            if (best == heard.size) break
        }
        return best >= maxOf(1, (heard.size * ECHO_MATCH).roundToInt())
    }

    /** Her last sentences, as words, for [isEcho]; appended as each sentence goes to the voice. */
    private val recentWords = ArrayDeque<String>()

    private fun remember(sentence: String) {
        recentWords.addAll(words(sentence))
        while (recentWords.size > RECENT_WORDS) recentWords.removeFirst()
    }

    /** Asha is thinking, waiting for the phone, about to speak, or her audio is still playing on the phone. */
    private fun isAshaBusy(): Boolean =
        answering >= 0 || deviceGeneration >= 0 || speaking != null || nowMillis() < playingUntil

    /** Called with [state] held. */
    private suspend fun bargeIn() {
        settlePlayback(dropUnplayed = true)
        generation.incrementAndGet()
        answering = -1
        deviceGeneration = -1
        dropAudio = true
        // a reply cut off half way is still saved: the phone hears of it, unspoken (one still being written hears of
        // it when its turn ends)
        speaking?.let {
            if (it.isFlushed) staleFlushes++
            it.done?.let { done -> emit(done.copy(spoken = false)) }
        }
        speaking = null
        generateSequence { playingReplies.poll() }.forEach { emit(it.copy(spoken = false)) }
        playingUntil = 0
        line.speech.clear()
        emit(LiveEvent.Interrupted)
        // the turn talked over is timed as far as it got
        report(clock)
    }

    /** Called with [state] held. */
    private suspend fun endTurn() {
        val endedAt = nowMillis()
        closeSpeech()
        val text = utterance.trim().toString()
        utterance.clear()
        if (text.isEmpty()) return

        report(clock)
        val asked = generation.incrementAndGet()
        clock = TurnClock(asked, endedAt)
        turnsAsked.incrementAndGet()
        lastActivity = nowMillis()
        emit(LiveEvent.TurnStarted(text))
        answer(asked, checked = true) { reply -> ask(text, agentToken, reply) }
    }

    /**
     * Runs one turn's use case to the end, whatever happens to the socket, speaking its reply as it streams, then
     * delivers what it came to. A new turn first re-checks the session (decision 2026-10-26).
     */
    private fun answer(
        asked: Int,
        checked: Boolean,
        turn: suspend (ReplyListener) -> Resource<TurnResult>,
    ) {
        if (asked >= 0) answering = asked
        val reply = SpokenReply(asked)
        turns.launch {
            if (checked && !checkSession(accessToken, null)) {
                hangUp(LiveEnd.SessionRevoked)
                return@launch
            }
            val result = withContext(NonCancellable) { turn(reply) }
            conclude(asked, result, reply)
            if (answering == asked) answering = -1
        }
    }

    // ---- answering -------------------------------------------------------------------------------------------

    private suspend fun conclude(
        asked: Int,
        result: Resource<TurnResult>,
        reply: SpokenReply,
    ) {
        when (result) {
            is Resource.Error -> failed(result, reply)
            is Resource.Success -> answered(asked, result.data, reply)
        }
        lastActivity = nowMillis()
        // a turn that ended with nothing for the voice will never have audio: timed now. One waiting for the phone
        // carries on later, and one with sentences queued is timed at its first audio.
        state.withLock {
            val ofTurn = clockOf(asked)
            val isWaiting = result is Resource.Success && result.data is TurnResult.NeedsDevice
            if (ofTurn != null && ofTurn.firstSentence < 0 && !isWaiting) report(ofTurn)
        }
    }

    private suspend fun failed(result: Resource.Error, reply: SpokenReply) {
        // the sentences already spoken are let out; the half-written one is dropped
        reply.finish(done = null, speakRest = false)
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

    private suspend fun answered(
        asked: Int,
        result: TurnResult,
        reply: SpokenReply,
    ) {
        when (result) {
            is TurnResult.Answer -> {
                val done = LiveEvent.ReplyDone(result.message, spoken = true, mascot = reply.endHints)
                if (!reply.finish(done, speakRest = true)) emit(done.copy(spoken = false))
            }

            is TurnResult.NeedsDevice -> {
                // what Asha said before asking the phone ("Let me look in your chats.") is heard while it searches
                reply.finish(done = null, speakRest = true)
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

    /** Queues one sentence of turn [asked]'s reply with the voice, unless the user has talked over it since. */
    private suspend fun queueSentence(asked: Int, sentence: String) =
        state.withLock {
            if (asked != generation.get() || ended.isCompleted) return@withLock
            val reply = speakingFor(asked)
            reply.captions.add(Caption(sentence, reply.chars / SPOKEN_CHARS_PER_SECOND))
            reply.chars += sentence.length + 1
            remember(sentence)
            clockOf(asked)?.takeIf { it.firstSentence < 0 }?.firstSentence = nowMillis()
            line.speech.speak(sentence)
        }

    /** The reply of turn [asked] still taking sentences, started if need be. Called with [state] held. */
    private suspend fun speakingFor(asked: Int): Speaking {
        speaking?.takeIf { it.generation == asked && !it.isFlushed }?.let { return it }
        // the part of this turn spoken before it paused for the phone: let it out, and ignore its Flushed when it comes
        speaking?.let {
            if (it.isFlushed) staleFlushes++
            letOut(it)
        }
        dropAudio = false
        return Speaking(asked).also { speaking = it }
    }

    /**
     * One `Flush` for the reply of turn [asked] (one per reply keeps under the voice's flush limit); `reply_done`
     * ([done]) goes once its audio has played. False if the user talked over it, or nothing of it was spoken.
     */
    private suspend fun flushReply(asked: Int, done: LiveEvent.ReplyDone?): Boolean =
        state.withLock {
            if (asked != generation.get() || ended.isCompleted) return@withLock false
            val reply = speaking?.takeIf { it.generation == asked && !it.isFlushed } ?: return@withLock done == null
            reply.done = done
            reply.isFlushed = true
            lastVoiceEventAt = nowMillis()
            line.speech.flush()
            true
        }

    private suspend fun relaySpeech() {
        while (true) {
            when (val event = line.speech.next() ?: return hangUp(LiveEnd.ProviderFailed)) {
                is SpeechEvent.Audio -> played(event.bytes)

                is SpeechEvent.Flushed -> flushed()

                // a refused Flush (over the rate limit) won't be answered; its audio, if any, has come
                is SpeechEvent.Warning -> flushed()

                is SpeechEvent.Cleared -> cleared()

                is SpeechEvent.Failed -> hangUp(LiveEnd.ProviderFailed)
            }
        }
    }

    private suspend fun flushed() =
        state.withLock {
            lastVoiceEventAt = nowMillis()
            if (staleFlushes > 0) {
                staleFlushes-- // the answer to a flush of a reply talked over
                return@withLock
            }
            flushDone()
        }

    /** After a `Clear` the voice answers no earlier flush: none is stale any more. */
    private suspend fun cleared() =
        state.withLock {
            dropAudio = false
            staleFlushes = 0
        }

    /** The flushed reply's audio is all sent ([letOut]). Called with [state] held. */
    private suspend fun flushDone() {
        speaking?.takeIf { it.isFlushed }?.let { letOut(it) }
    }

    /** The reply's last sentences go out, and `reply_done` once it has played. Called with [state] held. */
    private suspend fun letOut(reply: Speaking) {
        generateSequence { reply.captions.removeFirstOrNull() }.forEach { emit(LiveEvent.ReplyText(it.text)) }
        reply.done?.let { playingReplies.add(it) }
        // the turn's whole reply is out: if none of it was ever heard (a voice with no audio), it is timed without
        if (reply.done != null) report(clockOf(reply.generation))
        if (speaking === reply) speaking = null
    }

    /** The sentences the audio has reached, before [chunkSeconds] more of it. Called with [state] held. */
    private suspend fun captionsUpTo(reply: Speaking, chunkSeconds: Double) {
        while (reply.captions.firstOrNull()?.let { it.atSeconds <= reply.audioSeconds } == true) {
            emit(LiveEvent.ReplyText(reply.captions.removeFirst().text))
        }
        reply.audioSeconds += chunkSeconds
    }

    private suspend fun played(bytes: ByteArray) {
        val micros = (line.speech.format.seconds(bytes.size.toLong()) * MICROS).roundToLong()
        state.withLock {
            lastVoiceEventAt = nowMillis()
            if (dropAudio) return@withLock
            speaking?.let { captionsUpTo(it, micros.toDouble() / MICROS) }
            unplayedMicros.addAndGet(micros)
            playingUntil = max(playingUntil, nowMillis()) + micros / 1_000
            emit(LiveEvent.ReplyAudio(bytes))
            firstAudio(speaking?.generation)
        }
    }

    /** The first audio of turn [turn]'s reply has gone to the phone: the turn is timed. Called with [state] held. */
    private suspend fun firstAudio(turn: Int?) {
        val ofTurn = turn?.let(::clockOf) ?: return
        if (ofTurn.firstSentence < 0 || ofTurn.firstAudio >= 0) return
        ofTurn.firstAudio = nowMillis()
        report(ofTurn)
    }

    // ---- the clock: metering, silence, session and device waits --------------------------------------------

    private suspend fun watch() {
        while (true) {
            delay(TICK_MILLIS)
            carrySpeech()
            unstallFlush()
            deliverPlayedReplies()
            settle(final = false)?.let { return hangUp(it) }
            if (isSessionRevoked()) return hangUp(LiveEnd.SessionRevoked)
            expireDeviceWait()
            val quietSince = max(lastActivity, playingUntil)
            if (!isAshaBusy() && nowMillis() - quietSince >= line.silence.toMillis()) return hangUp(LiveEnd.Silence)
        }
    }

    /**
     * A flush the voice never answers (a lost `Flushed`) would hold `reply_done` and the silence close forever: after
     * [FLUSH_STALL_MILLIS] with nothing from the voice, it is taken as done.
     */
    private suspend fun unstallFlush() =
        state.withLock {
            if (speaking?.isFlushed != true || nowMillis() - lastVoiceEventAt < FLUSH_STALL_MILLIS) return@withLock
            flushDone()
        }

    /** `reply_done` (spoken) for each reply whose audio has finished playing on the phone. */
    private suspend fun deliverPlayedReplies() =
        state.withLock {
            if (playingReplies.isEmpty() || speaking != null || nowMillis() < playingUntil) return@withLock
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
        state.withLock { report(clock) }
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

        /** How fast the voice speaks, to time each sentence's text against its audio (as A4 meters speech). */
        const val SPOKEN_CHARS_PER_SECOND = 15.0

        /** Nothing from the voice for this long after a flush, and the flush is taken as answered. */
        const val FLUSH_STALL_MILLIS = 3_000L

        /** A pause longer than this between words on the transcriber's clock starts a new stretch of speech. */
        const val AUDIO_GAP_SECONDS = 1.0

        /** No new word for this long and the speech being heard has ended, even without an end-of-turn event. */
        const val SPEECH_IDLE_MILLIS = 2_000L

        /** The space after a sentence's last mark: a sentence is whole once the model has written past it. */
        private val sentenceEnd = Regex("(?<=[.!?])\\s+")

        /** After her audio has played out, the room and the transcriber can still be returning it for this long. */
        const val ECHO_TAIL_MILLIS = 1_500L

        /** The share of a transcript's words that must be hers, in order, for it to be her echo. */
        const val ECHO_MATCH = 0.7

        const val RECENT_WORDS = 120

        private val wordChars = Regex("[^\\p{L}\\p{N}']+")

        private fun words(text: String): List<String> = text.lowercase().split(wordChars).filter { it.isNotEmpty() }
    }
}
