package com.circaso.share

import com.circaso.share.api.ShareCode
import com.circaso.share.domain.errors.ShareError
import com.circaso.share.domain.models.CreateShareCommand
import com.circaso.share.domain.models.OpenTarget
import com.circaso.share.domain.models.PageLookup
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.presentation.dto.CreateShareLinkRequest
import com.circaso.share.presentation.mappers.parseCursor
import com.circaso.share.presentation.mappers.toCommand
import com.circaso.share.presentation.mappers.toDto
import com.circaso.share.presentation.mappers.toResponse
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareTest {
    private fun ShareHarness.command(
        kind: ShareKind,
        owner: UserId = wanjiru,
        transferId: TransferId? = null,
        chatId: com.circaso.messaging.api.ChatId? = null,
        blockchain: String? = null,
        showNames: Boolean = true,
        currency: String? = null,
        days: Int? = null,
    ) = CreateShareCommand(owner, kind, transferId, chatId, blockchain, showNames, currency, days)

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun <T> Resource<T>.code(): String = (this as Resource.Error).error.code

    @Test
    fun `codes are 12 base62 characters, unguessable and never printed`() {
        val codes = (1..2_000).map { ShareCode.new().value }.toSet()
        assertEquals(2_000, codes.size)
        assertTrue(codes.all { it.length == 12 && it.all { c -> c.isLetterOrDigit() && c.code < 128 } })
        assertEquals("ShareCode(***)", ShareCode.new().toString())
        assertNull(ShareCode.parse("short"))
        assertNull(ShareCode.parse("abcdefghijk!"))
        assertNull(ShareCode.parse("abcdefghijklm"))
    }

    @Test
    fun `a receipt is built from the wallet's record with names, fiat and the explorer`() =
        runTest {
            val h = ShareHarness()
            val payment = h.payment()
            val link = h.shares.create(h.command(ShareKind.RECEIPT, transferId = payment, currency = "KES")).data()

            val receipt = link.content as ShareContent.Receipt
            assertTrue(receipt.isSent)
            assertEquals("12.5", receipt.amount)
            assertEquals("Wanjiru", receipt.fromName)
            assertEquals("Juma", receipt.toName)
            assertEquals(BigDecimal("1612.50"), receipt.fiat?.amount)
            assertNull(link.endsAt, "no end unless the sharer sets one")

            // Juma, who received it, may share it too, from his side.
            val his = h.shares.create(h.command(ShareKind.RECEIPT, owner = h.juma, transferId = payment)).data()
            assertFalse((his.content as ShareContent.Receipt).isSent)
        }

    @Test
    fun `with names off a receipt shows amounts only`() =
        runTest {
            val h = ShareHarness()
            val link = h.shares.create(h.command(ShareKind.RECEIPT, transferId = h.payment(), showNames = false)).data()
            val receipt = link.content as ShareContent.Receipt
            assertNull(receipt.fromName)
            assertNull(receipt.toName)
        }

    @Test
    fun `someone else's payment, or one not yet signed, can't be shared`() =
        runTest {
            val h = ShareHarness()
            val payment = h.payment()
            assertEquals(
                "share.source_not_found",
                h.shares.create(h.command(ShareKind.RECEIPT, owner = h.stranger, transferId = payment)).code(),
            )
            assertEquals(
                "share.source_not_found",
                h.shares.create(h.command(ShareKind.RECEIPT, transferId = TransferId.new())).code(),
            )
            assertEquals(
                "share.not_shareable",
                h.shares
                    .create(h.command(ShareKind.RECEIPT, transferId = h.payment(state = "AWAITING_SIGNATURE")))
                    .code(),
            )
            assertEquals(0, h.links.count())
        }

    @Test
    fun `a statement is the member's view, notes only with names on, and nothing for a stranger`() =
        runTest {
            val h = ShareHarness()
            val named = h.shares.create(h.command(ShareKind.STATEMENT, chatId = h.group, currency = "KES")).data()
            val statement = named.content as ShareContent.Statement
            assertEquals("Holiday Fund", statement.goalName)
            assertEquals(listOf("Mama Mboga", "Juma"), statement.lines.map { it.name })
            assertEquals("Food for the trip", statement.lines.first().note)
            assertEquals("KES", statement.fiat?.currency)

            val bare = h.shares.create(h.command(ShareKind.STATEMENT, chatId = h.group, showNames = false)).data()
            assertTrue((bare.content as ShareContent.Statement).lines.all { it.name == null && it.note == null })

            assertEquals(
                "share.source_not_found",
                h.shares.create(h.command(ShareKind.STATEMENT, owner = h.stranger, chatId = h.group)).code(),
            )
        }

    @Test
    fun `an invite carries a fresh invite and ends with it, pay me needs a handle`() =
        runTest {
            val h = ShareHarness()
            val invite = h.shares.create(h.command(ShareKind.INVITE, days = 30)).data()
            assertEquals(h.clock.now.plus(Duration.ofDays(7)), invite.endsAt, "the invite's 7 days win over 30")
            assertEquals("invite-token-1", (invite.content as ShareContent.Person).inviteToken)

            val payMe = h.shares.create(h.command(ShareKind.PAY_ME)).data()
            assertNull((payMe.content as ShareContent.Person).inviteToken)
            assertEquals("share.not_shareable", h.shares.create(h.command(ShareKind.PAY_ME, owner = h.juma)).code())

            h.invites.available = false
            assertEquals("share.unavailable", h.shares.create(h.command(ShareKind.INVITE)).code())
        }

    @Test
    fun `an address comes from the wallet's own record`() =
        runTest {
            val h = ShareHarness()
            val link = h.shares.create(h.command(ShareKind.ADDRESS, blockchain = "BASE-SEPOLIA")).data()
            assertEquals("0x1234567890abcdef1234567890abcdef12345678", (link.content as ShareContent.Address).address)
            val other = h.shares.create(h.command(ShareKind.ADDRESS, blockchain = "SOL"))
            assertEquals("share.source_not_found", other.code())
        }

    @Test
    fun `the request's shape is checked before anything is built`() {
        val h = ShareHarness()

        fun check(request: CreateShareLinkRequest) = request.toCommand(h.wanjiru, h.config)
        assertEquals("share.request_invalid", check(CreateShareLinkRequest("selfie")).code())
        assertEquals("share.request_invalid", check(CreateShareLinkRequest("receipt", transferId = "nope")).code())
        assertEquals("share.request_invalid", check(CreateShareLinkRequest("invite", expiresInDays = 0)).code())
        assertEquals("share.request_invalid", check(CreateShareLinkRequest("invite", expiresInDays = 366)).code())
        assertEquals("share.request_invalid", check(CreateShareLinkRequest("receipt", currency = "kenyan")).code())
        val ok = check(CreateShareLinkRequest(" Pay_Me ", currency = "kes", blockchain = "base")).data()
        assertEquals(ShareKind.PAY_ME, ok.kind)
        assertEquals("KES", ok.currency)
        assertEquals("BASE", ok.blockchain)
    }

    @Test
    fun `turning a link off clears what it showed, and only its owner can`() =
        runTest {
            val h = ShareHarness()
            val link = h.shares.create(h.command(ShareKind.RECEIPT, transferId = h.payment())).data()

            assertEquals("share.link_not_found", h.shares.revoke(h.juma, link.code).code())
            assertTrue(h.shares.revoke(h.wanjiru, link.code) is Resource.Success)
            assertEquals("share.link_not_found", h.shares.revoke(h.wanjiru, link.code).code(), "already off")

            assertIs<PageLookup.Gone>(h.pages.view(link.code.value, "1.2.3.4").data())
            assertEquals("share.link_not_found", h.shares.open(h.juma, link.code).code())
            assertTrue(
                h.shares
                    .list(h.wanjiru, null, 50)
                    .data()
                    .links
                    .isEmpty(),
            )

            // 30 days later the row itself is gone: the page then says the link never was.
            h.clock.now = h.clock.now.plus(Duration.ofDays(31))
            assertIs<PageLookup.Missing>(h.pages.view(link.code.value, "1.2.3.4").data())
        }

    @Test
    fun `a link past its end stops opening`() =
        runTest {
            val h = ShareHarness()
            val link = h.shares.create(h.command(ShareKind.PAY_ME, days = 1)).data()
            assertIs<PageLookup.Live>(h.pages.view(link.code.value, "1.2.3.4").data())
            h.clock.now = h.clock.now.plus(Duration.ofDays(2))
            assertIs<PageLookup.Gone>(h.pages.view(link.code.value, "1.2.3.4").data())
        }

    @Test
    fun `opened in the app, each viewer gets only where they may go`() =
        runTest {
            val h = ShareHarness()
            val payment = h.payment()
            val receipt = h.shares.create(h.command(ShareKind.RECEIPT, transferId = payment)).data()
            assertEquals(
                OpenTarget.Transfer(payment),
                h.shares
                    .open(h.juma, receipt.code)
                    .data()
                    .open,
            )
            assertNull(
                h.shares
                    .open(h.stranger, receipt.code)
                    .data()
                    .open,
                "not their payment",
            )

            val statement = h.shares.create(h.command(ShareKind.STATEMENT, chatId = h.group)).data()
            assertEquals(
                OpenTarget.Chama(h.group),
                h.shares
                    .open(h.juma, statement.code)
                    .data()
                    .open,
            )
            assertNull(
                h.shares
                    .open(h.stranger, statement.code)
                    .data()
                    .open,
                "not their group",
            )

            val invite = h.shares.create(h.command(ShareKind.INVITE)).data()
            val opened = h.shares.open(h.stranger, invite.code).data()
            assertIs<OpenTarget.Invite>(opened.open)
            assertFalse(opened.isMine)
            val mine = h.shares.open(h.wanjiru, invite.code).data()
            assertTrue(mine.isMine)
            assertNull(mine.open, "your own invite opens nothing")
            assertNull(mine.toResponse(h.config).open)
        }

    @Test
    fun `a receipt still on its way is brought up to date when opened`() =
        runTest {
            val h = ShareHarness()
            val payment = h.payment(state = "SUBMITTED", txHash = null)
            val link = h.shares.create(h.command(ShareKind.RECEIPT, transferId = payment)).data()
            val settled =
                h.wallets.transfers
                    .getValue(payment)
                    .copy(state = "CONFIRMED", txHash = "0xabc")
            h.wallets.transfers[payment] = settled

            val page = h.pages.view(link.code.value, "1.2.3.4").data() as PageLookup.Live
            val fresh = page.link.content as ShareContent.Receipt
            assertEquals("CONFIRMED", fresh.status)
            assertEquals("0xabc", fresh.txHash)
            val stored = h.links.find(link.code, h.clock.now)!!.content as ShareContent.Receipt
            assertEquals("CONFIRMED", stored.status)
        }

    @Test
    fun `pages are throttled per caller address before the code is read`() =
        runTest {
            val h = ShareHarness()
            h.throttle.max = 2
            assertIs<PageLookup.Missing>(h.pages.view("AAAAAAAAAAAA", "5.6.7.8").data())
            assertIs<PageLookup.Missing>(h.pages.view("not a code", "5.6.7.8").data())
            assertEquals(PageLookup.Throttled(42), h.pages.view("AAAAAAAAAAAA", "5.6.7.8").data())
            assertIs<PageLookup.Missing>(h.pages.view("AAAAAAAAAAAA", "9.9.9.9").data())
        }

    @Test
    fun `the owner lists live links newest first, a page at a time`() =
        runTest {
            val h = ShareHarness()
            val made =
                (1..3).map {
                    h.clock.now = h.clock.now.plusSeconds(1)
                    h.shares.create(h.command(ShareKind.PAY_ME)).data()
                }
            val first = h.shares.list(h.wanjiru, null, 2).data()
            assertEquals(made.reversed().take(2).map(ShareLink::code), first.links.map(ShareLink::code))
            val cursor = first.toResponse(h.config).nextCursor
            val second = h.shares.list(h.wanjiru, parseCursor(cursor), 2).data()
            assertEquals(listOf(made.first().code), second.links.map(ShareLink::code))
            assertNull(second.next)
            assertTrue(
                h.shares
                    .list(h.juma, null, 50)
                    .data()
                    .links
                    .isEmpty(),
            )
        }

    @Test
    fun `a deleted account's links all go`() =
        runTest {
            val h = ShareHarness()
            val link = h.shares.create(h.command(ShareKind.PAY_ME)).data()
            h.shares.create(h.command(ShareKind.INVITE))
            h.identity.accountDeleted(h.wanjiru)
            assertEquals(0, h.links.count())
            assertIs<PageLookup.Missing>(h.pages.view(link.code.value, "1.2.3.4").data())
        }

    @Test
    fun `a link's answer carries its url on the share host`() =
        runTest {
            val h = ShareHarness()
            val link = h.shares.create(h.command(ShareKind.PAY_ME)).data()
            val dto = link.toDto(h.config)
            assertEquals("https://sandbox.circaso.com/s/${link.code.value}", dto.url)
            assertEquals("pay_me", dto.kind)
            assertEquals("Pay Wanjiru on Circaso", dto.title)
            assertNotEquals("", dto.summary)
        }

    @Suppress("unused")
    private val unused = ShareError.LinkNotFound
}
