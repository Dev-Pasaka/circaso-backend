package com.circaso.wallet

import com.circaso.integrations.api.AssetBalance
import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.decode
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.PreparedTransfer
import com.circaso.wallet.domain.models.RequestedAmount
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferQuote
import com.circaso.wallet.domain.support.PaymentCoin
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Any coin a wallet holds can be paid (decision 2026-11-07), through the same W17 → W4 → batch as USDC: EURC (a known
 * token Base Sepolia doesn't offer), ETH (Base Sepolia's own coin), at the price feed's price. The fee is the dollar
 * fee converted into the coin and rounded up; the limits count the payment in dollars; a coin with no price, or not
 * held, is refused with its own code; and USDC is priced exactly as before, without asking the feed.
 *
 * The fake network costs 0.000004 ETH at 2 500 dollars, one cent, so the gas floor is 5 cents.
 */
class AnyCoinTest {
    private val rules = MemoryRules()
    private val h = Harness(ruleRows = rules)
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val usdc = 1_000_000L

    private val eurc = Asset("EURC", 6)
    private val eth = Asset("ETH", 18)
    private val oneEth: BigInteger = BigInteger.TEN.pow(18)

    private suspend fun ready() {
        rules.seedFees()
        h.fund(amina, 400 * usdc)
        h.walletOn(bob)
        h.reach.connect(amina, bob)
    }

    /** Amina's Base wallet holds [minor] of a token at [address] that calls itself [symbol]. */
    private suspend fun holdToken(
        symbol: String,
        address: String,
        minor: BigInteger,
        decimals: Int = 6,
    ) {
        val wallet = h.walletOn(amina)
        h.custody.heldOnly[wallet.circleWalletId] =
            listOf(AssetBalance(CustodyAsset(symbol, address, decimals, usdPegged = false), minor))
    }

    /** Amina's Base wallet holds [usdcMinor] USDC and [wei] of ETH, the chain's own coin. */
    private suspend fun holdEth(wei: BigInteger, usdcMinor: Long = 400 * usdc) {
        val wallet = h.walletOn(amina)
        h.custody.held[wallet.circleWalletId] = mapOf("USDC" to BigInteger.valueOf(usdcMinor), "ETH" to wei)
    }

    private fun command(
        amount: Money,
        quoteId: UUID? = null,
        payee: Payee = Payee.Person(bob),
    ) = TransferCommand(amina, "BASE-SEPOLIA", payee, amount, null, null, UUID.randomUUID(), quoteId = quoteId)

    private suspend fun quote(amount: Money): Resource<TransferQuote> = h.quoteTransfer(command(amount))

    private suspend fun pay(amount: Money, quoteId: UUID? = null): Resource<PreparedTransfer> =
        h.prepareTransfer(command(amount, quoteId))

    /** W17 then W4 with its quote, as the app does: the fee shown is the fee charged. */
    private suspend fun quotedPay(amount: Money): Resource<PreparedTransfer> =
        when (val quoted = quote(amount)) {
            is Resource.Success -> pay(amount, quoted.data.quoteId)
            is Resource.Error -> quoted
        }

    // ---- the fee, in the coin ----

    @Test
    fun `an EURC payment pays the dollar fee in EURC at the feed's price, rounded up to the coin's minor unit`() =
        runBlocking<Unit> {
            ready()
            holdToken("EURC", EURC_BASE_SEPOLIA, 200_000_000.toBigInteger())
            h.prices.prices["EURC"] = BigDecimal("1.07")

            val quote = quote(Money(100_000_000, eurc)).data()

            // 100 EURC is 107 dollars; 0.5 % is 0.535, half up to the cent 0.54; 0.54 ÷ 1.07 = 0.504672… EURC → up.
            assertEquals(540_000L, quote.fee.usdMinor)
            assertEquals(504_673.toBigInteger(), quote.fee.amountMinor)
            assertEquals(eurc, quote.amount.asset)
            assertEquals(BigDecimal("1.07"), quote.fee.usdPrice)
            assertEquals(107_000_000L + 540_000L, quote.totalUsdMinor, "the limits count it in dollars")
            assertEquals(200_000_000.toBigInteger(), quote.balanceMinor)
            assertNull(quote.problem)
        }

