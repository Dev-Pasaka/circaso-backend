package com.circaso.assistant.eval

import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.ToolCall
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stand-in models that prove the harness before any paid call: through the real agent loop, the [OracleModel] (each
 * case's ideal calls and answer) must pass every case, while [NullModel] (a polite non-answer) and [GuessingModel]
 * (no tools, a confident made-up figure) must fail, the guesser on the invented-number check.
 */
object StubModels {
    fun of(name: String): LanguageModel? =
        when (name) {
            "oracle" -> OracleModel(EvalCases.all)
            "null" -> NullModel
            "guesser" -> GuessingModel
            else -> null
        }
}

private val usage = ModelUsage(1_000, 50)

/** The case's question: the first thing the user said. */
private fun questionOf(request: ModelRequest) =
    request.messages
        .filterIsInstance<ModelMessage.User>()
        .firstOrNull()
        ?.text
        .orEmpty()

class OracleModel(cases: List<EvalCase>) : LanguageModel {
    private val byQuestion = cases.associateBy { it.question }

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        val case =
            byQuestion[questionOf(request)]
                ?: return Resource.success(ModelResponse("I don't know that case.", emptyList(), usage))
        val results = request.messages.filterIsInstance<ModelMessage.ToolResult>()
        val next = results.size
        return if (next < case.oracle.calls.size) {
            val (name, args) = case.oracle.calls[next]
            Resource.success(ModelResponse(null, listOf(ToolCall("oracle_$next", name, fill(args, results))), usage))
        } else {
            Resource.success(ModelResponse(fill(case.oracle.answer, results), emptyList(), usage))
        }
    }

    /** `{tool.path}` read from that tool's latest result. */
    private fun fill(template: String, results: List<ModelMessage.ToolResult>): String =
        Regex("""\{(\w+)\.([\w.]+)}""").replace(template) { m ->
            val (tool, path) = m.destructured
            val raw = results.lastOrNull { it.name == tool }?.content ?: error("oracle: no $tool result for ${m.value}")
            val json = CircasoJson.parseToJsonElement(raw.removePrefix("<untrusted>").removeSuffix("</untrusted>"))
            path
                .split('.')
                .fold<String, JsonElement>(json) { node, key ->
                    when (node) {
                        is JsonArray -> node[key.toInt()]
                        is JsonObject -> node[key] ?: error("oracle: no $key in $tool result for ${m.value}")
                        else -> error("oracle: can't read $key in $tool result")
                    }
                }.let { (it as JsonPrimitive).content }
        }
}

/** Always a polite non-answer: what an eval must not score as right. */
object NullModel : LanguageModel {
    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> =
        Resource.success(ModelResponse("Sorry, I can't help with that right now.", emptyList(), usage))
}

/** Never calls a tool and states a figure it made up: the invented-number check must catch it every time. */
object GuessingModel : LanguageModel {
    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> =
        Resource.success(
            ModelResponse(
                "That comes to about KES 4,317.40, going by roughly 131.6 shillings to the dollar.",
                emptyList(),
                usage,
            ),
        )
}
