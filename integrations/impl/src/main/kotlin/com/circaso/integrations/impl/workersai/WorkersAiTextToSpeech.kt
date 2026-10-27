package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.TextToSpeech
import com.circaso.shared.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Asha's voice: Deepgram Aura 2 (`@cf/deepgram/aura-2-en` by default) on its model route `run/<model>`, answering MP3.
 * Aura speaks English only, so a reply in another language is `UnsupportedLanguage` without calling Workers AI, and
 * the phone speaks it with its own voice (decision 2026-10-25). Neither the text nor the audio is logged.
 *
 * Every Deepgram call sends `mip_opt_out=true`, keeping the audio out of Deepgram's model improvement programme; it is
 * a privacy guarantee, so there is no switch (decision 2026-10-25). Workers AI's published input schema for Aura 2
 * doesn't list the field (Nova-3 and Flux do): Workers AI accepts it for Aura 2, here and on the live socket, but
 * likely ignores it, which is accepted, relying on Cloudflare's terms for the models it hosts (decision 2026-10-27).
 */
class WorkersAiTextToSpeech(private val gateway: WorkersAiGateway, private val model: String) : TextToSpeech {
    private val log = LoggerFactory.getLogger(WorkersAiTextToSpeech::class.java)

    override suspend fun speak(text: String, language: String?): Resource<SpokenAudio> {
        if (!speaksEnglish(language)) return Resource.error(IntegrationError.UnsupportedLanguage(language.orEmpty()))
        val body = WorkersAiJson.encodeToString(AuraClipRequest.serializer(), AuraClipRequest(text, mipOptOut = true))

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
        val encoded = reply.decode(AuraClip.serializer())?.audio ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        return SpokenAudio(bytes, MP3).takeIf { bytes.isNotEmpty() }
    }

    private fun speaksEnglish(language: String?) = language == null || language.lowercase().startsWith("en")

    private companion object {
        const val MP3 = "audio/mpeg"
    }
}

@Serializable
private class AuraClipRequest(
    val text: String,
    @SerialName("mip_opt_out") val mipOptOut: Boolean,
)

/** The JSON shape of a clip, base64 audio, when the route wraps it rather than answering with the bytes. */
@Serializable
private class AuraClip(val audio: String? = null)
