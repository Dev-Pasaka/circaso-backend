package com.circaso.gateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorPagesTest {
    private fun pages(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { routing { errorPageRoutes() } }
            block()
        }

    @Test
    fun `the 403 page keeps the status and shows the caller their own details`() =
        pages {
            val response =
                client.get("/_errors/403") {
                    header("CF-Connecting-IP", "203.0.113.9")
                    header("CF-IPCountry", "KE")
                    header("CF-Ray", "8a1b2c3d4e5f-NBO")
                    header("X-Original-Method", "GET")
                    header("X-Original-URI", "/docs")
                    header(HttpHeaders.UserAgent, "Mozilla/5.0 test")
                }
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.Forbidden, response.status)
            for (shown in listOf("203.0.113.9", "KE", "8a1b2c3d4e5f-NBO", "GET /docs", "Mozilla/5.0 test")) {
                assertTrue(shown in body, "the page should show $shown")
            }
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertTrue(response.headers["Content-Security-Policy"]!!.startsWith("default-src 'none'"))
            assertFalse("{{" in body, "no placeholder should be left in the page")
        }

    @Test
    fun `what the caller sent is escaped and cannot become markup or a placeholder`() =
        pages {
            val body =
                client
                    .get("/_errors/403") {
                        header(HttpHeaders.UserAgent, "<script>alert(1)</script> {{ADDRESS}}")
                        header("X-Original-URI", "/x?\"><img src=x onerror=1>")
                    }.bodyAsText()
            assertFalse("<script>alert(1)" in body)
            assertFalse("<img src=x" in body)
            assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in body)
        }

    @Test
    fun `every status with a page answers with that status`() =
        pages {
            for ((code, status) in mapOf(
                404 to HttpStatusCode.NotFound,
                413 to HttpStatusCode.PayloadTooLarge,
                429 to HttpStatusCode.TooManyRequests,
            )) {
                val response = client.get("/_errors/$code")
                assertEquals(status, response.status)
                assertTrue("Circaso" in response.bodyAsText())
            }
        }

    @Test
    fun `a status with no page is not found`() =
        pages {
            assertEquals(HttpStatusCode.NotFound, client.get("/_errors/418").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/_errors/abc").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/_errors/502").status)
        }
}
