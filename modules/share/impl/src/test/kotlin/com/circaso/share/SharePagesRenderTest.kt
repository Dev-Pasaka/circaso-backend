package com.circaso.share

import com.circaso.messaging.api.ChatId
import com.circaso.share.api.ShareCode
import com.circaso.share.domain.models.CreateShareCommand
import com.circaso.share.domain.models.FiatRate
import com.circaso.share.domain.models.FiatValue
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.models.StatementLine
import com.circaso.share.domain.support.ShareConfig
import com.circaso.share.presentation.controllers.sharePageController
import com.circaso.share.presentation.mappers.Platform
import com.circaso.share.presentation.mappers.SharePages
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every page type and state rendered straight from the templates: what each must show, and what none may load. */
class SharePagesRenderTest {
    private val config = ShareConfig(baseUrl = "https://links.circaso.com")
    private val at = Instant.parse("2026-11-06T12:41:00Z")

    private fun link(kind: ShareKind, content: ShareContent) =
        ShareLink(ShareCode("Ab12Cd34Ef56"), UserId.new(), kind, content, at, null, null)

    private fun receipt(
        status: String = "CONFIRMED",
        fiat: Boolean = true,
        names: Boolean = true,
        txHash: String? = "0xfeedbeef0123456789abcdef0123456789abcdef0123456789abcdef01234567",
    ) = link(
        ShareKind.RECEIPT,
        ShareContent.Receipt(
            TransferId.new(),
            isSent = true,
            amount = "15.5",
            asset = "USDC",
            fiat = if (fiat) FiatValue("KES", BigDecimal("2000.00"), at) else null,
            status = status,
            blockchain = "BASE-SEPOLIA",
            txHash = txHash,
            at = at,
            fromName = if (names) "Wanjiru Kamau" else null,
            toName = if (names) "Juma Otieno" else null,
        ),
    )

    private fun statement(lines: List<StatementLine>) =
        link(
            ShareKind.STATEMENT,
            ShareContent.Statement(
                ChatId.new(),
                "Holiday Fund",
                500_000_000,
                325_500_000,
                50_000_000,
                275_500_000,
                8,
                Instant.parse("2026-12-20T00:00:00Z"),
                FiatRate("KES", BigDecimal("129.00"), at),
                lines,
                at,
            ),
        )

    private fun person(kind: ShareKind, name: String = "Wanjiru Kamau") =
        link(kind, ShareContent.Person(UserId.new(), name, "wanjiru", "invite-token"))

    private fun address() =
        link(
            ShareKind.ADDRESS,
            ShareContent.Address("BASE-SEPOLIA", "0x1234567890abcdef1234567890abcdef12345678", null),
        )

    private fun render(link: ShareLink) = SharePages.live(link, config, Platform.IOS)

    /** What every page guarantees, whatever it shows. */
    private fun assertPage(html: String) {
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue("<meta name=\"robots\" content=\"noindex, nofollow\">" in html)
        assertTrue("og:title" in html && "og:description" in html && "og:image" in html && "twitter:card" in html)
        assertFalse("<script" in html, "no script")
        assertFalse(
            "<link rel=\"stylesheet\"" in html || "@import" in html || "url(" in html,
            "no outside style or font",
        )
        // Everything the page loads is on the one host the CSP allows.
        Regex("(?:src|srcset)=\"([^\"]+)\"").findAll(html).forEach {
            assertTrue(it.groupValues[1].startsWith("https://assets.circaso.com/"), it.groupValues[1])
        }
        Regex("<link rel=\"(?:icon|apple-touch-icon)\" href=\"([^\"]+)\"").findAll(html).forEach {
            assertTrue(it.groupValues[1].startsWith("https://assets.circaso.com/"), it.groupValues[1])
        }
        assertTrue("prefers-reduced-motion: reduce" in html, "motion stands down")
        assertTrue("prefers-color-scheme: dark" in html)
    }

