package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.Transcript
import com.circaso.shared.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.math.ceil

/**
 * Voice clips to text with Whisper (`@cf/openai/whisper-large-v3-turbo` by default), the one Workers AI model that
 * transcribes Swahili, on its model route `run/<model>`. The clip goes as base64 (Whisper reads the format from the
 * bytes, so the content type isn't sent) with the language hint as Whisper's code (`sw-KE` becomes `sw`). Clips keep
 * the shared retry rules: a retried clip costs seconds, not a user's minute.
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
        val request =
            WhisperRequest(
                audio = Base64.getEncoder().encodeToString(audio),
                task = "transcribe",
                language = whisperLanguage(language),
            )
        val body = WorkersAiJson.encodeToString(WhisperRequest.serializer(), request)

        return when (val reply = gateway.post("run/$model", "transcribe", body)) {
            is Resource.Success -> read(reply.data)
            is Resource.Error -> reply
        }
    }

    private fun read(reply: GatewayReply): Resource<Transcript> {
        val answer = reply.decode(WhisperResult.serializer())
        val text = answer?.text
        if (text == null) {
            log.warn("workers-ai transcription had no text")
            return Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
        return Resource.success(Transcript(text.trim(), seconds(answer)))
    }

    /** Whisper's measured duration, else the end of the last segment; at least one second, rounded up. */
    private fun seconds(answer: WhisperResult): Int {
        val measured = answer.transcriptionInfo?.duration ?: answer.segments.lastOrNull()?.end
        return ceil(measured ?: 0.0).toInt().coerceAtLeast(1)
    }

    /**
     * The hint arrives as the phone's `Accept-Language`: its first range, without weight or region, if it is a plain
     * language code (`sw,en;q=0.9` → `sw`). Anything else (`*`, garbage) sends no hint and Whisper detects the language.
     */
    private fun whisperLanguage(acceptLanguage: String?): String? =
        acceptLanguage
            ?.substringBefore(',')
            ?.substringBefore(';')
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.trim()
            ?.lowercase()
            ?.takeIf { LANGUAGE_CODE.matches(it) }

    private companion object {
        val LANGUAGE_CODE = Regex("[a-z]{2,3}")
    }
}

/** Whisper's input; without a [language] hint (left out), Whisper detects it. */
@Serializable
private class WhisperRequest(val audio: String, val task: String, val language: String? = null)

@Serializable
private class WhisperResult(
    val text: String? = null,
    @SerialName("transcription_info") val transcriptionInfo: TranscriptionInfo? = null,
    val segments: List<WhisperSegment> = emptyList(),
)

@Serializable
private class TranscriptionInfo(val duration: Double? = null)

@Serializable
private class WhisperSegment(val end: Double? = null)
