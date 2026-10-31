package com.circaso.assistant.presentation.controllers

import com.circaso.assistant.presentation.dto.SpeechRequest
import com.circaso.assistant.presentation.dto.TranscriptResponse
import com.circaso.assistant.presentation.dto.UsageResponse
import com.circaso.assistant.presentation.dto.VoicesResponse
import com.circaso.assistant.presentation.mappers.DURATION_HEADER
import com.circaso.assistant.presentation.mappers.toResponse
import com.circaso.assistant.presentation.mappers.voiceClipOf
import com.circaso.assistant.presentation.services.VoiceService
import com.circaso.integrations.api.SpokenAudio
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.EnvelopeSent
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.respondError
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Asha voice and usage"
private const val SCOPE = "Scope: `app` (the user token)."

/** Voice in and out, Asha's voices, and the day's allowance. A3, A4, A7, A12 and A13. */
fun Route.voiceController(voice: VoiceService) {
    authenticate(Scope.APP) {
        route("/assistant") {
            post("voice") {
                val clip =
                    voiceClipOf(
                        call.receive<ByteArray>(),
                        call.request.header(DURATION_HEADER),
                        call.request.header("Content-Type"),
                        call.request.header("Accept-Language"),
                    )
                val result = clip.flatMap { voice.transcribe(call.userId(), it) }
                call.respondDefault(result.toDefaultResponse { TranscriptResponse(it.text, it.seconds) })
            }.docs<NoBody, TranscriptResponse>(
                "A3 · Transcribe a voice clip",
                TAG,
                listOf("assistant.voice_too_long", "assistant.quota_exceeded", "request.malformed"),
                notes =
                    "$SCOPE The clip is the raw body (up to 60 s, under 3 MB); send `Content-Type` of the audio " +
                        "and `X-Duration-Seconds`, the clip's length in seconds (a decimal such as `5.5` is " +
                        "fine; it is rounded up). A missing or non-positive length and a clip over 3 MB are " +
                        "`request.malformed`, each named in `details.fields`.",
            )

            post("speech") {
                val body = call.receive<SpeechRequest>()
                call.respondAudio(voice.speak(call.userId(), body.text, body.language, body.voice))
            }.docs<SpeechRequest, NoBody>(
                "A4 · Turn a reply into audio",
                TAG,
                listOf("assistant.quota_exceeded", "assistant.language_not_supported", "assistant.model_unavailable"),
                notes =
                    "$SCOPE The response is the audio itself; an error is the envelope. The server voice speaks " +
                        "English only: speak a Swahili reply with the phone's own text-to-speech. `voice` is an id " +
                        "from A12; a missing or unknown one is Asha's default voice, never an error.",
            )

            route("/voices") {
                get {
                    call.respondDefault(voice.voices().toDefaultResponse { it.toResponse() })
                }.docs<NoBody, VoicesResponse>(
                    "A12 · The voices Asha can speak in",
                    TAG,
                    notes =
                        "$SCOPE `default: true` marks the voice used when the app sends none. Keep the user's " +
                            "choice on the phone and send its `id` as `voice` on A4 and on A11's `auth` frame. " +
                            "`gender` is `female` or `male`, a hint for the picker.",
                )

                get("{voiceId}/sample") {
                    val sample = voice.voiceSample(call.parameters["voiceId"].orEmpty())
                    if (sample is Resource.Success) call.response.cacheControl(SAMPLE_CACHE)
                    call.respondAudio(sample)
                }.docs<NoBody, NoBody>(
                    "A13 · Hear a voice before choosing it",
                    TAG,
                    listOf("assistant.voice_not_found", "assistant.model_unavailable"),
                    notes =
                        "$SCOPE The response is the audio itself (MP3); an error is the envelope. The same short " +
                            "line in each voice. Not counted against the voice allowance.",
                )
            }

            get("usage") {
                call.respondDefault(voice.getUsage(call.userId()).toDefaultResponse { it.toResponse() })
            }.docs<NoBody, UsageResponse>("A7 · Today's allowance used and left, and the plan", TAG, notes = SCOPE)
        }
    }
}

/** A preview never changes for a day on the server, so the phone may keep it as long. */
private val SAMPLE_CACHE = CacheControl.MaxAge(maxAgeSeconds = 86_400, visibility = CacheControl.Visibility.Private)

/** Audio as the body itself; a failure as the envelope. */
private suspend fun ApplicationCall.respondAudio(result: Resource<SpokenAudio>) {
    when (result) {
        is Resource.Success -> {
            attributes.put(EnvelopeSent, true)
            respondBytes(result.data.bytes, ContentType.parse(result.data.contentType))
        }

        is Resource.Error -> {
            respondError(result.error)
        }
    }
}
