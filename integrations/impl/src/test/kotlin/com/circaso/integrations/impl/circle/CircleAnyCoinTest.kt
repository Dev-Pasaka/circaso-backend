package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PaymentLeg
import com.circaso.integrations.api.SplitTransferRequest
import com.circaso.integrations.api.TransferRequest
import com.circaso.integrations.impl.text
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Any coin a wallet holds is sent (decision 2026-11-07): a token by its contract on the chain, the chain's own coin by
 * Circle's token id (it has no contract), in a split batch a token's `transfer` call or the coin itself as the call's
 * value; and Circle's transactions in any coin are read with their symbol, decimals and contract.
 */
class CircleAnyCoinTest {
    private val circle = FakeCircle()
    private val custody = circle.custody
    private val user = "circle-user-1"
    private val payee = "0x00000000000000000000000000000000000000b0"
    private val feeWallet = "0xfee0000000000000000000000000000000000fee"
    private val eurc = Asset("EURC", 6)
    private val eth = Asset("ETH", 18)
    private val tenthEth: BigInteger = BigInteger.TEN.pow(17)

    private inline fun <reified T> HttpRequestData.sent(): T = circleJson.decodeFromString(text())

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun transfer(amount: Money, token: String?) =
        TransferRequest("BASE-SEPOLIA", "w-1", payee, amount, UUID.randomUUID(), "transfer-1", token)

    @Test
    fun `a token is sent by its contract and chain, in its own decimals`() =
        runBlocking<Unit> {
            circle.reply("POST /v1/w3s/user/transactions/transfer", """{"data":{"challengeId":"challenge-3"}}""")

            custody.prepareTransfer(user, transfer(Money(100_250_000, eurc), EURC)).data()

            val body = circle.requests("POST /v1/w3s/user/transactions/transfer").single().sent<TransferBody>()
            assertEquals(EURC, body.tokenAddress)
            assertEquals("BASE-SEPOLIA", body.blockchain)
            assertNull(body.tokenId)
            assertEquals(listOf("100.25"), body.amounts)
        }

    @Test
    fun `the chain's own coin is sent by Circle's token id for it, read from the wallet`() =
        runBlocking<Unit> {
            circle.reply("GET /v1/w3s/wallets/w-1/balances", BALANCES)
            circle.reply("POST /v1/w3s/user/transactions/transfer", """{"data":{"challengeId":"challenge-4"}}""")

            custody.prepareTransfer(user, transfer(Money(tenthEth, eth), "")).data()

            val sent = circle.requests("POST /v1/w3s/user/transactions/transfer").single()
            val body = sent.sent<TransferBody>()
            assertEquals(ETH_TOKEN_ID, body.tokenId)
            assertFalse("tokenAddress" in sent.text(), "Circle refuses tokenAddress together with tokenId")
            assertFalse("\"blockchain\"" in sent.text())
            assertEquals(listOf("0.1"), body.amounts)
        }

    @Test
    fun `a wallet that never held the chain's own coin has none to send, and Circle isn't asked to`() =
        runBlocking<Unit> {
            circle.reply("GET /v1/w3s/wallets/w-1/balances", """{"data":{"tokenBalances":[]}}""")

            val refused = custody.prepareTransfer(user, transfer(Money(tenthEth, eth), ""))

            assertIs<IntegrationError.InsufficientFunds>((refused as Resource.Error).error)
            assertTrue(circle.requests("POST /v1/w3s/user/transactions/transfer").isEmpty())
        }

    @Test
    fun `a coin named only by a symbol that isn't offered can't be sent - asset not supported`() =
        runBlocking<Unit> {
            val refused = custody.prepareTransfer(user, transfer(Money(1, eurc), token = null))

            assertIs<IntegrationError.AssetNotSupported>((refused as Resource.Error).error)
            assertTrue(circle.clients.requests.isEmpty())
        }

