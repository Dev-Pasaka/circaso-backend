package com.circaso.assistant.presentation.controllers

import com.circaso.assistant.domain.models.VoiceClip
import com.circaso.assistant.presentation.dto.SpeechRequest
import com.circaso.assistant.presentation.dto.TranscriptResponse
import com.circaso.assistant.presentation.dto.UsageResponse
import com.circaso.assistant.presentation.mappers.toResponse
import com.circaso.assistant.presentation.services.VoiceService
import com.circaso.integrations.api.Transcript
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.web.EnvelopeSent
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.respondError
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.http.ContentType
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Asha voice and usage"
private const val SCOPE = "Scope: `app` (the user token)."
private const val MAX_CLIP_BYTES = 3 * 1024 * 1024

/** Voice in and out, and the day's allowance. A3, A4 and A7. */
fun Route.voiceController(voice: VoiceService) {
    authenticate(Scope.APP) {
        route("/assistant") {
            post("voice") {
                val bytes = call.receive<ByteArray>()
                val seconds = call.request.header("X-Duration-Seconds")?.toIntOrNull()
                val result: Resource<Transcript> =
                    if (seconds == null || bytes.size > MAX_CLIP_BYTES) {
                        Resource.error(
                            SharedError.Malformed(
                                mapOf("X-Duration-Seconds" to "is required, and the clip must be under 3 MB"),
                            ),
                        )
                    } else {
                        voice.transcribe(
                            call.userId(),
                            VoiceClip(
                                bytes,
                                call.request.header("Content-Type") ?: "audio/mp4",
                                seconds,
                                call.request.header("Accept-Language"),
                            ),
                        )
                    }
                call.respondDefault(result.toDefaultResponse { TranscriptResponse(it.text, it.seconds) })
            }.docs<NoBody, TranscriptResponse>(
                "A3 · Transcribe a voice clip",
                TAG,
                listOf("assistant.voice_too_long", "assistant.quota_exceeded"),
                notes =
                    "$SCOPE The clip is the raw body (up to 60 s, under 3 MB); send `Content-Type` of the audio " +
                        "and `X-Duration-Seconds`.",
            )

            post("speech") {
                val body = call.receive<SpeechRequest>()
                when (val result = voice.speak(call.userId(), body.text, body.language)) {
                    is Resource.Success -> {
                        call.attributes.put(EnvelopeSent, true)
                        call.respondBytes(result.data.bytes, ContentType.parse(result.data.contentType))
                    }

                    is Resource.Error -> {
                        call.respondError(result.error)
                    }
                }
            }.docs<SpeechRequest, NoBody>(
                "A4 · Turn a reply into audio",
                TAG,
                listOf("assistant.quota_exceeded"),
                notes = "$SCOPE The response is the audio itself; an error is the envelope.",
            )

            get("usage") {
                call.respondDefault(voice.getUsage(call.userId()).toDefaultResponse { it.toResponse() })
            }.docs<NoBody, UsageResponse>("A7 · Today's allowance used and left, and the plan", TAG, notes = SCOPE)
        }
    }
}
