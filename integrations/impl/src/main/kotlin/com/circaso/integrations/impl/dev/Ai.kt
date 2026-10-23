package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.TextToSpeech
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.Transcript
import com.circaso.shared.Resource
import java.util.UUID

/**
 * Local runs: a stand-in for Asha's model that needs no provider. It answers plainly, and it uses tools so the whole
 * loop can be driven by hand: a message starting with `find <name>` calls `searchContacts`, one starting with
 * `chat <words>` calls the phone's `searchChats`, and the tool's result is read back in the next answer.
 */
class LogLanguageModel : LanguageModel {
    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        val last = request.messages.last()
        val usage = ModelUsage(request.messages.sumOf { it.length() } / 4 + 1, 20)
        return Resource.success(
            when (last) {
                is ModelMessage.User -> userTurn(last.text, usage)
                is ModelMessage.ToolResult -> ModelResponse("I looked that up and got ${last.content.length} characters of results. (This is the development model.)", emptyList(), usage)
                is ModelMessage.Assistant -> ModelResponse(last.text ?: "", emptyList(), usage)
            },
        )
    }

    private fun userTurn(text: String, usage: ModelUsage): ModelResponse {
        // a question asked from another chat arrives with a marker in front
        val words = text.replace(Regex("^\\[Asked from the chat \"[^\"]*\"] "), "").trim()
        return when {
            words.startsWith("find ", ignoreCase = true) ->
                call("searchContacts", """{"query":"${words.substring(5).trim().replace("\"", "")}"}""", usage)
            words.startsWith("chat ", ignoreCase = true) ->
                call("searchChats", """{"query":"${words.substring(5).trim().replace("\"", "")}"}""", usage)
            else -> ModelResponse("Hi, I'm Asha. (This is the development model.) You said: $words", emptyList(), usage)
        }
    }

    private fun call(name: String, arguments: String, usage: ModelUsage) =
        ModelResponse(null, listOf(ToolCall(UUID.randomUUID().toString(), name, arguments)), usage)

    private fun ModelMessage.length() = when (this) {
        is ModelMessage.User -> text.length
        is ModelMessage.Assistant -> text?.length ?: 0
        is ModelMessage.ToolResult -> content.length
    }
}

/** Tests: answers from a script, one entry per call, and records every request. */
class FakeLanguageModel(private val script: MutableList<(ModelRequest) -> Resource<ModelResponse>> = mutableListOf()) : LanguageModel {
    val requests = java.util.concurrent.CopyOnWriteArrayList<ModelRequest>()

    fun then(step: (ModelRequest) -> Resource<ModelResponse>) = apply { script += step }

    fun thenSay(text: String) = then { Resource.success(ModelResponse(text, emptyList(), ModelUsage(10, 5))) }

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        requests += request
        return script.removeFirstOrNull()?.invoke(request) ?: Resource.success(ModelResponse("(nothing scripted)", emptyList(), ModelUsage(1, 1)))
    }
}

/** Local runs and tests: the clip's text is its bytes, so a test can say what was "spoken". */
class FakeSpeechToText : SpeechToText {
    override suspend fun transcribe(audio: ByteArray, contentType: String, language: String?): Resource<Transcript> =
        Resource.success(Transcript(String(audio), seconds = (audio.size / 1000).coerceAtLeast(1)))
}

class FakeTextToSpeech : TextToSpeech {
    override suspend fun speak(text: String, language: String?): Resource<SpokenAudio> = Resource.success(SpokenAudio(text.toByteArray(), "audio/mpeg"))
}