    @Test
    fun `a receipt is the money card, who paid whom, a drawn check and the explorer`() {
        val html = render(receipt())
        assertPage(html)
        assertTrue("class=\"money" in html)
        assertTrue("KES 2,000.00" in html, "the hero amount, spoken once")
        assertTrue("15.50 USDC · Base (testnet)" in html)
        assertTrue(">WK<" in html && ">JO<" in html, "initials")
        assertTrue("status ok" in html && "class=\"check\"" in html && "Completed" in html)
        assertTrue("1 USDC = KES 129.03" in html)
        assertTrue("https://sepolia.basescan.org/tx/0xfeedbeef" in html && "View on Basescan" in html)
        assertTrue("circaso://s/Ab12Cd34Ef56" in html && "Open in Circaso" in html && "Get Circaso" in html)
    }

    @Test
    fun `a sent payment is the quiet card, a received one the titanium card`() {
        assertTrue("class=\"money out rise\"" in render(receipt()))
        val received = (receipt().content as ShareContent.Receipt).copy(isSent = false)
        assertTrue("class=\"money in rise\"" in render(link(ShareKind.RECEIPT, received)))
    }

    @Test
    fun `Open in Circaso falls back to the store on Android and isn't offered on a computer`() {
        val android = SharePages.live(receipt(), config, Platform.ANDROID)
        assertTrue("intent://s/Ab12Cd34Ef56#Intent;scheme=circaso;package=com.circaso.app;" in android)
        assertTrue("S.browser_fallback_url=https%3A%2F%2Fplay.google.com" in android)
        val desktop = SharePages.live(receipt(), config, Platform.OTHER)
        assertFalse("circaso://" in desktop || "intent://" in desktop || "Open in Circaso" in desktop)
        assertTrue("Get Circaso" in desktop)
    }

    @Test
    fun `a receipt without names shows no one, and one on its way says so`() {
        val html = render(receipt(status = "SUBMITTED", fiat = false, names = false, txHash = null))
        assertPage(html)
        assertFalse("Wanjiru" in html || "Juma" in html)
        assertFalse("class=\"pair" in html, "no relationship to show")
        assertTrue("15.50 USDC" in html, "the coin is the hero without a fiat value")
        assertTrue("status wait" in html && "On its way" in html && "Open the link again" in html)
        assertFalse("basescan" in html, "no hash, no explorer")
    }

    @Test
    fun `a failed receipt is marked in red words, not only colour`() {
        val html = render(receipt(status = "FAILED"))
        assertTrue("status bad" in html && "Failed" in html)
    }

    @Test
    fun `a statement is the goal ring and the group's money thread, oldest first`() {
        val lines =
            listOf(
                StatementLine(at, true, 25_000_000, "Wanjiru", "Food for the trip"),
                StatementLine(at.minus(Duration.ofDays(1)), false, 50_000_000, "Juma", "Villa deposit"),
                StatementLine(at.minus(Duration.ofDays(2)), true, 100_000_000, null, null),
            )
        val html = render(statement(lines))
        assertPage(html)
        assertTrue("Holiday Fund" in html && "8 members" in html && "Ends 20 Dec 2026" in html)
        assertTrue("role=\"progressbar\"" in html && "aria-valuenow=\"65\"" in html && "65% of the target" in html)
        assertTrue("stroke-dashoffset:114.36" in html, "the ring stops at 65%")
        assertTrue("325.50 USDC" in html && "of 500.00 USDC" in html && "≈ KES 41,989.50" in html)
        assertTrue("Paid out" in html && "In the wallet" in html)
        assertTrue("money mini in" in html && "money mini out" in html, "each line is a money card")
        assertTrue("Paid to Juma" in html && "Contribution" in html && "Food for the trip" in html)
        val oldest = html.indexOf("+100.00")
        val newest = html.indexOf("+25.00")
        assertTrue(oldest in 0 until newest, "a chat reads oldest first")
        assertEquals(3, Regex("<li class=\"day").findAll(html).count(), "a day line where the day changes")
    }

    @Test
    fun `a statement with nothing moved yet says so`() {
        val html = render(statement(emptyList()))
        assertPage(html)
        assertTrue("No money has moved yet." in html)
    }

