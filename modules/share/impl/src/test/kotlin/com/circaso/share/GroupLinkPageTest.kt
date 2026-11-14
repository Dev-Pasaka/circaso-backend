package com.circaso.share

import com.circaso.messaging.api.GroupLinkPage
import com.circaso.messaging.api.GroupLinkPhoto
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.share.presentation.controllers.sharePageController
import com.circaso.share.presentation.mappers.toSharedLink
import com.circaso.shared.Resource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A group join link's public page (decision 2026-11-14), over HTTP as a browser and a chat app's link preview see it:
 * the published name, description, photo and member count, the Open Graph tags, the guards, and the same words for a
 * link that was reset, turned off or never made.
 */
class GroupLinkPageTest {
    private val token = GroupLinkToken.new()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private val iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)"
    private val android = "Mozilla/5.0 (Linux; Android 15; Pixel 9)"

    private fun harness(
        description: String? = "Fridays at six.\nBring a book.",
        photo: Boolean = true,
        members: Int = 24,
        name: String = "Book club",
    ) = ShareHarness().also {
        it.groupLinks.pages[token] =
            GroupLinkPage(name, description, photo, photoVersion = 1_700L, memberCount = members)
        if (photo) it.groupLinks.photos[token] = GroupLinkPhoto(jpeg, "image/jpeg")
    }

    private fun page(h: ShareHarness, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            routing { sharePageController(h.pages, h.config) }
            block()
        }

    @Test
    fun `the page shows the published name, description, photo and member count, with the guards`() {
        val h = harness()
        page(h) {
            val response = client.get("/s/${token.value}") { header(HttpHeaders.UserAgent, iphone) }
            val html = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
            val csp = response.headers["Content-Security-Policy"]!!
            assertTrue("default-src 'none'" in csp && "img-src https://assets.circaso.com 'self'" in csp)
            assertTrue("<meta name=\"robots\" content=\"noindex, nofollow\">" in html)
            assertTrue("<h1>Book club</h1>" in html)
            assertTrue("Group · 24 members" in html)
            assertTrue("Fridays at six.\nBring a book." in html, "the description, line breaks kept")
            assertTrue("src=\"https://sandbox.circaso.com/s/${token.value}/photo?v=1700\"" in html)
            assertTrue("circaso://s/${token.value}" in html, "Join group opens the app")
            assertTrue(">Join group</a>" in html && "Get Circaso" in html)
            assertTrue("tap this link again to join" in html, "what to do after installing")
            assertFalse("<script" in html)
        }
    }

    @Test
    fun `the Open Graph tags carry the group, so a chat app draws it in the link's preview`() {
        val h = harness()
        page(h) {
            val html = client.get("/s/${token.value}").bodyAsText()
            val photo = "https://sandbox.circaso.com/s/${token.value}/photo?v=1700"

            assertTrue("<meta property=\"og:title\" content=\"Join Book club on Circaso\">" in html)
            assertTrue("<meta property=\"og:description\" content=\"Fridays at six. Bring a book.\">" in html)
            assertTrue("<meta property=\"og:image\" content=\"$photo\">" in html)
            assertTrue("<meta property=\"og:image:alt\" content=\"Book club\">" in html)
            assertTrue("<meta property=\"og:url\" content=\"https://sandbox.circaso.com/s/${token.value}\">" in html)
            assertTrue("<meta property=\"og:site_name\" content=\"Circaso\">" in html)
            assertTrue("<meta name=\"twitter:card\" content=\"summary\">" in html)
            assertTrue("<meta name=\"twitter:image\" content=\"$photo\">" in html)
            assertTrue("<title>Join Book club on Circaso · Circaso</title>" in html)
        }
    }

    @Test
    fun `a group with no photo or description shows initials, the brand image and the member count`() {
        val h = harness(description = null, photo = false, members = 1)
        page(h) {
            val html = client.get("/s/${token.value}").bodyAsText()

            assertTrue("Group · 1 member<" in html)
            assertTrue("group-avatar" in html && ">BC</span>" in html)
            assertFalse("/photo" in html)
            assertTrue("<meta property=\"og:image\" content=\"${h.config.previewImageUrl}\">" in html)
            assertTrue("content=\"A group on Circaso with 1 member. Tap to join.\"" in html)
        }
    }

    @Test
    fun `what the admin typed is escaped, and a long description is cut for the preview only`() {
        val long = "<b>Rules</b> " + "word ".repeat(80)
        val h = harness(name = "<script>alert(1)</script>\"Club'", description = long)
        page(h) {
            val html = client.get("/s/${token.value}").bodyAsText()

            assertFalse("<script>alert" in html)
            assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;&quot;Club&#39;" in html)
            assertTrue("&lt;b&gt;Rules&lt;/b&gt;" in html && "<b>Rules" !in html)
            val og = Regex("og:description\" content=\"([^\"]*)\"").find(html)!!.groupValues[1]
            assertTrue(og.endsWith("…") && og.length <= 300, "cut for the preview")
            assertTrue("word ".repeat(80).trim() in html, "whole on the page")
        }
    }

    @Test
    fun `on Android Join group is an intent that falls back to the store`() {
        val h = harness()
        page(h) {
            val html = client.get("/s/${token.value}") { header(HttpHeaders.UserAgent, android) }.bodyAsText()
            assertTrue("intent://s/${token.value}#Intent;scheme=circaso;package=com.circaso.app;" in html)
            assertTrue("S.browser_fallback_url=" in html)
        }
    }

    @Test
    fun `a link that was reset, turned off or never made says it no longer works, with nothing of the group`() {
        val h = harness()
        h.groupLinks.pages.clear()
        page(h) {
            for (gone in listOf(token, GroupLinkToken.new())) {
                val response = client.get("/s/${gone.value}")
                val html = response.bodyAsText()
                assertEquals(HttpStatusCode.Gone, response.status)
                assertTrue("This link no longer works" in html)
                assertTrue("Ask an admin of the group for a new one." in html)
                assertFalse("Book club" in html)
                assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
            }
        }
    }

    @Test
    fun `views are rate limited before the token is read, and the photo counts too`() {
        val h = harness()
        h.throttle.max = 2
        page(h) {
            assertEquals(HttpStatusCode.OK, client.get("/s/${token.value}").status)
            assertEquals(HttpStatusCode.OK, client.get("/s/${token.value}/photo").status)
            val third = client.get("/s/${token.value}")
            assertEquals(HttpStatusCode.TooManyRequests, third.status)
            assertTrue(third.headers[HttpHeaders.RetryAfter] != null)
            assertFalse("Book club" in third.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.get("/s/${token.value}/photo").status)
        }
    }

    @Test
    fun `the photo is served as the image it is, never indexed, and gone when the link is`() {
        val h = harness()
        page(h) {
            val response = client.get("/s/${token.value}/photo?v=1700")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
            assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
            assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertContentEquals(jpeg, response.readRawBytes())

            h.groupLinks.photos.clear()
            assertEquals(HttpStatusCode.NotFound, client.get("/s/${token.value}/photo").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/s/abcDEF123456/photo").status, "a share code has none")
        }
    }

    @Test
    fun `a share code still opens its own page`() {
        val h = harness()
        page(h) {
            val response = client.get("/s/abcDEF123456")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue("Check that the whole link was copied" in response.bodyAsText())
        }
    }

    @Test
    fun `an app from before join links gets the group's name and a line saying to update`() {
        val h = harness()
        val opened = runBlocking { h.shares.openGroup(token) }
        val dto = (opened as Resource.Success).data.toSharedLink(token, h.config, h.clock.now)

        assertEquals("group", dto.kind)
        assertEquals("Join Book club on Circaso", dto.title)
        assertEquals("Update Circaso to join this group.", dto.summary)
        assertEquals("https://sandbox.circaso.com/s/${token.value}", dto.url)
        assertFalse(dto.mine)
        assertNull(dto.open)
        assertNull(dto.person)

        val gone = runBlocking { h.shares.openGroup(GroupLinkToken.new()) }
        assertEquals("share.link_not_found", (gone as Resource.Error).error.code)
    }
}
