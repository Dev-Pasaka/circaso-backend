package com.circaso.integrations.api

import com.circaso.shared.Resource

/**
 * The model behind Asha, in our language: a conversation and the tools she may call go in, text or tool calls come
 * out. Prompts, tools and what to do with an answer belong to the assistant; the vendor only runs the model.
 */
interface LanguageModel {
    suspend fun complete(request: ModelRequest): Resource<ModelResponse>
}

data class ModelRequest(val system: String, val messages: List<ModelMessage>, val tools: List<ToolSpec> = emptyList())

sealed interface ModelMessage {
    data class User(val text: String) : ModelMessage

    data class Assistant(val text: String?, val toolCalls: List<ToolCall> = emptyList()) : ModelMessage

    data class ToolResult(val callId: String, val name: String, val content: String) : ModelMessage
}

/** [parametersJsonSchema] is a JSON Schema object describing the arguments. */
data class ToolSpec(val name: String, val description: String, val parametersJsonSchema: String)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class ModelResponse(val text: String?, val toolCalls: List<ToolCall>, val usage: ModelUsage)

data class ModelUsage(val inputTokens: Int, val outputTokens: Int)

interface SpeechToText {
    /** Turns a short clip into text; the audio is not kept. */
    suspend fun transcribe(
        audio: ByteArray,
        contentType: String,
        language: String? = null,
    ): Resource<Transcript>
}

data class Transcript(val text: String, val seconds: Int)

interface TextToSpeech {
    /** A [language] the voice can't speak fails with [IntegrationError.UnsupportedLanguage], before any vendor call. */
    suspend fun speak(text: String, language: String? = null): Resource<SpokenAudio>
}

class SpokenAudio(val bytes: ByteArray, val contentType: String)

/**
 * Reads a photo for Asha, in our language: an image and what to look for go in, text comes out. The assistant
 * puts that text into the conversation for [LanguageModel], so the text model never needs to see images.
 */
interface VisionModel {
    suspend fun describe(image: ImageInput, instruction: String): Resource<VisionAnswer>
}

/** A photo as its bytes: the assistant reads it from storage itself, so no provider ever fetches our files. */
class ImageInput(val bytes: ByteArray, val contentType: String)

data class VisionAnswer(val text: String, val usage: ModelUsage)
