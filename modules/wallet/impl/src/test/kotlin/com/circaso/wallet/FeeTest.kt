package com.circaso.wallet

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.domain.models.FeeKind
import com.circaso.wallet.domain.models.FeeState
import com.circaso.wallet.domain.models.NoFeeReason
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.PreparedTransfer
import com.circaso.wallet.domain.models.QuoteProblem
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferQuote
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2F fees (spaces-plan §7b) over in-memory fakes: the rule that applies, the gas floor (gas at most 25 % of the
 * fee), the quote held for 15 minutes and charged by W4, `wallet.fee_changed`, the batch that carries the fee leg to
 * the fee wallet from server configuration, Solana without a fee, the balance with the fee, and the ledger.
 *
 * The fake network costs 0.000004 ETH at 2 500 dollars: one cent, so the floor is 1¢ × 1.2 ÷ 25 % = 4.8¢ → 5¢.
 */
class FeeTest {
    private val rules = MemoryRules()
    private val ledger = MemoryFeeLedger()
    private val h = Harness(ruleRows = rules, ledger = ledger)
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val usdc = 1_000_000L

    private suspend fun ready(balance: Long = 400 * usdc) {
        rules.seedFees()
        h.fund(amina, balance)
        h.walletOn(bob)
        h.reach.connect(amina, bob)
    }

    private suspend fun quoteBob(amount: Long, blockchain: String = "BASE-SEPOLIA"): TransferQuote =
        h.quote(amina, Payee.Person(bob), amount, blockchain).data()

    private suspend fun payBob(
        amount: Long,
        quoteId: UUID? = null,
        blockchain: String = "BASE-SEPOLIA",
    ): Resource<PreparedTransfer> =
        h.prepareTransfer(
            TransferCommand(
                amina,
                blockchain,
                Payee.Person(bob),
                com.circaso.shared.Money(amount, com.circaso.shared.Asset.USDC),
                null,
                null,
                UUID.randomUUID(),
                quoteId = quoteId,
            ),
        )

    private fun Resource<*>.field(name: String): String? =
        (this as Resource.Error)
            .error.details
            ?.fields
            ?.firstOrNull { it.field == name }
            ?.problem

    // ---- the price ----

    @Test
    fun `between Circaso people the fee is 0,5 percent, quoted with the total and held for 15 minutes`() =
        runBlocking<Unit> {
            ready()
            val quote = quoteBob(100 * usdc)

            assertEquals(500_000.toBigInteger(), quote.fee.amountMinor, "0.5 % of 100 USDC")
            assertEquals(50, quote.fee.rateBps)
            assertFalse(quote.fee.minimum)
            assertEquals(FeeKind.INTERNAL, quote.fee.parties.kind)
            assertEquals(100_500_000.toBigInteger(), quote.totalMinor, "the sender pays on top")
            assertNotNull(quote.quoteId, "the person who signs gets a hold")
            assertEquals(h.clock.instant().plus(Duration.ofMinutes(15)), quote.expiresAt)
            assertNull(quote.problem)
            assertEquals(1, h.custody.count("estimateSplit"), "gas estimated for the exact batch")
        }

    @Test
    fun `a small payment pays the minimum that keeps the network cost under a quarter of the fee`() =
        runBlocking<Unit> {
            ready()
            val quote = quoteBob(usdc)

            assertEquals(50_000.toBigInteger(), quote.fee.amountMinor, "5 cents, not the 0.5 cent the rate gives")
            assertTrue(quote.fee.minimum)
            assertEquals(10_000L, quote.fee.gasMinor, "one cent of gas, booked for the reports")
            assertTrue((quote.fee.gasMinor!! * 4).toBigInteger() <= quote.fee.amountMinor)
        }