    @Test
    fun `an invite is the inviter typing to you, and its token is never on it`() {
        val html = render(person(ShareKind.INVITE))
        assertPage(html)
        assertTrue("Wanjiru Kamau" in html && "@wanjiru" in html && "class=\"typing\"" in html)
        assertTrue("Chat on Circaso" in html)
        assertFalse("invite-token" in html)
    }

    @Test
    fun `pay me teaches the hash amount in a chat`() {
        val html = render(person(ShareKind.PAY_ME))
        assertPage(html)
        assertTrue("Pay on Circaso" in html && "type <strong>#</strong> and the amount" in html)
        assertTrue("In a chat with Wanjiru," in html)
    }

    @Test
    fun `an address is readable in groups of four and copies whole`() {
        val html = render(address())
        assertPage(html)
        assertTrue("0x1234567890abcdef1234567890abcdef12345678" in html, "whole for screen readers")
        assertTrue("<span>0x12</span><span>3456</span>" in html, "grouped for eyes")
        assertTrue("user-select: all" in html)
        assertTrue("Send only USDC on Base (testnet)" in html)
    }

    @Test
    fun `what people typed is escaped everywhere it lands, initials and captions too`() {
        val hostile = "<img src=x onerror=alert(1)> \"Kip'"
        val html = render(person(ShareKind.INVITE, hostile))
        assertFalse("<img src=x" in html)
        assertTrue("&lt;img src=x onerror=alert(1)&gt; &quot;Kip&#39;" in html)
        val receipt =
            link(
                ShareKind.RECEIPT,
                ShareContent.Receipt(
                    TransferId.new(),
                    true,
                    "1",
                    "USDC",
                    null,
                    "CONFIRMED",
                    "BASE",
                    null,
                    at,
                    hostile,
                    "<u>x</u>",
                ),
            )
        val r = render(receipt)
        assertFalse("<img src=x" in r || "<u>" in r)
    }

    @Test
    fun `the four states that show nothing each have their own words`() {
        val revoked = SharePages.gone(config, Platform.ANDROID, isRevoked = true)
        val ended = SharePages.gone(config, Platform.ANDROID, isRevoked = false)
        val missing = SharePages.missing(config, Platform.ANDROID)
        val throttled = SharePages.throttled(config, Platform.ANDROID)
        listOf(revoked, ended, missing, throttled).forEach {
            assertPage(it)
            assertTrue(config.androidStoreUrl in it && "Get Circaso" in it)
            assertFalse("og:url" in it, "no link to a page that isn't there")
        }
        assertTrue("This link was turned off" in revoked)
        assertTrue("This link has ended" in ended)
        assertTrue("This link doesn" in missing)
        assertTrue("Give it a minute" in throttled)
    }

    @Test
    fun `an ended link and a turned-off one say which over HTTP`() {
        val h = ShareHarness()
        val ending =
            (
                runBlocking {
                    h.shares.create(
                        CreateShareCommand(
                            h.wanjiru,
                            ShareKind.PAY_ME,
                            null,
                            null,
                            null,
                            true,
                            null,
                            endsAfterDays = 1,
                        ),
                    )
                } as Resource.Success
            ).data
        val revoking =
            (
                runBlocking {
                    h.shares.create(
                        CreateShareCommand(h.wanjiru, ShareKind.PAY_ME, null, null, null, true, null, null),
                    )
                } as Resource.Success
            ).data
        runBlocking { h.shares.revoke(h.wanjiru, revoking.code) }
        h.clock.now = h.clock.now.plus(Duration.ofDays(2))
        testApplication {
            routing { sharePageController(h.pages, h.config) }
            val ended = client.get("/s/${ending.code.value}")
            assertEquals(HttpStatusCode.Gone, ended.status)
            assertTrue("This link has ended" in ended.bodyAsText())
            val off = client.get("/s/${revoking.code.value}")
            assertEquals(HttpStatusCode.Gone, off.status)
            assertTrue("This link was turned off" in off.bodyAsText())
        }
    }
}
