package com.circaso.wallet

import com.circaso.integrations.api.IntegrationError
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.ApprovedPayout
import com.circaso.wallet.domain.models.CreateGroupWalletCommand
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.PreparedTransfer
import com.circaso.wallet.domain.models.QuoteProblem
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletQuery
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2F limits (spaces-plan §7a) over in-memory fakes: a group's cap on what leaves its wallet over a rolling 24
 * hours, fees included and payouts to members counted, the error's numbers, W17 reporting it, a failed payment no
 * longer counting, and the window rolling on.
 */
class LimitTest {
    private val rules = MemoryRules()
    private val h = Harness(ruleRows = rules)
    private val amina = UserId.new() // owns the group and signs for its wallet
    private val wanjiru = UserId.new() // a member, paid out
    private val otieno = UserId.new()
    private val chat: ChatId = h.chats.group(listOf(amina, wanjiru, otieno))
    private val usdc = 1_000_000L

    /** The group wallet with 100 USDC, a cap of [cap] USDC and the fee seeds (1 % from a group). */
    private suspend fun group(cap: Long = 30): WalletOwner.Group {
        rules.seedFees()
        rules.seedGroupLimit(cap * usdc)
        val owner = h.createGroupWallet(CreateGroupWalletCommand(amina, chat, UUID.randomUUID())).data().owner
        h.custody.sign(h.circleUserOf(owner.holder))
        h.getWallet(WalletQuery(wanjiru, null, owner.walletId)).data()
        val wallet = h.wallets.wallets(owner.holder).single()
        h.custody.held[wallet.circleWalletId] = mapOf("USDC" to BigInteger.valueOf(100 * usdc))
        h.walletOn(wanjiru)
        return owner
    }

    private suspend fun payOut(owner: WalletOwner.Group, amount: Long): Resource<PreparedTransfer> {
        val requestId = UUID.randomUUID()
        h.approvals.approved[requestId] = ApprovedPayout(requestId, chat, wanjiru, null, amount)
        val command =
            TransferCommand(
                amina,
                "BASE-SEPOLIA",
                Payee.Person(wanjiru),
                Money(amount, Asset.USDC),
                chat,
                null,
                UUID.randomUUID(),
                walletId = owner.walletId,
                payoutRequestId = requestId,
            )
        val quote = h.quoteTransfer(command).data()
        return h.prepareTransfer(command.copy(quoteId = quote.quoteId))
    }

    /** The owner signed it: on its way, so it counts whatever the clock does. */
    private suspend fun signed(prepared: Resource<PreparedTransfer>): com.circaso.wallet.domain.models.Transfer {
        val transfer = prepared.data().transfer
        h.transfers.move(
            transfer.transferId,
            com.circaso.wallet.domain.models.TransferState.SUBMITTED,
            com.circaso.wallet.domain.models
                .TransferChange(at = h.clock.instant()),
        )
        return transfer
    }

    private fun Resource<*>.field(name: String): String? =
        (this as Resource.Error)
            .error.details
            ?.fields
            ?.firstOrNull { it.field == name }
            ?.problem

    @Test
    fun `a group's cap counts what leaves in 24 hours, fees and payouts to members included`() =
        runBlocking<Unit> {
            val owner = group()
            val first = signed(payOut(owner, 10 * usdc))
            assertEquals(100_000L, first.feeMinor, "1 % from a group wallet")
            h.clock.advance(Duration.ofHours(1))
            signed(payOut(owner, 10 * usdc))

            val refused = payOut(owner, 10 * usdc)

            assertEquals("wallet.limit_exceeded", refused.code(), "20.20 has left; 10.10 more would pass 30")
            assertEquals("DAILY", refused.field("limit"))
            assertEquals("30000000", refused.field("limitMinor"))
            assertEquals("20200000", refused.field("usedMinor"))
            assertEquals("9800000", refused.field("leftMinor"))
            assertEquals(first.createdAt.plus(Duration.ofHours(24)).toString(), refused.field("resetsAt"))
            assertEquals(first.createdAt.plus(Duration.ofHours(24)).toString(), refused.field("moreAt"))
            val max = refused.field("maxAmountMinor")!!.toLong()
            assertEquals(9_700_000L, max, "9.70 with its 10 cent fee fits the 9.80 left")
        }

    @Test
    fun `W17 reports the cap to any member, with when the payment fits, and holds nothing for them`() =
        runBlocking<Unit> {
            val owner = group(cap = 15)
            val first = payOut(owner, 10 * usdc).data().transfer

            val quote = h.quote(wanjiru, Payee.Person(otieno), 10 * usdc, walletId = owner.walletId)
            h.walletOn(otieno)
            h.reach.connect(wanjiru, otieno)
            val seen = h.quote(wanjiru, Payee.Person(otieno), 10 * usdc, walletId = owner.walletId).data()

            assertEquals("users.not_reachable", quote.code(), "W4's payee rules still apply")
            assertEquals(QuoteProblem.DAILY_LIMIT, seen.problem)
            assertEquals(first.createdAt.plus(Duration.ofHours(24)), seen.fitsAt)
            assertEquals(4_900_000L, seen.allowance.leftMinor, "15 - 10.10 = 4.90 left")
            assertNull(seen.quoteId, "only the signer gets a hold")
        }

    @Test
    fun `the window rolls - 24 hours later the same payout fits`() =
        runBlocking<Unit> {
            val owner = group(cap = 15)
            payOut(owner, 10 * usdc).data()
            assertEquals("wallet.limit_exceeded", payOut(owner, 10 * usdc).code())

            h.clock.advance(Duration.ofHours(24))

            payOut(owner, 10 * usdc).data()
        }

    @Test
    fun `a payment Circle refused no longer counts, nor one that expired unsigned`() =
        runBlocking<Unit> {
            val owner = group(cap = 15)
            h.custody.refuseTransfers = IntegrationError.Rejected("no")
            assertTrue(payOut(owner, 10 * usdc) is Resource.Error)
            h.custody.refuseTransfers = null
            assertEquals(0L, h.used(owner.holder))

            payOut(owner, 10 * usdc).data()
            h.clock.advance(Duration.ofMinutes(16))
            assertEquals(0L, h.used(owner.holder), "past its signing window it can't go, so it doesn't count")
            payOut(owner, 10 * usdc).data()
        }

    @Test
    fun `personal wallets keep their placeholders - one payment's amount and the day`() =
        runBlocking<Unit> {
            val bob = UserId.new()
            h.fund(amina, 2_000 * usdc)
            h.walletOn(bob)
            h.reach.connect(amina, bob)

            val tooBig = h.pay(amina, Payee.Person(bob), 501 * usdc)

            assertEquals("wallet.limit_exceeded", tooBig.code())
            assertEquals("PER_PAYMENT", tooBig.field("limit"))
            assertEquals("500000000", tooBig.field("maxAmountMinor"))
        }
}
