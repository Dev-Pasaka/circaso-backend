package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.ImageInput
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.VisionAnswer
import com.circaso.integrations.api.VisionModel
import com.circaso.shared.Resource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
        val body =
            buildJsonObject {
                put("model", model)
                // Llama 4 Scout stops at 256 tokens unless told otherwise, which cuts a receipt short.
                put("max_tokens", MAX_TOKENS)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", instruction)
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", dataUrl(image)) }
                                    },
                                )
                            }
                        },
                    )
                }
            }

        return when (val reply = gateway.post(CHAT_COMPLETIONS, "describe_image", body, retried = false)) {
            is Resource.Success -> read(reply.data)
            is Resource.Error -> reply
        }
    }

    private fun read(reply: GatewayReply): Resource<VisionAnswer> {
        val answer = reply.json()
        val text = answer?.firstMessage()?.contentText()
        if (answer == null || text.isNullOrBlank()) {
            log.warn("workers-ai image answer had no text: finish_reason={}", answer?.finishReason())
            return Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
        return Resource.success(VisionAnswer(text, answer.usage()))
    }

    private fun dataUrl(image: ImageInput) =
        "data:${image.contentType};base64,${Base64.getEncoder().encodeToString(image.bytes)}"

    private companion object {
        const val MAX_TOKENS = 1024
    }
}
