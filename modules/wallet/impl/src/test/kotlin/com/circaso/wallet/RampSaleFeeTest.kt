package com.circaso.wallet

import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.SaleDeposit
import com.circaso.wallet.domain.models.FeeKind
import com.circaso.wallet.domain.models.NoFeeReason
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.PreparedTransfer
import com.circaso.wallet.domain.models.RequestedAmount
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferQuote
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cash Out carries no wallet fee (decision 2026-11-24): a payment that funds the payer's own open off-ramp sale,
 * exactly, is priced at zero with `no_fee_reason` `RAMP_SALE` and goes as a plain transfer (no fee leg; Circle Gas
 * Station sponsors its gas, as for any payment no rule prices). Anything else that touches the sale is priced as any
 * outside payment: another payer, another amount, a sale that no longer waits, no `rampOrderId`.
 */
class RampSaleFeeTest {
    private val rules = MemoryRules()
    private val ledger = MemoryFeeLedger()
    private val h = Harness(ruleRows = rules, ledger = ledger)
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val usdc = 1_000_000L
    private val float = "0x" + "5a3c".repeat(10)
    private val order = UUID.randomUUID()
    private val amount = 10 * usdc

    private suspend fun ready() {
        rules.seedFees()
        // Dev's outside-payment rule as it charged a $1.76 Cash Out on 2026-11-24: 2 % with a $5 floor.
        rules.fees.removeIf { it.kind == FeeKind.EXTERNAL }
        rules.fee(200, kind = FeeKind.EXTERNAL, minFeeMinor = 5 * usdc)
        h.fund(amina, 100 * usdc)
        h.fund(bob, 100 * usdc)
        h.sales.sales[order] =
            SaleDeposit(
                orderId = order,
                payer = amina,
                blockchain = TEST_CHAINS.default,
                address = float,
                tokenAddress = USDC_BASE,
                amountMinor = amount,
                fundBy = h.clock.instant().plus(Duration.ofMinutes(15)),
            )
    }

    private fun command(
        sender: UserId = amina,
        minor: Long = amount,
        rampOrderId: UUID? = order,
        quoteId: UUID? = null,
        to: String = float,
    ) = TransferCommand(
        senderId = sender,
        blockchain = TEST_CHAINS.default,
        payee = Payee.Address(to),
        amount = RequestedAmount.of(Money(minor, Asset.USDC)),
        chatId = null,
        proposalId = null,
        idempotencyKey = UUID.randomUUID(),
        quoteId = quoteId,
        rampOrderId = rampOrderId,
    )

    private suspend fun quote(command: TransferCommand): TransferQuote = h.quoteTransfer(command).data()

    private suspend fun pay(command: TransferCommand): Resource<PreparedTransfer> = h.prepareTransfer(command)

    private fun assertPriced(quote: TransferQuote) {
        assertTrue(quote.fee.amountMinor > BigInteger.ZERO, "priced as any outside payment")
        assertNull(quote.fee.reason)
    }

    @Test
    fun `funding your own open sale exactly is free - quoted and sent with no fee leg, the reason recorded`() =
        runBlocking<Unit> {
            ready()

            val quote = quote(command())
            assertEquals(BigInteger.ZERO, quote.fee.amountMinor)
            assertEquals(NoFeeReason.RAMP_SALE, quote.fee.reason)
            assertEquals(BigInteger.valueOf(amount), quote.totalMinor)
            assertNull(quote.problem)
            assertEquals(0, h.custody.count("estimateSplit"), "no fee leg, so no gas estimate to price one")

            val sent = pay(command(quoteId = quote.quoteId)).data().transfer
            assertEquals(BigInteger.ZERO, sent.feeMinor)
            assertNull(sent.feeAddress)
            assertEquals(1, h.custody.count("prepareTransfer"), "a plain transfer: Gas Station sponsors its gas")
            assertEquals(0, h.custody.count("prepareSplitTransfer"))
            assertEquals(
                NoFeeReason.RAMP_SALE,
                ledger.rows
                    .getValue(sent.transferId)
                    .fee.reason,
            )
        }

    @Test
    fun `W4 without a quote is free too`() =
        runBlocking<Unit> {
            ready()

            val sent = pay(command()).data()

            assertEquals(BigInteger.ZERO, sent.transfer.feeMinor)
            assertEquals(NoFeeReason.RAMP_SALE, sent.fee?.reason)
        }

    @Test
    fun `someone else sending to the same deposit address, naming the same sale, is priced`() =
        runBlocking<Unit> {
            ready()

            assertPriced(quote(command(sender = bob)))
        }

    @Test
    fun `any other amount is priced - more or less than the sale needs`() =
        runBlocking<Unit> {
            ready()

            assertPriced(quote(command(minor = amount + usdc)))
            assertPriced(quote(command(minor = amount - usdc)))
        }

    @Test
    fun `another address, or no sale named, is priced`() =
        runBlocking<Unit> {
            ready()

            assertPriced(quote(command(to = "0x" + "1234567890".repeat(4))))
            assertPriced(quote(command(rampOrderId = null)))
            assertPriced(quote(command(rampOrderId = UUID.randomUUID())))
        }

    @Test
    fun `a sale past its deadline is priced, and a free quote held for it no longer holds`() =
        runBlocking<Unit> {
            ready()
            val free = quote(command())
            assertEquals(BigInteger.ZERO, free.fee.amountMinor)

            h.clock.advance(Duration.ofMinutes(16))

            assertPriced(quote(command()))
            val refused = pay(command(quoteId = free.quoteId))
            assertEquals("wallet.fee_changed", (refused as Resource.Error).error.code, "the fee is shown first")
        }

    @Test
    fun `the minimum fee still applies to an ordinary outside payment of the same size`() =
        runBlocking<Unit> {
            ready()

            val ordinary = quote(command(rampOrderId = null))

            assertEquals(BigInteger.valueOf(5 * usdc), ordinary.fee.amountMinor, "the 5 dollar floor of 2026-11-24")
            assertTrue(ordinary.fee.minimum)
        }
}
