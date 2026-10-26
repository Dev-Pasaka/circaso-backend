package com.circaso.assistant

import com.circaso.assistant.domain.usecases.CheckLiveSession
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
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
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

    /** Makes the next opens fail as if Nova-3 were down. */
    @Volatile var unavailable = false

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
        if (unavailable) return Resource.error(IntegrationError.Unavailable("nova-3"))
        return Resource.success(Session().also { sessions += it }.also { opened.trySend(it) })
    }
}

/**
 * The live voice: a sentence's audio is its text's bytes, sent on `flush`, as 8 kHz mulaw (Aura's default), so a byte
 * plays for 1/8000 s. English only, like Aura.
 */
class RecordingVoice(private val sampleRate: Int = 8_000) : LiveSpeech {
    val sessions = CopyOnWriteArrayList<Session>()

    inner class Session : LiveSpeechSession {
        private val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        private val queued = StringBuilder()
        val spoken = CopyOnWriteArrayList<String>()
        val clears = AtomicInteger()

        @Volatile var isClosed = false

        override val format = LiveAudioFormat(AudioEncoding.MULAW, sampleRate)

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

/**
 * `user-token` and `fresh-token` are the user's app tokens, `agent-token` the same user's agent token, `revoked-token`
 * one of a revoked session (identity's own error, not a shared one); anything else is refused. [revoked] revokes the
 * user's session, so every token of it fails from then on.
 */
class TestVerifier(val userId: UserId) : TokenVerifier {
    @Volatile var revoked = false

    private data object SessionRevoked : DomainError {
        override val code = "identity.session_revoked"
        override val message = "Signed out."
        override val kind = ErrorKind.UNAUTHORIZED
    }

    override suspend fun verify(accessToken: String): Resource<Principal> =
        when {
            revoked || accessToken == "revoked-token" -> Resource.error(SessionRevoked)
            accessToken == "boom" -> error("the deny-list is unreachable")
            else -> principal(accessToken)
        }

    private fun principal(accessToken: String): Resource<Principal> =
        when (accessToken) {
            "user-token", "fresh-token" -> {
                Resource.success(
                    Principal(userId, DeviceId.new(), SessionId.new(), Scope.APP),
                )
            }

            "agent-token" -> {
                Resource.success(Principal(userId, DeviceId.new(), SessionId.new(), Scope.AGENT))
            }

            else -> {
                Resource.error(SharedError.TokenInvalid)
            }
        }
}

/** The assistant's A11 socket over the [Harness], served at `/v1/assistant/voice/live` on Ktor's test host. */
class LiveHarness(val h: Harness, val userId: UserId = UserId.new()) {
    val ears = ScriptedEars()
    val voice = RecordingVoice()
    val verifier = TestVerifier(userId)
    private val clock = Clock.systemUTC()

    val service =
        LiveVoiceService(
            OpenLiveVoice(verifier, ears, voice, h.plans, h.quota, h.config, clock),
            MeterLiveVoice(h.quota, clock),
            h.start,
            h.resume,
            CheckLiveSession(verifier),
        )

    fun serve(builder: ApplicationTestBuilder) =
        builder.application {
            install(WebSockets)
            routing { route("/v1") { liveVoiceController(service) } }
        }
}
