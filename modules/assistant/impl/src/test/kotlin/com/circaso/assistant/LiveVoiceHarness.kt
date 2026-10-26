package com.circaso.assistant

import com.circaso.assistant.domain.usecases.MeterLiveVoice
import com.circaso.assistant.domain.usecases.OpenLiveVoice
import com.circaso.assistant.presentation.controllers.liveVoiceController
import com.circaso.assistant.presentation.services.LiveVoiceService
import com.circaso.identity.api.Principal
import com.circaso.identity.api.SessionId
import com.circaso.identity.api.TokenVerifier
import com.circaso.integrations.api.AudioEncoding
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LiveAudioFormat
import com.circaso.integrations.api.LiveSpeech
import com.circaso.integrations.api.LiveSpeechSession
import com.circaso.integrations.api.LiveTranscription
import com.circaso.integrations.api.LiveTranscriptionOptions
import com.circaso.integrations.api.LiveTranscriptionSession
import com.circaso.integrations.api.SpeechEvent
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.channels.Channel
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The live transcription, driven by the test: it pushes what the provider "heard". English only, like Nova-3 here. */
class ScriptedEars : LiveTranscription {
    val sessions = CopyOnWriteArrayList<Session>()
    val opened = Channel<Session>(Channel.UNLIMITED)

    class Session : LiveTranscriptionSession {
        private val events = Channel<TranscriptionEvent>(Channel.UNLIMITED)
        val frames = CopyOnWriteArrayList<ByteArray>()

        @Volatile var isClosed = false

        fun push(vararg heard: TranscriptionEvent) = heard.forEach { events.trySend(it) }

        override suspend fun send(audio: ByteArray) {
            frames += audio
        }

        override suspend fun next(): TranscriptionEvent? = events.receiveCatching().getOrNull()

        override suspend fun close() {
            isClosed = true
            events.close()
        }
    }

    override suspend fun open(options: LiveTranscriptionOptions): Resource<LiveTranscriptionSession> {
        val language = options.language ?: "en"
        if (!language.startsWith("en")) return Resource.error(IntegrationError.UnsupportedLanguage(language))
        return Resource.success(Session().also { sessions += it }.also { opened.trySend(it) })
    }
}

/** The live voice: a sentence's audio is its text's bytes, sent on `flush`. English only, like Aura. */
class RecordingVoice(private val sampleRate: Int = 16_000) : LiveSpeech {
    val sessions = CopyOnWriteArrayList<Session>()

    inner class Session : LiveSpeechSession {
        private val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        private val queued = StringBuilder()
        val spoken = CopyOnWriteArrayList<String>()
        val clears = AtomicInteger()

        @Volatile var isClosed = false

        override val format = LiveAudioFormat(AudioEncoding.LINEAR16, sampleRate)

        override suspend fun speak(text: String) {
            spoken += text
            queued.append(text)
        }

        override suspend fun flush() {
            val text = queued.toString()
            queued.clear()
            if (text.isNotEmpty()) events.trySend(SpeechEvent.Audio(text.toByteArray()))
            events.trySend(SpeechEvent.Flushed)
        }

        override suspend fun clear() {
            clears.incrementAndGet()
            queued.clear()
            events.trySend(SpeechEvent.Cleared)
        }

        override suspend fun next(): SpeechEvent? = events.receiveCatching().getOrNull()

        override suspend fun close() {
            isClosed = true
            events.close()
        }
    }

    override suspend fun open(language: String?): Resource<LiveSpeechSession> {
        if (language != null && !language.startsWith("en")) {
            return Resource.error(IntegrationError.UnsupportedLanguage(language))
        }
        return Resource.success(Session().also { sessions += it })
    }
}

/** `user-token` is the user's app token, `agent-token` the same user's agent token; anything else is refused. */
class TestVerifier(val userId: UserId) : TokenVerifier {
    override suspend fun verify(accessToken: String): Resource<Principal> =
        when (accessToken) {
            "user-token" -> Resource.success(Principal(userId, DeviceId.new(), SessionId.new(), Scope.APP))
            "agent-token" -> Resource.success(Principal(userId, DeviceId.new(), SessionId.new(), Scope.AGENT))
            else -> Resource.error(SharedError.TokenInvalid)
        }
}

/** The assistant's A11 socket over the [Harness], served at `/v1/assistant/voice/live` on Ktor's test host. */
class LiveHarness(val h: Harness, val userId: UserId = UserId.new(), voiceSampleRate: Int = 16_000) {
    val ears = ScriptedEars()
    val voice = RecordingVoice(voiceSampleRate)
    private val clock = Clock.systemUTC()

    val service =
        LiveVoiceService(
            OpenLiveVoice(TestVerifier(userId), ears, voice, h.plans, h.quota, h.config, clock),
            MeterLiveVoice(h.quota, clock),
            h.start,
            h.resume,
        )

    fun serve(builder: ApplicationTestBuilder) =
        builder.application {
            install(WebSockets)
            routing { route("/v1") { liveVoiceController(service) } }
        }
}
