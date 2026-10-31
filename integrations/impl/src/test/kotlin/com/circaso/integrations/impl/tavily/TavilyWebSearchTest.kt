package com.circaso.integrations.impl.tavily

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebResult
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.dev.FakeWebSearch
import com.circaso.shared.Resource
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tavily over MockEngine, and the dev fake. */
class TavilyWebSearchTest {
    private var status = HttpStatusCode.OK
    private var down = false
    private val sent = mutableListOf<String>()
    private var body =
        """
        {"query":"usdc","results":[
          {"title":"What is USDC?","url":"https://www.circle.com/usdc",
           "content":"USDC is a digital dollar &amp; it&#39;s backed 1:1.","score":0.9,"extra":1},
          {"title":"No link","url":"","content":"dropped"},
          {"title":"M-Pesa fees","url":"https://www.safaricom.co.ke/fees","content":"Tariffs   by band."}
        ],"response_time":0.5}
        """.trimIndent()
    private val clients =
        FakeHttpClients { request ->
            if (down) throw IOException("down")
            sent += String(request.body.toByteArray())
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    private val search = TavilyWebSearch(clients.named("tavily"), key = "tvly-secret")

    @Test
    fun `asks Tavily with the key as a Bearer token and a capped count, and gives back plain text`() =
        runBlocking<Unit> {
            val result = search.search("  what is usdc  ", 50)

            val request = clients.last
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("api.tavily.com", request.url.host)
            assertEquals("/search", request.url.encodedPath)
            assertEquals("Bearer tvly-secret", request.headers[HttpHeaders.Authorization])
            val json = sent.single()
            assertTrue(""""query":"what is usdc"""" in json, json)
            assertTrue(""""max_results":10""" in json, json)
            assertFalse("tvly-secret" in json, "the key goes in the header only")
            val results = assertIs<Resource.Success<List<WebResult>>>(result).data
            assertEquals(
                listOf("https://www.circle.com/usdc", "https://www.safaricom.co.ke/fees"),
                results.map { it.url },
            )
            assertEquals("USDC is a digital dollar & it's backed 1:1.", results[0].snippet)
            assertEquals("Tariffs by band.", results[1].snippet)
        }

    @Test
    fun `the count is at least one and the results are cut to it`() =
        runBlocking<Unit> {
            val result = search.search("fees", 0)

            assertTrue(""""max_results":1""" in sent.single())
            assertEquals(1, (result as Resource.Success).data.size)
        }

    @Test
    fun `a refusal, an unreadable answer or no answer is unavailable, an empty query is rejected`() =
        runBlocking<Unit> {
            status = HttpStatusCode.TooManyRequests
            assertIs<IntegrationError.Unavailable>((search.search("x", 3) as Resource.Error).error)

            status = HttpStatusCode.OK
            body = "not json"
            assertIs<IntegrationError.Unavailable>((search.search("x", 3) as Resource.Error).error)

            down = true
            assertIs<IntegrationError.Unavailable>((search.search("x", 3) as Resource.Error).error)

            val before = clients.requests.size
            assertIs<IntegrationError.Rejected>((search.search("   ", 3) as Resource.Error).error)
            assertEquals(before, clients.requests.size, "nothing is sent for an empty query")
        }

    @Test
    fun `the fake answers made-up results about the query and records it`() =
        runBlocking<Unit> {
            val fake = FakeWebSearch()

            val results = (fake.search("M-Pesa fees", 1) as Resource.Success).data

            assertEquals(1, results.size)
            assertTrue("M-Pesa fees" in results.single().title)
            assertEquals(listOf("M-Pesa fees"), fake.asked)
            fake.isDown = true
            assertIs<Resource.Error>(fake.search("x", 3))
        }
}