    @Test
    fun `a split batch pays a token by its transfer call, and the chain's own coin as the call's value`() =
        runBlocking<Unit> {
            circle.reply(
                "POST /v1/w3s/user/transactions/contractExecution",
                """{"data":{"challengeId":"challenge-8"}}""",
            )

            fun split(payeeLeg: PaymentLeg, feeLeg: PaymentLeg) =
                SplitTransferRequest(
                    "BASE-SEPOLIA",
                    "w-1",
                    "0x00000000000000000000000000000000000000a1",
                    listOf(payeeLeg, feeLeg),
                    UUID.randomUUID(),
                    "transfer-1",
                )

            custody
                .prepareSplitTransfer(
                    user,
                    split(
                        PaymentLeg(payee, Money(100_000_000, eurc), EURC),
                        PaymentLeg(feeWallet, Money(504_673, eurc), EURC),
                    ),
                ).data()
            custody
                .prepareSplitTransfer(
                    user,
                    split(PaymentLeg(payee, Money(tenthEth, eth), ""), PaymentLeg(feeWallet, Money(5, eth), "")),
                ).data()

            val (tokens, native) =
                circle
                    .requests("POST /v1/w3s/user/transactions/contractExecution")
                    .map { it.sent<ContractExecutionBody>().abiParameters.single() }
            assertEquals(
                listOf(
                    listOf(EURC, "0", Erc20.transfer(payee, 100_000_000)),
                    listOf(EURC, "0", Erc20.transfer(feeWallet, 504_673)),
                ),
                tokens,
            )
            assertEquals(listOf(listOf(payee, tenthEth.toString(), "0x"), listOf(feeWallet, "5", "0x")), native)
        }

    @Test
    fun `Circle's transaction in any coin is read with the coin's symbol, decimals and contract`() =
        runBlocking<Unit> {
            circle.reply("GET /v1/w3s/tokens/$EURC_TOKEN_ID", token(EURC_TOKEN_ID, EURC, "EURC", 6))
            circle.reply("GET /v1/w3s/tokens/$ETH_TOKEN_ID", token(ETH_TOKEN_ID, "", "ETH-SEPOLIA", 18))
            circle.reply("GET /v1/w3s/tokens/$NAMELESS_TOKEN_ID", token(NAMELESS_TOKEN_ID, "0xabc", "", null))
            val transactions = CircleTransactions(CircleTokens(circle.client), circle.settings)
            val inbound =
                TransactionPayload(
                    id = "tx-1",
                    userId = user,
                    walletId = "w-1",
                    blockchain = "BASE-SEPOLIA",
                    transactionType = "INBOUND",
                    state = "COMPLETE",
                    amounts = listOf("12.5"),
                )

            val euro = transactions.translate(inbound.copy(tokenId = EURC_TOKEN_ID)).data()!!
            assertEquals(Money(12_500_000, eurc), euro.amount)
            assertEquals(EURC, euro.tokenAddress)
            assertEquals(CustodyDirection.IN, euro.direction)

            val ether = transactions.translate(inbound.copy(tokenId = ETH_TOKEN_ID, amounts = listOf("20"))).data()!!
            assertEquals(Money(BigInteger.TEN.pow(18) * BigInteger.valueOf(20), eth), ether.amount, "past a Long")
            assertEquals("", ether.tokenAddress)

            assertNull(transactions.translate(inbound.copy(tokenId = NAMELESS_TOKEN_ID)).data(), "Circle doesn't say")
        }

    private fun token(
        id: String,
        address: String,
        symbol: String,
        decimals: Int?,
    ) = """{"data":{"token":{"id":"$id","blockchain":"BASE-SEPOLIA","tokenAddress":"$address",""" +
        """"symbol":"$symbol"${decimals?.let { ",\"decimals\":$it" } ?: ""}}}}"""

    private companion object {
        const val EURC = "0x808456652fdb597867f38412077a9182bf77359f"
        const val EURC_TOKEN_ID = "6a1a8a3b-0000-5000-8000-0000000000e1"
        const val ETH_TOKEN_ID = "979869da-9115-5f7d-917d-12d434e56ae7"
        const val NAMELESS_TOKEN_ID = "00000000-0000-5000-8000-0000000000ff"

        val BALANCES =
            """{"data":{"tokenBalances":[""" +
                """{"token":{"id":"$ETH_TOKEN_ID","blockchain":"BASE-SEPOLIA","tokenAddress":"",""" +
                """"symbol":"ETH-SEPOLIA","decimals":18,"isNative":true},"amount":"0.5"},""" +
                """{"token":{"id":"${FakeCircle.USDC_TOKEN_ID}","blockchain":"BASE-SEPOLIA",""" +
                """"tokenAddress":"${FakeCircle.USDC_ADDRESS}","symbol":"USDC","decimals":6},"amount":"3"}""" +
                """]}}"""
    }
}
