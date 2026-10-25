package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.ImageInput
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.workersai.WorkersAiKit.json
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkersAiVisionModelTest {
    private var reply =
        """
        {"choices":[{"index":0,"message":{"role":"assistant","content":"A Naivas receipt for KES 1,250."}}],
         "usage":{"prompt_tokens":900,"completion_tokens":12}}
        """.trimIndent()
    private val clients = FakeHttpClients { _ -> json(reply) }
    private val vision = WorkersAiVisionModel(WorkersAiKit.gateway(clients), "@cf/meta/llama-4-scout-17b-16e-instruct")

    private fun sentParts(): List<JsonObject> {
        val message =
            clients.last
                .json()["messages"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        return message["content"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun `a photo's bytes go as a data URL beside the instruction, and its reading comes back`() =
        runBlocking<Unit> {
            val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)

            val result = vision.describe(ImageInput(jpeg, "image/jpeg"), "What does this receipt say?")

            val answer = (result as Resource.Success).data
            assertEquals("A Naivas receipt for KES 1,250.", answer.text)
            assertEquals(ModelUsage(900, 12), answer.usage)
            val body = clients.last.json()
            assertEquals("/client/v4/accounts/acc123/ai/v1/chat/completions", clients.last.url.encodedPath)
            assertEquals("@cf/meta/llama-4-scout-17b-16e-instruct", body["model"]!!.jsonPrimitive.content)
            assertEquals(1024, body["max_tokens"]!!.jsonPrimitive.int)
            val (text, image) = sentParts()
            assertEquals("text", text["type"]!!.jsonPrimitive.content)
            assertEquals("What does this receipt say?", text["text"]!!.jsonPrimitive.content)
            assertEquals("image_url", image["type"]!!.jsonPrimitive.content)
            assertEquals(
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg),
                image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
            )
        }

    @Test
    fun `an answer with no text is Unavailable`() =
        runBlocking<Unit> {
            reply = """{"choices":[{"index":0,"message":{"role":"assistant","content":""}}]}"""

            val result = vision.describe(ImageInput(byteArrayOf(1, 2), "image/png"), "Describe it")

            assertIs<IntegrationError.Unavailable>((result as Resource.Error).error)
        }
}