    @Test
    fun `W4 sends the EURC batch - the payee and the fee leg in EURC by its contract, one signature`() =
        runBlocking<Unit> {
            ready()
            holdToken("EURC", EURC_BASE_SEPOLIA, 200_000_000.toBigInteger())
            h.prices.prices["EURC"] = BigDecimal("1.07")
            val quote = quote(Money(100_000_000, eurc)).data()

            val prepared = pay(Money(100_000_000, eurc), quote.quoteId).data()

            val transfer = prepared.transfer
            assertEquals(Money(100_000_000, eurc), transfer.amount)
            assertEquals(504_673.toBigInteger(), transfer.feeMinor)
            assertEquals(EURC_BASE_SEPOLIA, transfer.tokenAddress)
            assertEquals(BigDecimal("1.07"), transfer.usdPrice)
            assertEquals(107_540_000L, transfer.usdTotalMinor)
            val (_, batch) = h.custody.splits.single()
            assertEquals(
                listOf(
                    h.walletOn(bob).address.lowercase() to 100_000_000.toBigInteger(),
                    FakeFeeConfig.FEE_WALLET to 504_673.toBigInteger(),
                ),
                batch.legs.map { it.destinationAddress to it.amount.amountMinor },
            )
            assertTrue(batch.legs.all { it.tokenAddress == EURC_BASE_SEPOLIA && it.amount.asset == eurc })
            assertEquals(107_540_000L, h.used(amina), "the window counts dollars")
        }

    @Test
    fun `a price move after the quote changes neither the fee that is signed nor what the limits count`() =
        runBlocking<Unit> {
            ready()
            holdToken("EURC", EURC_BASE_SEPOLIA, 200_000_000.toBigInteger())
            h.prices.prices["EURC"] = BigDecimal("1.07")
            val quote = quote(Money(100_000_000, eurc)).data()

            h.prices.prices["EURC"] = BigDecimal("1.20")
            val transfer = pay(Money(100_000_000, eurc), quote.quoteId).data().transfer

            assertEquals(504_673.toBigInteger(), transfer.feeMinor, "the quote's fee")
            assertEquals(BigDecimal("1.07"), transfer.usdPrice, "at the quote's price")
            assertEquals(107_540_000L, transfer.usdTotalMinor)
        }

    @Test
    fun `without a held quote a price fall that raises the fee in the coin is fee_changed`() =
        runBlocking<Unit> {
            ready()
            holdToken("EURC", EURC_BASE_SEPOLIA, 200_000_000.toBigInteger())
            h.prices.prices["EURC"] = BigDecimal("1.07")
            // 1 EURC pays the 5 cent floor: 0.05 ÷ 1.07 = 0.04672897… EURC, up to 46 729 minor units.
            val quote = quote(Money(1_000_000, eurc)).data()
            assertEquals(46_729.toBigInteger(), quote.fee.amountMinor)
            h.clock.advance(java.time.Duration.ofMinutes(16))
            h.prices.prices["EURC"] = BigDecimal("0.90")

            // Now the same 5 cents is 0.055556 EURC: more than was shown, so nothing is prepared.
            val refused = pay(Money(1_000_000, eurc), quote.quoteId)
            assertEquals("wallet.fee_changed", refused.code())
            assertEquals("55556", field(refused, "feeMinor"))
            assertTrue(h.custody.splits.isEmpty())
        }

    @Test
    fun `ETH, the chain's own coin, pays its fee in ETH, and its legs carry no contract`() =
        runBlocking<Unit> {
            ready()
            holdEth(oneEth)
            h.prices.prices["ETH"] = BigDecimal("3000")
            val tenth = oneEth / BigInteger.TEN

            val quote = quote(Money(tenth, eth)).data()
            // 0.1 ETH is 300 dollars; 0.5 % is 1.50; 1.5 ÷ 3 000 = 0.0005 ETH.
            assertEquals(1_500_000L, quote.fee.usdMinor)
            assertEquals(BigInteger.valueOf(5).multiply(BigInteger.TEN.pow(14)), quote.fee.amountMinor)

            pay(Money(tenth, eth), quote.quoteId).data()
            val (_, batch) = h.custody.splits.single()
            assertTrue(batch.legs.all { it.tokenAddress == "" && it.amount.asset == eth })
        }

