package com.circaso.share

import com.circaso.share.domain.models.CreateShareCommand
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.support.ShareConfig
import com.circaso.share.presentation.controllers.sharePageController
import com.circaso.share.presentation.mappers.Platform
import com.circaso.share.presentation.mappers.SharePages
import com.circaso.shared.Resource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The public page and the app association files, over HTTP as a browser and a link preview see them. */
class SharePageTest {
    private fun ShareHarness.make(
        kind: ShareKind,
        showNames: Boolean = true,
        currency: String? = null,
    ): ShareLink {
        val command =
            CreateShareCommand(
                wanjiru,
                kind,
                transferId = if (kind == ShareKind.RECEIPT) payment() else null,
                chatId = if (kind == ShareKind.STATEMENT) group else null,
                blockchain = if (kind == ShareKind.ADDRESS) "BASE-SEPOLIA" else null,
                showNames = showNames,
                currency = currency,
                endsAfterDays = null,
            )
        return (runBlocking { shares.create(command) } as Resource.Success).data
    }

    private fun page(
        h: ShareHarness,
        config: ShareConfig = h.config,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        routing { sharePageController(h.pages, config) }
        block()
    }

    @Test
    fun `a receipt page has the amount, the preview tags and the guards`() {
        val h = ShareHarness()
        val link = h.make(ShareKind.RECEIPT, currency = "KES")
        page(h) {
            val response =
                client.get("/s/${link.code.value}") {
                    header("CF-Connecting-IP", "1.2.3.4")
                    header(HttpHeaders.UserAgent, "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)")
                }
            val html = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
            assertTrue(response.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
            assertTrue("<meta name=\"robots\" content=\"noindex, nofollow\">" in html)
            assertTrue("<meta property=\"og:title\" content=\"Payment receipt\">" in html)
            assertTrue("og:image" in html && "twitter:card" in html)
            assertTrue("KES 1,612.50" in html)
            assertTrue("12.50 USDC" in html)
            assertTrue("Wanjiru" in html && "Juma" in html)
            assertTrue("https://sepolia.basescan.org/tx/0xfeedbeef" in html, "the explorer link")
            assertTrue("circaso://s/${link.code.value}" in html, "Open in Circaso")
            assertTrue("Get Circaso" in html)
            assertFalse("<script" in html)
            assertFalse(link.content.toString().contains("invite-token"))
        }
    }

    @Test
    fun `names the sharer left out are not on the page`() {
        val h = ShareHarness()
        val link = h.make(ShareKind.RECEIPT, showNames = false)
        page(h) {
            val html = client.get("/s/${link.code.value}").bodyAsText()
            assertFalse("Wanjiru" in html || "Juma" in html)
        }
    }

    @Test
    fun `what people typed is escaped`() {
        val h = ShareHarness()
        h.profiles.add(h.wanjiru, "<script>alert(1)</script>\"Wanjiru'", "wanjiru")
        val link = h.make(ShareKind.PAY_ME)
        page(h) {
            val html = client.get("/s/${link.code.value}").bodyAsText()
            assertFalse("<script>alert" in html)
            assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;&quot;Wanjiru&#39;" in html)
        }
    }

    @Test
    fun `a statement, an invite and an address each have their page, and an invite's token is never on it`() {
        val h = ShareHarness()
        val statement = h.make(ShareKind.STATEMENT)
        val invite = h.make(ShareKind.INVITE)
        val address = h.make(ShareKind.ADDRESS)
        page(h) {
            val s = client.get("/s/${statement.code.value}").bodyAsText()
            assertTrue("Holiday Fund" in s && "125.50 USDC" in s && "Food for the trip" in s && "25%" in s)
            val i = client.get("/s/${invite.code.value}").bodyAsText()
            assertTrue("Chat with Wanjiru on Circaso" in i && "@wanjiru" in i)
            assertFalse("invite-token" in i)
            val a = client.get("/s/${address.code.value}").bodyAsText()
            assertTrue("0x1234567890abcdef1234567890abcdef12345678" in a && "Base (testnet)" in a)
        }
    }

    @Test
    fun `an unknown, a turned-off and a throttled link each say so with their status`() {
        val h = ShareHarness()
        val link = h.make(ShareKind.PAY_ME)
        runBlocking { h.shares.revoke(h.wanjiru, link.code) }
        page(h) {
            val missing = client.get("/s/AAAAAAAAAAAA")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue("This link doesn" in missing.bodyAsText())
            val gone = client.get("/s/${link.code.value}")
            assertEquals(HttpStatusCode.Gone, gone.status)
            assertTrue("This link was turned off" in gone.bodyAsText())
            assertFalse("Wanjiru" in gone.bodyAsText())
            h.throttle.max = 0
            val throttled = client.get("/s/${link.code.value}")
            assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
            assertEquals("42", throttled.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun `the association files name the app, and assetlinks waits for a fingerprint`() {
        val h = ShareHarness()
        page(h) {
            val android = client.get("/.well-known/assetlinks.json")
            assertEquals(HttpStatusCode.OK, android.status)
            val statement = (Json.parseToJsonElement(android.bodyAsText()) as JsonArray).single() as JsonObject
            assertTrue(statement.toString().contains("\"package_name\":\"com.circaso.app\""))
            assertTrue(statement.toString().contains("\"sha256_cert_fingerprints\":[\"AA:BB\"]"))
            assertTrue(statement.toString().contains("delegate_permission/common.handle_all_urls"))

            val ios = client.get("/.well-known/apple-app-site-association")
            assertEquals(HttpStatusCode.OK, ios.status)
            assertTrue(ios.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
            val body = ios.bodyAsText()
            assertTrue("\"appIDs\":[\"985W5Z7984.com.circaso.app\"]" in body)
            assertTrue("\"/\":\"/s/*\"" in body && "\"apps\":[]" in body)
        }
        page(h, h.config.copy(androidCertFingerprints = emptyList())) {
            assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/assetlinks.json").status)
        }
    }

    @Test
    fun `Get Circaso goes to the store for the phone`() {
        assertEquals(Platform.ANDROID, Platform.of("Mozilla/5.0 (Linux; Android 14; 23021RAA2Y)"))
        assertEquals(Platform.IOS, Platform.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)"))
        assertEquals(Platform.OTHER, Platform.of("WhatsApp/2.24"))
        val h = ShareHarness()
        assertTrue(h.config.androidStoreUrl in SharePages.missing(h.config, Platform.ANDROID))
    }
}
