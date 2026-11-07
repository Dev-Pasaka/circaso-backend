package com.circaso.wallet

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.usecases.EstimateFee
import com.circaso.wallet.domain.usecases.ValueAssets
import com.circaso.wallet.presentation.mappers.AgentFeeParams
import com.circaso.wallet.presentation.mappers.assetValueQuery
import com.circaso.wallet.presentation.mappers.toAgentResponse
import com.circaso.wallet.presentation.mappers.toCommand
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asha's value and fee routes (decision 2026-11-05) over in-memory fakes: values come from the wallet's own rate, the
 * one balances, payments and fees use; a fee estimate is W17's numbers with nothing held.
 */
class AgentValueTest {
    private val rates = FakeRates().apply { rates["KES"] = BigDecimal("129.50") }
    private val prices = FakePrices()
    private val values = ValueAssets(rates, prices)

    private suspend fun valued(
        assets: String? = null,
        amount: String? = null,
        currency: String? = null,
    ) = values(assetValueQuery(assets, amount, currency).data()).data().toAgentResponse()

    @Test
    fun `a stablecoin is one dollar times the wallet's rate, for one coin and for the amount asked`() =
        runBlocking<Unit> {
            val answer = valued("usdc", "20", "kes")

            assertEquals("KES", answer.currency)
            assertEquals("circaso", answer.source)
            val usdc = answer.values.single()
            assertEquals("USDC", usdc.asset)
            assertTrue(usdc.priced)
            assertEquals("1", usdc.usdPrice)
            assertEquals("129.50", usdc.unitValue)
            assertEquals("2590.00", usdc.value)
            assertEquals("129.50", answer.rate?.usdRate)
            assertEquals(rates.asOf.toString(), answer.rate?.asOf)
        }

    @Test
    fun `no names is every supported asset, a coin we don't hold is unpriced, and no rate is US dollars`() =
        runBlocking<Unit> {
            assertEquals(listOf("USDC", "USDT"), valued().values.map { it.asset })

            val btc = valued("BTC,usdt").values
            assertFalse(btc.first().priced)
            assertNull(btc.first().unitValue, "the price feed has no BTC here")
            assertTrue(btc.last().priced)

            val unknown = valued("USDC", "3", "UGX")
            assertEquals("USD", unknown.currency, "no UGX rate known: the answer says it is in dollars")
            assertNull(unknown.rate)
            assertEquals("3.00", unknown.values.single().value)
        }

    @Test
    fun `another coin is valued at the wallet's price feed, the one W1 shows balances with`() =
        runBlocking<Unit> {
            prices.prices["ETH"] = BigDecimal("3500")

            val answer = valued("ETH,usdc", "0.5", "KES")

            val eth = answer.values.first()
            assertEquals("ETH", eth.asset)
            assertTrue(eth.priced)
            assertEquals("3500", eth.usdPrice)
            assertEquals("453250.00", eth.unitValue, "3500 x 129.50")
            assertEquals("226625.00", eth.value)
            assertEquals(listOf(setOf("ETH")), prices.asked, "stablecoins never ask the feed")
        }

    @Test
    fun `malformed arguments name their field`() {
        assertMalformed(assetValueQuery(null, "-1", null), "amount")
        assertMalformed(assetValueQuery(null, "lots", null), "amount")
        assertMalformed(assetValueQuery(null, null, "XXXX"), "currency")
        assertMalformed(assetValueQuery((1..11).joinToString(",") { "A$it" }, null, null), "assets")
        val amina = UserId.new()
        assertMalformed(AgentFeeParams(amount = "1", toAddress = "0xabc").toCommand(amina), "blockchain")
        assertMalformed(AgentFeeParams(amount = "1", blockchain = "BASE").toCommand(amina), "to")
        assertMalformed(
            AgentFeeParams(amount = "1", blockchain = "BASE", toUserId = "x", toAddress = "0x").toCommand(amina),
            "to",
        )
        assertMalformed(AgentFeeParams(blockchain = "BASE", toAddress = "0xabc").toCommand(amina), "amount")
        val command = AgentFeeParams(amount = "2.5", blockchain = "base", toAddress = " 0xabc ").toCommand(amina).data()
        assertEquals(2_500_000.toBigInteger(), command.amount.minorIn(6))
        assertEquals("BASE", command.blockchain)
        assertEquals(Payee.Address("0xabc"), command.payee)
    }

    @Test
    fun `a fee estimate is W17's fee and limits with fiat values, and holds no quote`() =
        runBlocking<Unit> {
            val rules = MemoryRules().apply { seedFees() }
            val quotes = MemoryFeeQuotes()
            val h = Harness(ruleRows = rules, quoteRows = quotes)
            h.rates.rates["KES"] = BigDecimal("129.50")
            val amina = UserId.new()
            val bob = UserId.new()
            h.fund(amina, 400_000_000)
            h.walletOn(bob)
            h.reach.connect(amina, bob)
            val estimate = EstimateFee(h.quoter, h.rates)
            val command =
                AgentFeeParams(
                    amount = "100",
                    blockchain = "BASE-SEPOLIA",
                    toUserId = bob.toString(),
                ).toCommand(amina).data()

            val answer = estimate(command, "KES").data().toAgentResponse()

            assertEquals("100", answer.amount)
            assertEquals("0.5", answer.fee, "0.5 % between Circaso people")
            assertEquals(50, answer.rateBps)
            assertEquals("100.5", answer.total)
            assertEquals("400", answer.balance)
            assertNull(answer.problem)
            assertEquals("13014.75", answer.fiatTotal)
            assertEquals("64.75", answer.fiatFee)
            assertTrue(quotes.rows.isEmpty(), "nothing is held for an estimate")
            assertIs<Resource.Success<*>>(estimate(command, null))
            assertTrue(quotes.rows.isEmpty())
        }

    private fun assertMalformed(result: Resource<*>, field: String) {
        val error = assertIs<Resource.Error>(result)
        assertEquals("request.malformed", error.error.code)
        assertEquals(
            field,
            error.error.details
                ?.fields
                ?.firstOrNull()
                ?.field,
        )
    }
}