    @Test
    fun `an 18-decimal amount past what a Long holds is paid exactly`() =
        runBlocking<Unit> {
            ready()
            rules.limits.clear()
            val twenty = oneEth * BigInteger.valueOf(20)
            holdEth(twenty * BigInteger.TWO)
            h.prices.prices["ETH"] = BigDecimal("3000")

            val transfer = quotedPay(Money(twenty, eth)).data().transfer

            assertEquals(twenty, transfer.amount.amountMinor)
            // 0.5 % of at most 10 000 dollars (the rule's cap on the base): 50 dollars.
            assertEquals(60_000_000_000L + 50_000_000L, transfer.usdTotalMinor, "60 000 dollars and its fee")
        }

    // ---- the limits, in dollars ----

    @Test
    fun `one payment's cap and the daily window count a coin at its price`() =
        runBlocking<Unit> {
            ready()
            holdEth(oneEth, usdcMinor = 1_000 * usdc)
            h.prices.prices["ETH"] = BigDecimal("3000")

            // 0.2 ETH is 600 dollars, over the 500 dollar cap on one payment.
            val over = pay(Money(oneEth / BigInteger.valueOf(5), eth))
            assertEquals("wallet.limit_exceeded", over.code())
            assertEquals("PER_PAYMENT", field(over, "limit"))
            assertEquals("500000000", field(over, "limitMinor"), "in micro-dollars")
            // The most ETH within 500 dollars, by the same rounding.
            val most =
                BigDecimal("500.0000005")
                    .divide(BigDecimal("3000"), 30, RoundingMode.CEILING)
                    .movePointRight(18)
                    .setScale(0, RoundingMode.CEILING)
                    .toBigInteger() - BigInteger.ONE
            assertEquals(most, field(over, "maxAmountMinor")!!.toBigInteger())

            // 0.16 ETH is 480 dollars and its fee 2.40: 482.40 of the 1 000 a day.
            quotedPay(Money(oneEth * BigInteger.valueOf(16) / BigInteger.valueOf(100), eth)).data()
            assertEquals(482_400_000L, h.used(amina))
            quotedPay(Money(500 * usdc, Asset.USDC)).data()
            assertEquals(482_400_000L + 502_500_000L, h.used(amina), "USDC counts as it always did")
            val daily = quotedPay(Money(20 * usdc, Asset.USDC))
            assertEquals("wallet.limit_exceeded", daily.code())
            assertEquals("DAILY", field(daily, "limit"))
        }

    @Test
    fun `W17 shows a coin's limits and Send all in dollars at its price`() =
        runBlocking<Unit> {
            ready()
            holdEth(oneEth)
            h.prices.prices["ETH"] = BigDecimal("3000")

            val quote = quote(Money(oneEth / BigInteger.valueOf(5), eth)).data()

            assertEquals(com.circaso.wallet.domain.models.QuoteProblem.PER_PAYMENT_LIMIT, quote.problem)
            assertEquals(500_000_000L, quote.allowance.perPaymentMaxMinor)
            // The most that fits: within 500 dollars of ETH, its fee on top within the balance.
            val max = quote.maxAmountMinor
            assertTrue(PaymentCoin(eth, "", BigDecimal("3000")).usdMinor(max) <= 500_000_000L)
            assertTrue(PaymentCoin(eth, "", BigDecimal("3000")).usdMinor(max + BigInteger.TEN.pow(13)) > 500_000_000L)
        }

    // ---- refusals ----

    @Test
    fun `a coin with no price right now can't be sent - asset_unpriced`() =
        runBlocking<Unit> {
            ready()
            holdToken("EURC", EURC_BASE_SEPOLIA, 200_000_000.toBigInteger())

            assertEquals("wallet.asset_unpriced", quote(Money(1_000_000, eurc)).code(), "the feed has no price")
            assertEquals("wallet.asset_unpriced", pay(Money(1_000_000, eurc)).code())
            assertTrue(h.custody.splits.isEmpty() && h.custody.transfers.isEmpty(), "nothing asked of Circle")
        }

    @Test
    fun `a token that only calls itself a known coin is unpriced, whatever the feed says of the name`() =
        runBlocking<Unit> {
            ready()
            holdToken("EURC", "0x00000000000000000000000000000000000bad01", 200_000_000.toBigInteger())
            h.prices.prices["EURC"] = BigDecimal("1.07")

            assertEquals("wallet.asset_unpriced", pay(Money(1_000_000, eurc)).code())
        }

