package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.ToolSpec
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.workersai.WorkersAiKit.json
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WorkersAiLanguageModelTest {
    private val replies = ArrayDeque<Pair<String, Int>>()
    private val clients =
        FakeHttpClients { _ ->
            val (body, status) = replies.removeFirst()
            json(body, status)
        }
    private val model =
        WorkersAiLanguageModel(WorkersAiKit.gateway(clients), "@cf/moonshotai/kimi-k2.6", maxOutputTokens = 4096)

    private val searchContacts =
        ToolSpec(
            name = "searchContacts",
            description = "Find a contact by name",
            parametersJsonSchema =
                """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
        )

    private fun completion(
        message: String,
        prompt: Int = 120,
        completion: Int = 18,
    ) = """
        {"id":"chatcmpl-1","object":"chat.completion","created":1761400000,"model":"@cf/moonshotai/kimi-k2.6",
         "choices":[{"index":0,"message":$message,"finish_reason":"stop","logprobs":null}],
         "usage":{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":${prompt + completion}}}
        """.trimIndent()

    @Test
    fun `a plain question goes as OpenAI chat messages to the configured model`() =
        runBlocking<Unit> {
            replies += completion("""{"role":"assistant","content":"Hi Amina!","refusal":null}""") to 200

            val result =
                model.complete(ModelRequest("You are Asha.", listOf(ModelMessage.User("Hello")))) as Resource.Success

            assertEquals(ModelResponse("Hi Amina!", emptyList(), ModelUsage(120, 18)), result.data)
            val request = clients.last
            assertEquals("/client/v4/accounts/acc123/ai/v1/chat/completions", request.url.encodedPath)
            val body = request.json()
            assertEquals("@cf/moonshotai/kimi-k2.6", body["model"]!!.jsonPrimitive.content)
            assertEquals(4096, body["max_completion_tokens"]!!.jsonPrimitive.int, "the answer is always capped")
            val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
            assertEquals("You are Asha.", messages[0]["content"]!!.jsonPrimitive.content)
            assertEquals("Hello", messages[1]["content"]!!.jsonPrimitive.content)
            assertNull(body["tools"], "no tools, no tools field")
        }

    @Test
    fun `a reply with neither text nor a tool call is Unavailable`() =
        runBlocking<Unit> {
            val request = ModelRequest("s", listOf(ModelMessage.User("u")))
            replies +=
                """
                {"choices":[{"index":0,"message":{"role":"assistant","content":null},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":50,"completion_tokens":4096}}
                """.trimIndent() to 200
            assertIs<IntegrationError.Unavailable>((model.complete(request) as Resource.Error).error)

            replies += completion("""{"role":"assistant","content":"  ","tool_calls":[]}""") to 200
            assertIs<IntegrationError.Unavailable>((model.complete(request) as Resource.Error).error)
        }

    @Test
    fun `a tool call round trip reads back to the model exactly as it happened`() =
        runBlocking<Unit> {
            // 1. The model asks for a tool.
            replies +=
                completion(
                    """
                    {"role":"assistant","content":null,"refusal":null,
                     "tool_calls":[{"id":"call_7","type":"function",
                                    "function":{"name":"searchContacts","arguments":"{\"query\":\"Mum\"}"}}]}
                    """.trimIndent(),
                ) to 200
            val ask = ModelRequest("You are Asha.", listOf(ModelMessage.User("Send Mum 200")), listOf(searchContacts))

            val first = (model.complete(ask) as Resource.Success).data

            assertNull(first.text)
            assertEquals(listOf(ToolCall("call_7", "searchContacts", """{"query":"Mum"}""")), first.toolCalls)
            val tool =
                clients.last
                    .json()["tools"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("function", tool["type"]!!.jsonPrimitive.content)
            val function = tool["function"]!!.jsonObject
            assertEquals("searchContacts", function["name"]!!.jsonPrimitive.content)
            assertEquals("Find a contact by name", function["description"]!!.jsonPrimitive.content)
            assertEquals("object", function["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)

            // 2. The assistant runs the tool and sends its result back with the call.
            replies += completion("""{"role":"assistant","content":"Mum is Jane Wanjiru.","refusal":null}""") to 200
            val followUp =
                ask.copy(
                    messages =
                        ask.messages +
                            ModelMessage.Assistant(null, first.toolCalls) +
                            ModelMessage.ToolResult("call_7", "searchContacts", """[{"name":"Jane Wanjiru"}]"""),
                )

            val second = (model.complete(followUp) as Resource.Success).data

            assertEquals("Mum is Jane Wanjiru.", second.text)
            val messages =
                clients.last
                    .json()["messages"]!!
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.role() })
            val assistant = messages[2]
            assertEquals(JsonNull, assistant["content"])
            val call = assistant["tool_calls"]!!.jsonArray.single().jsonObject
            assertEquals("call_7", call["id"]!!.jsonPrimitive.content)
            assertEquals("function", call["type"]!!.jsonPrimitive.content)
            assertEquals(
                """{"query":"Mum"}""",
                call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content,
                "arguments go back as the JSON-encoded string the model sent",
            )
            val result = messages[3]
            assertEquals("call_7", result["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("searchContacts", result["name"]!!.jsonPrimitive.content)
            assertEquals("""[{"name":"Jane Wanjiru"}]""", result["content"]!!.jsonPrimitive.content)
        }

    @Test
    fun `arguments sent as an object instead of a string still arrive as JSON text`() =
        runBlocking<Unit> {
            replies +=
                completion(
                    """
                    {"role":"assistant","content":null,
                     "tool_calls":[{"id":"c1","type":"function",
                                    "function":{"name":"searchContacts","arguments":{"query":"Mum"}}}]}
                    """.trimIndent(),
                ) to 200

            val response = (model.complete(ModelRequest("s", listOf(ModelMessage.User("u")))) as Resource.Success).data

            assertEquals("""{"query":"Mum"}""", response.toolCalls.single().argumentsJson)
        }

    @Test
    fun `missing usage counts as zero tokens`() =
        runBlocking<Unit> {
            replies += """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}""" to 200

            val response = (model.complete(ModelRequest("s", listOf(ModelMessage.User("u")))) as Resource.Success).data

            assertEquals(ModelUsage(0, 0), response.usage)
        }

    @Test
    fun `a tool whose schema isn't JSON is refused before calling`() =
        runBlocking<Unit> {
            val broken = searchContacts.copy(parametersJsonSchema = "{not json")

            val result = model.complete(ModelRequest("s", listOf(ModelMessage.User("u")), listOf(broken)))

            assertIs<IntegrationError.Rejected>((result as Resource.Error).error)
            assertEquals(0, clients.requests.size)
        }

    @Test
    fun `an answer without a message, or a vendor error, fails in our language`() =
        runBlocking<Unit> {
            val request = ModelRequest("s", listOf(ModelMessage.User("u")))
            replies += """{"choices":[]}""" to 200
            assertIs<IntegrationError.Unavailable>((model.complete(request) as Resource.Error).error)
            replies += """{"errors":[{"code":3040,"message":"Capacity temporarily exceeded"}]}""" to 429
            assertIs<IntegrationError.Unavailable>((model.complete(request) as Resource.Error).error)
            replies += """{"errors":[{"code":5006,"message":"bad input"}]}""" to 400
            assertIs<IntegrationError.Rejected>((model.complete(request) as Resource.Error).error)
        }

    private fun JsonObject.role() = this["role"]!!.jsonPrimitive.content
}
