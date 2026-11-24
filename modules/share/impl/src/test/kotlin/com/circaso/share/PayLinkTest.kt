package com.circaso.share

import com.circaso.share.domain.models.PayLink
import com.circaso.share.domain.support.Keccak256
import com.circaso.share.domain.support.PayLinks
import com.circaso.share.presentation.controllers.sharePageController
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pay links (decision 2026-11-24): the strict reader, and the page a phone's camera opens. */
class PayLinkTest {
    private val to = "0x7a3f0000000000000000000000000000001e4b00"
    private val sol = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val android = "Mozilla/5.0 (Linux; Android 14; 23021RAA2Y)"
    private val iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)"

    @Test
    fun `keccak and EIP-55 match the published vectors`() {
        assertEquals(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            Keccak256.hash(ByteArray(0)).joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
            Keccak256.hash("abc".toByteArray()).joinToString("") { "%02x".format(it) },
        )
        listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
            "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
        ).forEach { address ->
            assertEquals(address, PayLinks.checksummed(address.substring(2).lowercase()))
            assertTrue(PayLinks.checksumHolds(address), address)
        }
        // One character's case flipped: a typo the checksum catches.
        assertFalse(PayLinks.checksumHolds("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"))
        assertTrue(PayLinks.checksumHolds(to))
        assertTrue(PayLinks.checksumHolds(to.uppercase().replace("0X", "0x")))
        assertFalse(PayLinks.checksumHolds("0x" + "0".repeat(40)), "the zero address never pays")
    }

    @Test
    fun `a link is payable only with a valid address, a chain Circaso offers and USDC`() {
        assertEquals(PayLink.Payable(to, "BASE", null), PayLinks.read(to, "BASE", "USDC", null))
        assertEquals(PayLink.Payable(to, "BASE-SEPOLIA", "12.5"), PayLinks.read(to, "base-sepolia", "usdc", "012.50"))
        // No asset said: the link is still a USDC link (it can't ask for anything else).
        assertEquals(PayLink.Payable(to, "MATIC", null), PayLinks.read(to, "MATIC", null, null))
        assertEquals(PayLink.Payable(sol, "SOL", null), PayLinks.read(sol, "SOL", "USDC", null))

        val typo = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"
        assertEquals(PayLink.BadAddress, PayLinks.read(typo, "BASE", "USDC", null))
        assertEquals(PayLink.BadAddress, PayLinks.read("0x1234", "BASE", "USDC", null))
        assertEquals(PayLink.BadAddress, PayLinks.read(null, "BASE", "USDC", null))
        assertEquals(PayLink.BadAddress, PayLinks.read(sol, "BASE", "USDC", null), "a Solana address on an EVM chain")
        assertEquals(PayLink.BadAddress, PayLinks.read(to, "SOL", "USDC", null), "a 0x address on Solana")
        assertEquals(PayLink.BadAddress, PayLinks.read("1".repeat(34), "SOL", "USDC", null), "not a 32-byte key")

        assertEquals(PayLink.UnknownChain("ARB"), PayLinks.read(to, "ARB", "USDC", null))
        assertEquals(PayLink.UnknownChain(null), PayLinks.read(to, null, "USDC", null))
        assertEquals(PayLink.NotUsdc, PayLinks.read(to, "BASE", "USDT", null))
    }

    @Test
    fun `an amount is kept only when it reads exactly`() {
        val tooPrecise = assertIs<PayLink.Payable>(PayLinks.read(to, "BASE", "USDC", "1.1234567"))
        assertNull(tooPrecise.amount)
        assertTrue(tooPrecise.amountUnreadable)
        assertTrue(assertIs<PayLink.Payable>(PayLinks.read(to, "BASE", "USDC", "1e6")).amountUnreadable)
        assertEquals("0.000001", assertIs<PayLink.Payable>(PayLinks.read(to, "BASE", "USDC", "0.000001")).amount)
    }

    private fun pay(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        val h = ShareHarness()
        testApplication {
            routing { sharePageController(h.pages, h.config) }
            block()
        }
    }

    @Test
    fun `the page shows the address to copy, the network and coin, and opens the app`() =
        pay {
            val response =
                client.get("/pay/$to?chain=BASE&asset=USDC&amount=25") { header(HttpHeaders.UserAgent, android) }
            val html = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
            assertTrue("<span>0x7a3f</span><span>0000</span>" in html, "the address in groups of four")
            assertTrue("data-copy=\"$to\"" in html && " hidden>" in html, "Copy, hidden until the script shows it")
            assertTrue("<h1>Send USDC <span class=\"pay-on\">on <b>Base</b></span></h1>" in html)
            assertTrue("Amount</span>" in html && "25.00" in html)
            assertTrue("<p class=\"sr\">0x7a3f, 0000, " in html, "spoken group by group")
            assertFalse("0x7a3f…" in html, "the preview never shortens the address")
            assertTrue("Send only USDC on Base to this address" in html)
            val intent = "intent://pay/$to?chain=BASE&amp;asset=USDC&amp;amount=25#Intent;scheme=circaso;"
            assertTrue("${intent}package=com.circaso.app" in html)
            assertFalse("<form" in html, "nothing here takes input")

            // The page runs its one script and nothing else: the policy names exactly its hash.
            val csp = response.headers["Content-Security-Policy"]!!
            val script = html.substringAfter("<script>").substringBefore("</script>")
            val digest = MessageDigest.getInstance("SHA-256").digest(script.toByteArray())
            val hash = Base64.getEncoder().encodeToString(digest)
            assertTrue("script-src 'sha256-$hash'" in csp, csp)
            assertTrue("default-src 'none'" in csp && "form-action 'none'" in csp)

            val ios =
                client
                    .get(
                        "/pay/$to?chain=BASE-SEPOLIA&asset=USDC",
                    ) { header(HttpHeaders.UserAgent, iphone) }
                    .bodyAsText()
            assertTrue("circaso://pay/$to?chain=BASE-SEPOLIA&amp;asset=USDC" in ios)
            assertTrue("Base (testnet)" in ios && "only test USDC" in ios && "Test network" in ios)
            // On iOS the page shows when the app isn't there: the store leads, the scheme comes second.
            assertTrue(ios.indexOf("Get Circaso") < ios.indexOf("Open in Circaso"))

            val desktop = client.get("/pay/$to?chain=BASE&asset=USDC").bodyAsText()
            assertFalse("Open in Circaso" in desktop, "a computer has no app to open")
        }

    @Test
    fun `a link that can't be paid never shows an address`() =
        pay {
            val typo = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"
            val bad = client.get("/pay/$typo?chain=BASE&asset=USDC") { header(HttpHeaders.UserAgent, android) }
            val badHtml = bad.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, bad.status)
            assertTrue("This address isn&#39;t valid" in badHtml)
            assertFalse(typo in badHtml && "data-copy" in badHtml)
            assertFalse("<script>" in badHtml)

            val chain = client.get("/pay/$to?chain=%3Cb%3EARB&asset=USDC")
            val chainHtml = chain.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, chain.status)
            assertTrue("Unknown network" in chainHtml && "doesn&#39;t support. " in chainHtml)
            assertFalse("&lt;B&gt;" in chainHtml, "words that aren't a network's name are never repeated")
            val arb = client.get("/pay/$to?chain=ARB&asset=USDC").bodyAsText()
            assertTrue("(ARB)" in arb && "Base, Polygon, Ethereum or Solana" in arb)
            assertFalse(to in chainHtml)

            val noChain = client.get("/pay/$to").bodyAsText()
            assertTrue("doesn&#39;t say which network" in noChain)

            val coin = client.get("/pay/$to?chain=BASE&asset=USDT")
            assertEquals(HttpStatusCode.BadRequest, coin.status)
            assertTrue("isn&#39;t for USDC" in coin.bodyAsText())
        }
}