    @Test
    fun `a coin the wallet doesn't hold on the chain is asset_not_held, and the chain's own coin at zero is short`() =
        runBlocking<Unit> {
            ready()
            h.prices.prices["SOL"] = BigDecimal("150")
            val sol = Money(1_000_000_000, Asset("SOL", 9))

            assertEquals("wallet.asset_not_held", pay(sol).code())
            assertEquals("wallet.asset_not_held", quote(sol).code())
            // ETH is offered on Base Sepolia, so a wallet without any is simply short, as USDC at zero is.
            h.prices.prices["ETH"] = BigDecimal("3000")
            assertEquals("wallet.insufficient_funds", quotedPay(Money(oneEth / BigInteger.TEN, eth)).code())
        }

    @Test
    fun `an amount in whole coins is read in the coin's own decimals`() =
        runBlocking<Unit> {
            ready()
            holdEth(oneEth)
            h.prices.prices["ETH"] = BigDecimal("3000")
            val whole =
                TransferCommand(
                    amina,
                    "BASE-SEPOLIA",
                    Payee.Person(bob),
                    RequestedAmount("ETH", null, BigDecimal("0.000000000000000001")),
                    null,
                    null,
                    UUID.randomUUID(),
                )

            assertEquals(Money(BigInteger.ONE, eth), h.quoteTransfer(whole).data().amount)
            val tooFine = whole.copy(amount = RequestedAmount("ETH", null, BigDecimal("0.0000000000000000001")))
            assertEquals("wallet.amount_invalid", h.quoteTransfer(tooFine).code())
        }

    // ---- USDC is as it was ----

    @Test
    fun `USDC is priced as before, one dollar, and the feed is never asked`() =
        runBlocking<Unit> {
            ready()
            val quote = h.quote(amina, Payee.Person(bob), 100 * usdc).data()

            assertEquals(500_000.toBigInteger(), quote.fee.amountMinor)
            assertEquals(500_000L, quote.fee.usdMinor)
            assertEquals(BigDecimal.ONE, quote.fee.usdPrice)
            assertEquals(100_500_000L, quote.totalUsdMinor)
            val transfer = quotedPay(Money(100 * usdc, Asset.USDC)).data().transfer
            assertEquals(USDC_BASE, transfer.tokenAddress)
            assertEquals(100_500_000L, transfer.usdTotalMinor)
            assertTrue(h.prices.asked.isEmpty(), "a dollar stablecoin needs no price")
        }

    @Test
    fun `the confirmed event says what the payment was worth in dollars and its decimals`() =
        runBlocking<Unit> {
            ready()
            holdToken("EURC", EURC_BASE_SEPOLIA, 200_000_000.toBigInteger())
            h.prices.prices["EURC"] = BigDecimal("1.07")
            val transfer = quotedPay(Money(100_000_000, eurc)).data().transfer

            h
                .applyUpdate(
                    h.update(
                        h.walletOn(amina),
                        h.circleUserOf(amina),
                        CustodyDirection.OUT,
                        CustodyTransactionState.CONFIRMED,
                        0,
                        refId = transfer.transferId.toString(),
                    ),
                ).data()

            val confirmed =
                h.bus.published
                    .single { it.type == TransferConfirmed.TYPE }
                    .decode<TransferConfirmed>()
            assertEquals("EURC", confirmed.amount.asset)
            assertEquals("107000000", confirmed.usdValueMinor)
            assertEquals(6, confirmed.assetDecimals)
        }

    // ---- what reaches the history ----

    @Test
    fun `money in a trusted coin reaches the history, a token that calls itself USDC does not`() =
        runBlocking<Unit> {
            ready()
            val wallet = h.walletOn(bob)
            val circleUser = h.circleUserOf(bob)

            fun inbound(asset: Asset, token: String?) =
                h
                    .update(wallet, circleUser, CustodyDirection.IN, CustodyTransactionState.CONFIRMED, 0)
                    .copy(amount = Money(5_000_000, asset), tokenAddress = token)

            h.applyUpdate(inbound(Asset.USDC, "0x00000000000000000000000000000000000bad02")).data()
            h.applyUpdate(inbound(eurc, EURC_BASE_SEPOLIA)).data()
            h.applyUpdate(inbound(eth, "")).data()

            val rows = (h.transactions as MemoryTransactionRepository).rows.filter { it.userId == bob }
            assertEquals(listOf("EURC", "ETH"), rows.map { it.amount.asset.symbol })
        }

    // ---- the conversion itself ----

