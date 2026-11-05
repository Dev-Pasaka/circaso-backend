package com.circaso.integrations.impl.brave

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebResult
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.shared.Resource
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Brave's Search API over MockEngine. */
class BraveWebSearchTest {
    private var status = HttpStatusCode.OK
    private var down = false
    private var body =
        """
        {"type":"search","query":{"original":"usdc"},"web":{"type":"search","results":[
          {"title":"What is <strong>USDC</strong>?","url":"https://www.circle.com/usdc",
           "description":"USDC is a digital dollar &amp; it&#x27;s backed 1:1.","page_age":"2026-10-30T08:15:00",
           "age":"6 days ago","extra":1},
          {"title":"No link","url":"","description":"dropped"},
          {"title":"M-Pesa fees","url":"https://www.safaricom.co.ke/fees","description":"Tariffs   by band."}
        ]}}
        """.trimIndent()
    private val clients =
        FakeHttpClients { _ ->
            if (down) throw IOException("down")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    private val search = BraveWebSearch(clients.named("brave"), key = "brave-secret")

    @Test
    fun `asks Brave with the key in its header and a capped count, and gives back plain text with dates`() =
        runBlocking<Unit> {
            val result = search.search("  what is usdc  ", 50)

            val request = clients.last
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("api.search.brave.com", request.url.host)
            assertEquals("/res/v1/web/search", request.url.encodedPath)
            assertEquals("brave-secret", request.headers["X-Subscription-Token"])
            assertEquals("what is usdc", request.url.parameters["q"])
            assertEquals("10", request.url.parameters["count"])
            assertEquals("false", request.url.parameters["text_decorations"])
            assertTrue("brave-secret" !in request.url.toString(), "the key goes in the header only")
            val results = assertIs<Resource.Success<List<WebResult>>>(result).data
            assertEquals(
                listOf("https://www.circle.com/usdc", "https://www.safaricom.co.ke/fees"),
                results.map { it.url },
            )
            assertEquals("What is USDC?", results[0].title)
            assertEquals("USDC is a digital dollar & it's backed 1:1.", results[0].snippet)
            assertEquals(Instant.parse("2026-10-30T08:15:00Z"), results[0].publishedAt)
            assertEquals("Tariffs by band.", results[1].snippet)
            assertNull(results[1].publishedAt)
        }

    @Test
    fun `the count is at least one and the results are cut to it`() =
        runBlocking<Unit> {
            val result = search.search("fees", 0)

            assertEquals("1", clients.last.url.parameters["count"])
            assertEquals(1, (result as Resource.Success).data.size)
        }

    @Test
    fun `a query Brave won't run is rejected, a refusal or an unreadable answer is unavailable`() =
        runBlocking<Unit> {
            status = HttpStatusCode.UnprocessableEntity
            assertIs<IntegrationError.Rejected>((search.search("x", 3) as Resource.Error).error)

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
    fun `no web block is no results, and a page age is read as UTC or left out`() =
        runBlocking<Unit> {
            body = """{"type":"search"}"""
            assertEquals(emptyList(), (search.search("x", 3) as Resource.Success).data)

            assertEquals(Instant.parse("2026-10-30T00:00:00Z"), BraveWebSearch.publishedAt("2026-10-30"))
            assertEquals(Instant.parse("2026-10-30T08:00:00Z"), BraveWebSearch.publishedAt("2026-10-30T08:00:00Z"))
            assertNull(BraveWebSearch.publishedAt("6 days ago"))
            assertNull(BraveWebSearch.publishedAt(null))
        }
}
