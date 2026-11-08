package com.circaso.gateway.access

import com.circaso.gateway.ApiDocsConfig
import com.circaso.gateway.apiDocsRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spec behind the API reference, with the docs site's Cloudflare Access application configured (decision
 * 2026-11-08): only a verified token of that application opens it. A header that merely exists is not enough.
 */
class ApiDocsAccessTest {
    private val access = AccessTokens()
    private val settings = AccessSettings(access.team, access.audience)

    private fun ApplicationTestBuilder.docs(keys: AccessKeys = AccessKeys { listOf(access.jwk) }) {
        application {
            routing {
                get("/v1/ping") { }
                apiDocsRoutes(
                    this@application,
                    ApiDocsConfig(enabled = true, requireOps = true, access = settings),
                    AccessVerifier(settings, keys),
                )
            }
        }
    }

    @Test
    fun `a good token of the docs application opens the spec, from the header or the cookie`() =
        testApplication {
            docs()
            val viaHeader = client.get("/docs/openapi.json") { header("Cf-Access-Jwt-Assertion", access.token()) }
            assertEquals(HttpStatusCode.OK, viaHeader.status)
            assertTrue(viaHeader.bodyAsText().contains("\"openapi\""))
            val viaCookie = client.get("/docs/openapi.json") { header("Cookie", "CF_Authorization=${access.token()}") }
            assertEquals(HttpStatusCode.OK, viaCookie.status)
        }

    @Test
    fun `no token, a made-up one, another application's or an expired one is refused`() =
        testApplication {
            docs()
            assertEquals(HttpStatusCode.Forbidden, client.get("/docs/openapi.json").status)
            for (bad in listOf(
                "fake",
                access.token(audience = "someone-elses-app"),
                access.token(
                    expiresAt =
                        java.time.Instant
                            .now()
                            .minusSeconds(5),
                ),
                AccessTokens(kid = access.kid).token(),
            )) {
                val res = client.get("/docs/openapi.json") { header("Cf-Access-Jwt-Assertion", bad) }
                assertEquals(HttpStatusCode.Forbidden, res.status, bad.take(20))
                assertTrue(!res.bodyAsText().contains("\"openapi\""))
            }
        }

    @Test
    fun `when Cloudflare's keys can't be read the spec stays closed`() =
        testApplication {
            docs(keys = AccessKeys { null })
            val res = client.get("/docs/openapi.json") { header("Cf-Access-Jwt-Assertion", access.token()) }
            assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        }
}
