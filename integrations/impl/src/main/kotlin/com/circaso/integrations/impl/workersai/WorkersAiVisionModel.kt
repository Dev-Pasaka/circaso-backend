package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.ImageInput
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.VisionAnswer
import com.circaso.integrations.api.VisionModel
import com.circaso.shared.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Reads a photo with the image model (`@cf/meta/llama-4-scout-17b-16e-instruct` by default) on the OpenAI-compatible
 * chat completions route: one user message holding the instruction as a `text` part and the photo as an `image_url`
 * part with a `data:` URL. Like the text model it is never retried, so a photo turn waits at most the client's 60 s.
 * Neither the photo nor the answer is logged.
 */
class WorkersAiVisionModel(private val gateway: WorkersAiGateway, private val model: String) : VisionModel {
    private val log = LoggerFactory.getLogger(WorkersAiVisionModel::class.java)

    override suspend fun describe(image: ImageInput, instruction: String): Resource<VisionAnswer> {
        val request =
            VisionRequest(
                model = model,
                // Llama 4 Scout stops at 256 tokens unless told otherwise, which cuts a receipt short.
                maxTokens = MAX_TOKENS,
                messages =
                    listOf(
                        VisionMessage(
                            role = "user",
                            content =
                                listOf(
                                    VisionPart(type = "text", text = instruction),
                                    VisionPart(type = "image_url", imageUrl = ImageUrl(dataUrl(image))),
                                ),
                        ),
                    ),
            )
        val body = WorkersAiJson.encodeToString(VisionRequest.serializer(), request)

        return when (val reply = gateway.post(CHAT_COMPLETIONS, "describe_image", body, retried = false)) {
            is Resource.Success -> read(reply.data)
            is Resource.Error -> reply
        }
    }

    private fun read(reply: GatewayReply): Resource<VisionAnswer> {
        val answer = reply.decode(ChatCompletion.serializer())
        val choice = answer?.choices?.firstOrNull()
        val text = choice?.message?.content
        if (answer == null || text.isNullOrBlank()) {
            log.warn("workers-ai image answer had no text: finish_reason={}", choice?.finishReason)
            return Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
        return Resource.success(VisionAnswer(text, answer.usage?.toModel() ?: ModelUsage(0, 0)))
    }

    private fun dataUrl(image: ImageInput) =
        "data:${image.contentType};base64,${Base64.getEncoder().encodeToString(image.bytes)}"

    private companion object {
        const val MAX_TOKENS = 1024
    }
}

/** One user message with the instruction and the photo as its parts (OpenAI's multi-part content). */
@Serializable
private class VisionRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<VisionMessage>,
)

@Serializable
private class VisionMessage(val role: String, val content: List<VisionPart>)

/** A `text` part or an `image_url` part; the other field is left out. */
@Serializable
private class VisionPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null,
)

@Serializable
private class ImageUrl(val url: String)
