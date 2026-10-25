package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.TextToSpeech
import com.circaso.shared.Resource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Asha's voice: Deepgram Aura 2 (`@cf/deepgram/aura-2-en` by default) on its model route `run/<model>`, answering MP3.
 * Aura speaks English only, so a reply in another language is refused here and the phone speaks it with its own voice
 * (decision 2026-10-25). Neither the text nor the audio is logged.
 *
 * Every Deepgram call opts out of Deepgram's model improvement programme with `mip_opt_out` (assistant → Integrations
 * used). Workers AI's published input schema for Aura 2 doesn't list that field (Nova-3 and Flux do), so whether it
 * is accepted and passed on is checked in the smoke test; [mipOptOut] turns it off without a release if Workers AI
 * refuses the request because of it.
 *
 * TODO(decision: does Workers AI accept and honour mip_opt_out for Aura 2, and does the programme apply at all to
 *  Deepgram models hosted by Cloudflare?)
 */
class WorkersAiTextToSpeech(
    private val gateway: WorkersAiGateway,
    private val model: String,
    private val mipOptOut: Boolean = true,
) : TextToSpeech {
    private val log = LoggerFactory.getLogger(WorkersAiTextToSpeech::class.java)

    override suspend fun speak(text: String, language: String?): Resource<SpokenAudio> {
        if (!speaksEnglish(language)) {
            return Resource.error(IntegrationError.Rejected("Aura speaks English only; the phone speaks '$language'."))
        }
        val body =
            buildJsonObject {
                put("text", text)
                if (mipOptOut) put("mip_opt_out", true)
            }

        return when (val reply = gateway.post("run/$model", "speak", body)) {
            is Resource.Success -> read(reply.data)
            is Resource.Error -> reply
        }
    }

    /**
     * Audio comes back as the body itself (`audio/mpeg`). Some Workers AI routes wrap binary output as base64 in
     * JSON (`{"result": {"audio": "…"}}`), so that shape is read too.
     */
    private fun read(reply: GatewayReply): Resource<SpokenAudio> {
        val type =
            reply.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        val audio =
            when {
                type == "application/json" -> fromJson(reply)
                reply.bytes.isNotEmpty() -> SpokenAudio(reply.bytes, type?.takeIf { it.startsWith("audio/") } ?: MP3)
                else -> null
            }
        if (audio == null) {
            log.warn("workers-ai speech had no audio")
            return Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
        return Resource.success(audio)
    }

    private fun fromJson(reply: GatewayReply): SpokenAudio? {
        val encoded = reply.json()?.text("audio") ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        return SpokenAudio(bytes, MP3).takeIf { bytes.isNotEmpty() }
    }

    private fun speaksEnglish(language: String?) = language == null || language.lowercase().startsWith("en")

    private companion object {
        const val MP3 = "audio/mpeg"
    }
}
