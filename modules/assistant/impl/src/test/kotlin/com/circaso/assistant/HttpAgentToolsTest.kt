package com.circaso.assistant

import com.circaso.assistant.data.tools.HttpAgentTools
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Asha's contact tools against users' agent routes, typed both ways (decision 2026-10-27). */
class HttpAgentToolsTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var answer =
        """{"httpStatusCode":200,"status":true,"message":"Success","requestId":"r1",""" +
            """"data":{"contacts":[{"userId":"u1","displayName":"Mum Wanjiku","handle":"mum","extra":1}]}}"""
    private var status = HttpStatusCode.OK
    private val tools =
        HttpAgentTools(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
            "http://localhost:8081",
        )

    @Test
    fun `a search reads its query from the model's arguments and gives the model only the route's data`() =
        runTest {
            val result = tools.call("searchContacts", """{"query":"Mum"}""", "agent-tok")

            val request = requests.single()
            assertEquals("/v1/agent/users/contacts/search", request.url.encodedPath)
            assertEquals("Mum", request.url.parameters["q"])
            assertEquals("5", request.url.parameters["limit"])
            assertEquals("Bearer agent-tok", request.headers[HttpHeaders.Authorization])
            assertEquals(
                """{"contacts":[{"userId":"u1","displayName":"Mum Wanjiku","handle":"mum"}],"nextCursor":null}""",
                (result as Resource.Success).data,
                "the envelope, and anything the route adds, never reach the model",
            )
        }

    @Test
    fun `a list keeps its limit within bounds, and arguments that don't read fall back to the defaults`() =
        runTest {
            tools.call("listContacts", """{"limit":500}""", "agent-tok")
            tools.call("listContacts", """not json""", "agent-tok")
            tools.call("listContacts", """{"limit":"many"}""", "agent-tok")

            assertEquals(listOf("50", "20", "20"), requests.map { it.url.parameters["limit"] })
        }

    @Test
    fun `an answer that isn't the route's shape is an error, not an empty success`() =
        runTest {
            answer = """{"httpStatusCode":200,"status":true,"data":{"contacts":"not a list"}}"""

            val result = tools.call("searchContacts", """{"query":"x"}""", "agent-tok")

            assertIs<Resource.Error>(result)
        }

    @Test
    fun `a refused lookup is an error for the model to read`() =
        runTest {
            status = HttpStatusCode.Forbidden
            answer = """{"httpStatusCode":403,"status":false,"code":"auth.scope_insufficient"}"""

            val result = tools.call("searchContacts", """{"query":"x"}""", "agent-tok")

            assertIs<Resource.Error>(result)
        }
}