    @Test
    fun `a coin's dollar value rounds half up, and dollars in the coin round up`() {
        val ethAt = PaymentCoin(eth, "", BigDecimal("2999.99"))
        // 1.5 dollars of ETH at 2 999.99 is 0.000500001666672222… ETH: up to the wei.
        val fee = ethAt.minorOf(1_500_000)
        val exact = BigDecimal("1.5").divide(BigDecimal("2999.99"), 18, RoundingMode.CEILING).movePointRight(18)
        assertEquals(exact.toBigInteger(), fee)
        assertTrue(ethAt.usdMinor(fee) >= 1_500_000, "the coin never covers less than the dollar fee")
        assertEquals(0.toBigInteger(), ethAt.minorOf(0))

        val usdcAt = PaymentCoin.dollar(Asset.USDC)
        assertEquals(1_234_567L, usdcAt.usdMinor(1_234_567.toBigInteger()), "USDC: a minor unit is a micro-dollar")
        assertEquals(1_234_567.toBigInteger(), usdcAt.minorOf(1_234_567))
        assertEquals(500_000_000.toBigInteger(), usdcAt.maxWithinUsd(500_000_000))
    }

    @Test
    fun `EURC is trusted and priced at every address Circle lists for it, as Circle writes it`() {
        // developers.circle.com/stablecoins/eurc-contract-addresses, 2026-11-07, verbatim (checksummed).
        val circleList =
            listOf(
                "0x1aBaEA1f7C830bD89Acc67eC4af516284b1bC33c", // Ethereum
                "0x60a3E35Cc302bFA44Cb288Bc5a4F316Fdb1adb42", // Base
                "0xC891EB4cbdEFf6e073e859e987815Ed1505c2ACD", // Avalanche
                "0xbEf5f6d51CB62b58e6A8f77868681825C6fe21c1", // Arc
                "0xA6dE01a2d62C6B5f3525d768f34d276652C554c8", // Cronos
                "0x3EE196E78d4d4248b849B8E1C7F44C5457FAFD2C", // Plasma
                "0x1C60ba0A0eD1019e8Eb035E6daF4155A5cE2380B", // World Chain
                "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr", // Solana and Solana Devnet
                "0x08210F9170F89Ab7658F0B5E3fF39b0E03C594D4", // Ethereum Sepolia
                "0x808456652fdb597867f38412077A9182bf77359F", // Base Sepolia
                "0x5E44db7996c682E92a960b65AC713a54AD815c6B", // Avalanche Fuji
                "0x89B50855Aa3bE2F677cD6303Cec089B5F319D72a", // Arc Testnet
                "0x31f7538adb53cF16350e6B0c89d03D91b7D12c46", // Cronos Testnet
                "0x98AfA0F93Dd993B736399f9074eDcEBD1985A330", // Plasma Testnet
                "0xe479EcA5740Ac65d6E1823bea2f1C08Bc14e954F", // World Chain Sepolia
            )
        for (chain in listOf("BASE-SEPOLIA", "SOL-DEVNET", "MATIC-AMOY")) {
            for (address in circleList) {
                assertEquals(
                    "EURC",
                    com.circaso.wallet.domain.support.CoinTrust
                        .priceKey(TEST_CHAINS, chain, "EURC", address, usdPegged = false),
                    "$address on $chain",
                )
            }
        }
    }

    @Test
    fun `W1 prices EURC held on Base Sepolia as Circle writes its address, and it can then be quoted`() =
        runBlocking<Unit> {
            ready()
            h.prices.prices["EURC"] = BigDecimal("1.16")
            holdToken("EURC", "0x808456652fdb597867f38412077A9182bf77359F", 50_000_000.toBigInteger())
            h.cache.clear(h.walletOn(amina).circleWalletId) // the 15-second balance cache, read before it came in

            val view =
                h
                    .getWallet(
                        com.circaso.wallet.domain.models
                            .WalletQuery(amina, null),
                    ).data()
            val line = view.chains.flatMap { it.holdings }.single { it.symbol == "EURC" }
            assertEquals(0, BigDecimal("1.16").compareTo(line.usdPrice), "W1 shows its money value")
            assertNull(quote(Money(1_000_000, eurc)).let { (it as? Resource.Error)?.error }, "and W17 quotes it")
        }

    private fun field(result: Resource<*>, name: String): String? =
        (result as Resource.Error)
            .error.details
            ?.fields
            ?.firstOrNull { it.field == name }
            ?.problem

    private companion object {
        /** Circle's EURC on Base Sepolia, as the issuer lists it. */
        const val EURC_BASE_SEPOLIA = "0x808456652fdb597867f38412077a9182bf77359f"
    }
}