    @Test
    fun `to an outside address 2 percent, and into a group wallet 1 percent`() =
        runBlocking<Unit> {
            ready()
            val outside = h.quote(amina, Payee.Address("0x" + "1234567890".repeat(4)), 100 * usdc).data()
            assertEquals(2_000_000.toBigInteger(), outside.fee.amountMinor)
            assertEquals(FeeKind.EXTERNAL, outside.fee.parties.kind)
            assertNull(outside.fee.parties.receiverType)

            val chat = h.chats.group(listOf(bob, amina, UserId.new()))
            val group =
                h
                    .createGroupWallet(
                        com.circaso.wallet.domain.models
                            .CreateGroupWalletCommand(bob, chat, UUID.randomUUID()),
                    ).data()
                    .owner
            h.custody.sign(h.circleUserOf(group.holder))
            h.sync.sync(h.wallets.circleUser(group.holder)!!).data()
            val contribution = h.quote(amina, Payee.Group(chat), 100 * usdc).data()
            assertEquals(1_000_000.toBigInteger(), contribution.fee.amountMinor)
            assertEquals(WalletOwnerType.GROUP, contribution.fee.parties.receiverType)
        }

    @Test
    fun `the most specific rule wins, and a 0 percent promotion takes nothing and adds no floor`() =
        runBlocking<Unit> {
            ready()
            rules.fee(
                0,
                kind = FeeKind.INTERNAL,
                senderType = WalletOwnerType.PERSON,
                receiverType = WalletOwnerType.PERSON,
            )

            val quote = quoteBob(usdc)

            assertEquals(0.toBigInteger(), quote.fee.amountMinor)
            assertFalse(quote.fee.minimum)
            assertEquals(0, h.custody.count("estimateSplit"), "nothing to estimate for a free payment")
        }

    @Test
    fun `a new version of a rule replaces the old one from its start, and an ended rule no longer applies`() =
        runBlocking<Unit> {
            ready()
            val id = UUID.randomUUID()
            rules.fees.clear()
            rules.fee(50, kind = FeeKind.INTERNAL, ruleId = id, version = 1)
            rules.fee(80, kind = FeeKind.INTERNAL, ruleId = id, version = 2, from = h.clock.instant().plusSeconds(60))

            assertEquals(50, quoteBob(100 * usdc).fee.rateBps, "version 2 hasn't started")
            h.clock.advance(Duration.ofMinutes(2))
            assertEquals(80, quoteBob(100 * usdc).fee.rateBps)

            rules.fees.replaceAll { it.copy(effectiveTo = h.clock.instant()) }
            val none = quoteBob(100 * usdc).fee
            assertEquals(0.toBigInteger(), none.amountMinor)
            assertEquals(NoFeeReason.NO_RULE, none.reason)
        }

    @Test
    fun `Solana carries no fee leg, so it is free and goes as a plain transfer`() =
        runBlocking<Unit> {
            ready()
            h.fund(amina, 100 * usdc, "SOL-DEVNET")
            h.walletOn(bob, "SOL-DEVNET")
            rules.fees.removeIf { it.blockchain == "SOL-DEVNET" }

            val quote = quoteBob(10 * usdc, "SOL-DEVNET")
            assertEquals(0.toBigInteger(), quote.fee.amountMinor)
            assertEquals(NoFeeReason.NO_SPLIT, quote.fee.reason)
            assertEquals(listOf("SOL-DEVNET" to "NO_SPLIT"), h.warnings.feesNotTaken, "a rule asked for a fee there")

            payBob(10 * usdc, quote.quoteId, "SOL-DEVNET").data()
            assertEquals(1, h.custody.transfers.size)
            assertTrue(h.custody.splits.isEmpty())
        }

    @Test
    fun `a chain without a configured fee wallet takes no fee`() =
        runBlocking<Unit> {
            ready()
            h.feeConfig.addresses.clear()

            val quote = quoteBob(100 * usdc)

            assertEquals(0.toBigInteger(), quote.fee.amountMinor)
            assertEquals(NoFeeReason.NO_FEE_WALLET, quote.fee.reason)
        }

    // ---- W4 charges what was shown ----

