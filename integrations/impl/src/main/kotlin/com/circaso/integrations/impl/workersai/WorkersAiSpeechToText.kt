package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.Transcript
import com.circaso.shared.Resource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.math.ceil

/**
 * Voice clips to text with Whisper (`@cf/openai/whisper-large-v3-turbo` by default), the one Workers AI model that
 * transcribes Swahili, on its model route `run/<model>`. The clip goes as base64 (Whisper reads the format from the
 * bytes, so the content type isn't sent) with the language hint as Whisper's two-letter code (`sw-KE` becomes `sw`).
 * The length comes from Whisper's own measure of the audio, so usage is billed on what was heard, not on what the
 * phone claimed. Neither the audio nor the transcript is logged.
 */
class WorkersAiSpeechToText(private val gateway: WorkersAiGateway, private val model: String) : SpeechToText {
    private val log = LoggerFactory.getLogger(WorkersAiSpeechToText::class.java)

    override suspend fun transcribe(
        audio: ByteArray,
        contentType: String,
        language: String?,
    ): Resource<Transcript> {
        val body =
            buildJsonObject {
                put("audio", Base64.getEncoder().encodeToString(audio))
                put("task", "transcribe")
                whisperLanguage(language)?.let { put("language", it) }
            }

        return when (val reply = gateway.post("run/$model", "transcribe", body)) {
            is Resource.Success -> read(reply.data)
            is Resource.Error -> reply
        }
    }

    private fun read(reply: GatewayReply): Resource<Transcript> {
        val answer = reply.json()
        val text = answer?.text("text")
        if (answer == null || text == null) {
            log.warn("workers-ai transcription had no text")
            return Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
        return Resource.success(Transcript(text.trim(), seconds(answer)))
    }

    /** Whisper's measured duration, else the end of the last segment; at least one second, rounded up. */
    private fun seconds(answer: JsonObject): Int {
        val duration = ((answer["transcription_info"] as? JsonObject)?.get("duration") as? JsonPrimitive)?.doubleOrNull
        val lastEnd =
            ((answer["segments"] as? JsonArray)?.lastOrNull() as? JsonObject)
                ?.get("end")
                ?.let { (it as? JsonPrimitive)?.doubleOrNull }
        return ceil(duration ?: lastEnd ?: 0.0).toInt().coerceAtLeast(1)
    }

    private fun whisperLanguage(language: String?): String? =
        language
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
}