    @Test
    fun `W4 with the quote charges its fee in one batch to the payee and the fee wallet from configuration`() =
        runBlocking<Unit> {
            ready()
            val quote = quoteBob(100 * usdc)

            val prepared = payBob(100 * usdc, quote.quoteId).data()

            assertEquals(500_000.toBigInteger(), prepared.transfer.feeMinor)
            assertEquals(100_500_000.toBigInteger(), prepared.transfer.totalMinor)
            assertNotNull(prepared.challengeId)
            assertEquals(50, prepared.fee?.rateBps)
            assertTrue(h.custody.transfers.isEmpty(), "not a plain transfer")
            val (_, batch) = h.custody.splits.single()
            assertEquals(prepared.transfer.transferId.toString(), batch.refId)
            val bobs = h.walletOn(bob).address.lowercase()
            assertEquals(
                listOf(bobs to 100 * usdc, FakeFeeConfig.FEE_WALLET to 500_000L),
                batch.legs.map {
                    it.destinationAddress to
                        it.amount.amountMinor.toLong()
                },
            )
            assertEquals(h.walletOn(amina).address, batch.walletAddress, "the batch runs on the sender's own wallet")
        }

    @Test
    fun `a quote holds for 15 minutes even if the rule changes, and W4 charges exactly it`() =
        runBlocking<Unit> {
            ready()
            val quote = quoteBob(100 * usdc)
            rules.fee(
                150,
                kind = FeeKind.INTERNAL,
                senderType = WalletOwnerType.PERSON,
                receiverType = WalletOwnerType.PERSON,
            )

            val prepared = payBob(100 * usdc, quote.quoteId).data()

            assertEquals(500_000.toBigInteger(), prepared.transfer.feeMinor, "the fee the person saw")
        }

    @Test
    fun `without a quote, a fee above zero answers fee_changed with a new quote, which W4 then charges`() =
        runBlocking<Unit> {
            ready()
            val refused = payBob(100 * usdc)

            assertEquals("wallet.fee_changed", refused.code())
            assertEquals("0", refused.field("quotedFeeMinor"))
            assertEquals("500000", refused.field("feeMinor"))
            assertEquals("100500000", refused.field("totalMinor"))
            assertTrue((h.transfers as MemoryTransferRepository).rows.isEmpty(), "nothing was prepared")

            val again = payBob(100 * usdc, UUID.fromString(refused.field("quoteId"))).data()
            assertEquals(500_000.toBigInteger(), again.transfer.feeMinor)
        }

    @Test
    fun `a quote that has run out is worked out again - the same fee goes, a higher one is shown first`() =
        runBlocking<Unit> {
            ready()
            val first = quoteBob(100 * usdc)
            h.clock.advance(Duration.ofMinutes(16))
            payBob(100 * usdc, first.quoteId).data()

            val second = quoteBob(100 * usdc)
            rules.fee(
                150,
                kind = FeeKind.INTERNAL,
                senderType = WalletOwnerType.PERSON,
                receiverType = WalletOwnerType.PERSON,
            )
            h.clock.advance(Duration.ofMinutes(16))
            val refused = payBob(100 * usdc, second.quoteId)

            assertEquals("wallet.fee_changed", refused.code(), "GW13c: they never sign a fee they haven't seen")
            assertEquals("500000", refused.field("quotedFeeMinor"))
            assertEquals("1500000", refused.field("feeMinor"))
        }

    @Test
    fun `a quote is only for its own payment`() =
        runBlocking<Unit> {
            ready()
            val quote = quoteBob(100 * usdc)

            assertEquals("wallet.fee_changed", payBob(200 * usdc, quote.quoteId).code(), "another amount isn't quoted")
        }

    // ---- the balance with the fee ----

    @Test
    fun `the amount fits but not with its fee - W17 says so and offers the most that fits, W4 refuses with it`() =
        runBlocking<Unit> {
            ready(balance = 100 * usdc)
            val quote = quoteBob(100 * usdc)

            assertEquals(QuoteProblem.INSUFFICIENT_FUNDS, quote.problem)
            val max = quote.maxAmountMinor.toLong()
            assertTrue(max in 99 * usdc..100 * usdc)
            assertTrue(max + quoteBob(max).fee.amountMinor.toLong() <= 100 * usdc, "the largest whose total fits")
            assertTrue(max + 1 + quoteBob(max + 1).fee.amountMinor.toLong() > 100 * usdc)

            val refused = payBob(100 * usdc, quote.quoteId)
            assertEquals("wallet.insufficient_funds", refused.code())
            assertEquals(max.toString(), refused.field("maxAmountMinor"))
            assertEquals("500000", refused.field("feeMinor"))
        }

    @Test
    fun `a quote that can't estimate the network cost fails, so nothing is ever guessed`() =
        runBlocking<Unit> {
            ready()
            h.custody.estimateFails = true

            assertEquals("wallet.custody_unavailable", h.quote(amina, Payee.Person(bob), 100 * usdc).code())
        }

    // ---- the ledger ----

    @Test
    fun `the ledger has the fee when prepared, collected with the network cost once confirmed`() =
        runBlocking<Unit> {
            ready()
            val prepared = payBob(100 * usdc, quoteBob(100 * usdc).quoteId).data().transfer
            assertEquals(FeeState.QUOTED, ledger.rows[prepared.transferId]?.state)

            val update =
                h
                    .update(
                        h.walletOn(amina),
                        h.circleUserOf(amina),
                        CustodyDirection.OUT,
                        CustodyTransactionState.CONFIRMED,
                        0,
                        prepared.transferId.toString(),
                    ).copy(networkFee = "0.000002")
            h.applyUpdate(update).data()

            assertEquals(FeeState.COLLECTED, ledger.rows[prepared.transferId]?.state)
            assertEquals("0.000002" to 5_000L, ledger.gas[prepared.transferId], "half a cent at 2 500 dollars")
            assertTrue(h.warnings.gasOverShare.isEmpty())
        }

    @Test
    fun `network cost above a quarter of the fee is flagged, and a failed payment's fee is void`() =
        runBlocking<Unit> {
            ready()
            val paid = payBob(100 * usdc, quoteBob(100 * usdc).quoteId).data().transfer
            val expensive =
                h
                    .update(
                        h.walletOn(amina),
                        h.circleUserOf(amina),
                        CustodyDirection.OUT,
                        CustodyTransactionState.CONFIRMED,
                        0,
                        paid.transferId.toString(),
                    ).copy(networkFee = "0.0001")
            h.applyUpdate(expensive).data()
            assertEquals(
                listOf(paid.transferId.toString() to 5_000),
                h.warnings.gasOverShare,
                "25 cents of a 50 cent fee",
            )

            val failed = payBob(50 * usdc, quoteBob(50 * usdc).quoteId).data().transfer
            h
                .applyUpdate(
                    h.update(
                        h.walletOn(amina),
                        h.circleUserOf(amina),
                        CustodyDirection.OUT,
                        CustodyTransactionState.FAILED,
                        0,
                        failed.transferId.toString(),
                    ),
                ).data()
            assertEquals(FeeState.VOID, ledger.rows[failed.transferId]?.state)
        }

    @Test
    fun `the price of a payment is a function of its amount - the cap stops it at 10 000 dollars`() =
        runBlocking<Unit> {
            ready()
            rules.fees.clear()
            rules.fee(200, kind = FeeKind.EXTERNAL)
            h.custody.networkCost = BigDecimal.ZERO

            val outside = Payee.Address("0x" + "1234567890".repeat(4))
            assertEquals(
                2_000_000L,
                h
                    .quote(amina, outside, 100 * usdc)
                    .data()
                    .fee.amountMinor
                    .toLong(),
            )
            assertEquals(200 * usdc, h.fees.pricingFor(h, amina, outside, 20_000 * usdc), "at most 200 dollars")
        }
}

/** The fee W17 would quote for [amount] without the wallet's balance or limits in the way. */
private suspend fun com.circaso.wallet.domain.support.FeeCalculator.pricingFor(
    h: Harness,
    sender: UserId,
    payee: Payee.Address,
    amount: Long,
): Long {
    val wallet = h.walletOn(sender)
    val user = h.wallets.circleUser(sender)!!
    val pricing =
        pricing(
            com.circaso.wallet.domain.models.WalletOwner
                .Person(sender),
            com.circaso.wallet.domain.support
                .Sender(user, wallet),
            com.circaso.wallet.domain.support
                .Destination(null, payee.address.lowercase()),
            com.circaso.shared.Money(amount, com.circaso.shared.Asset.USDC),
        ).data()
    return pricing.feeFor(amount).amountMinor.toLong()
}
