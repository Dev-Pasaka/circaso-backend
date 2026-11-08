package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyOperation
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PaymentLeg
import com.circaso.integrations.api.SplitTransferRequest
import com.circaso.integrations.impl.text
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2F's one-challenge payment with Circaso's fee (fee spike, 2026-11-03): `executeBatch` on the sender's own
 * smart-contract wallet with one USDC `transfer` per leg, estimated first as the user; Solana can't; and the batch's
 * contract-execution update is read as one, whether or not Circle kept our `refId` (it doesn't, 2026-11-05).
 */
class CircleSplitTest {
    private val circle = FakeCircle()
    private val custody = circle.custody
    private val user = "circle-user-1"
    private val payee = "0x00000000000000000000000000000000000000b0"
    private val feeWallet = "0xfee0000000000000000000000000000000000fee"

    private inline fun <reified T> HttpRequestData.sent(): T = circleJson.decodeFromString(text())

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun request(key: UUID = UUID.randomUUID(), blockchain: String = "BASE-SEPOLIA") =
        SplitTransferRequest(
            blockchain = blockchain,
            walletId = "w-1",
            walletAddress = "0x00000000000000000000000000000000000000a1",
            legs =
                listOf(
                    PaymentLeg(payee, Money(100_000_000, Asset.USDC)),
                    PaymentLeg(feeWallet, Money(500_000, Asset.USDC)),
                ),
            idempotencyKey = key,
            refId = "transfer-1",
        )

    @Test
    fun `ERC-20 transfer calls are encoded as the batch takes them`() {
        assertEquals(
            "0xa9059cbb" + "00000000000000000000000000000000000000000000000000000000000000b0".padStart(64, '0') +
                "0000000000000000000000000000000000000000000000000000000005f5e100",
            Erc20.transfer(payee, 100_000_000),
        )
        assertNull(Erc20.transfer("So1abc", 1), "not an EVM address")
    }

    @Test
    fun `smart-contract wallet chains can split, Solana can't`() {
        assertTrue(custody.canSplit("BASE-SEPOLIA"))
        assertTrue(custody.canSplit("MATIC-AMOY"))
        assertFalse(custody.canSplit("SOL-DEVNET"))
        assertFalse(custody.canSplit("NOT-A-CHAIN"))
    }

    @Test
    fun `prepareSplitTransfer is one executeBatch on the sender's own wallet, with our key and refId`() =
        runBlocking<Unit> {
            circle.reply(
                "POST /v1/w3s/user/transactions/contractExecution",
                """{"data":{"challengeId":"challenge-7"}}""",
            )
            val key = UUID.randomUUID()

            val challenge = custody.prepareSplitTransfer(user, request(key)).data()

            assertEquals("challenge-7", challenge.challengeId)
            val sent = circle.requests("POST /v1/w3s/user/transactions/contractExecution").single()
            assertEquals(circle.tokenOf(1), sent.headers["X-User-Token"])
            assertEquals(key.toString(), sent.headers["Idempotency-Key"])
            val body = sent.sent<ContractExecutionBody>()
            assertEquals("0x00000000000000000000000000000000000000a1", body.contractAddress, "its own address")
            assertEquals("executeBatch((address,uint256,bytes)[])", body.abiFunctionSignature)
            assertEquals("MEDIUM", body.feeLevel)
            assertEquals("transfer-1", body.refId)
            val calls = body.abiParameters.single()
            assertEquals(2, calls.size)
            for (call in calls) {
                val (token, value, _) = call
                assertEquals(FakeCircle.USDC_ADDRESS, token)
                assertEquals("0", value)
            }
            assertEquals(Erc20.transfer(feeWallet, 500_000), calls[1][2])
        }

    @Test
    fun `estimateSplit asks for the same batch as the user and answers the medium network fee`() =
        runBlocking<Unit> {
            circle.reply(
                "POST /v1/w3s/transactions/contractExecution/estimateFee",
                """{"data":{"low":{"networkFee":"0.000003"},"medium":{"networkFee":"0.00000371","gasLimit":"322280"},"high":{"networkFee":"0.000005"}}}""",
            )

            val cost = custody.estimateSplit(user, request()).data()

            assertEquals(BigDecimal("0.00000371"), cost.amount)
            assertEquals("ETH", cost.symbol)
            val sent = circle.requests("POST /v1/w3s/transactions/contractExecution/estimateFee").single()
            assertEquals(circle.tokenOf(1), sent.headers["X-User-Token"])
            assertFalse("idempotencyKey" in sent.text(), "nothing is created, so the body takes no key")
            assertEquals("w-1", sent.sent<EstimateContractBody>().walletId)
        }

    @Test
    fun `a batch Circle can't run (a leg overdraws) is too little money, and Solana is refused without a call`() =
        runBlocking<Unit> {
            circle.reply(
                "POST /v1/w3s/transactions/contractExecution/estimateFee",
                """{"code":155208,"message":"Estimate fee execution reverted."}""",
                400,
            )
            assertIs<IntegrationError.InsufficientFunds>(
                (custody.estimateSplit(user, request()) as Resource.Error).error,
            )

            val solana = custody.prepareSplitTransfer(user, request(blockchain = "SOL-DEVNET"))
            assertIs<IntegrationError.Rejected>((solana as Resource.Error).error)
            assertTrue(circle.requests("POST /v1/w3s/user/transactions/contractExecution").isEmpty())
        }

    @Test
    fun `a batch's contract-execution update is read as one, with or without a refId, and an inbound one is ignored`() =
        runBlocking<Unit> {
            val transactions = CircleTransactions(CircleTokens(circle.client), circle.settings)
            val ours =
                TransactionPayload(
                    id = "tx-1",
                    userId = user,
                    walletId = "w-1",
                    blockchain = "BASE-SEPOLIA",
                    transactionType = "OUTBOUND",
                    state = "COMPLETE",
                    refId = "transfer-1",
                    txHash = "0xhash",
                    operation = "CONTRACT_EXECUTION",
                    networkFee = "0.00000133",
                )

            val update = transactions.translate(ours).data()!!

            assertEquals(CustodyDirection.OUT, update.direction)
            assertEquals("transfer-1", update.refId)
            assertEquals("0.00000133", update.networkFee)
            assertEquals(0.toBigInteger(), update.amount.amountMinor, "the transfer knows the amounts")
            assertEquals(CustodyOperation.CONTRACT_EXECUTION, update.operation)

            // Circle keeps no refId on a user's contract execution (2026-11-05); the wallet matches it by its legs.
            val withoutRef = transactions.translate(ours.copy(refId = "")).data()!!
            assertEquals("", withoutRef.refId)
            assertEquals(CustodyOperation.CONTRACT_EXECUTION, withoutRef.operation)
            assertNull(transactions.translate(ours.copy(transactionType = "INBOUND")).data(), "not a payment of ours")
            assertNull(transactions.translate(ours.copy(id = "")).data())
        }

    @Test
    fun `chainTransaction lists every wallet's record of one chain transaction with the API key alone`() =
        runBlocking<Unit> {
            circle.reply("GET /v1/w3s/tokens/${FakeCircle.USDC_TOKEN_ID}", FakeCircle.USDC_TOKEN_REPLY)
            circle.reply(
                "GET /v1/w3s/transactions",
                """{"data":{"transactions":[${CircleRecords.PAYEE_INBOUND},${CircleRecords.FEE_INBOUND},""" +
                    "${CircleRecords.SENDER_EXECUTION}]}}",
            )

            val records = custody.chainTransaction("BASE-SEPOLIA", CircleRecords.TX_HASH).data()

            val request = circle.requests("GET /v1/w3s/transactions").single()
            assertEquals(CircleRecords.TX_HASH, request.url.parameters["txHash"])
            assertEquals("BASE-SEPOLIA", request.url.parameters["blockchain"])
            assertNull(request.headers["X-User-Token"], "asked as the developer, so every wallet is listed")
            assertEquals(3, records.size)
            val payee = records.single { it.walletId == "6e62c682-7770-5768-aeed-0b2a59644b24" }
            assertEquals(CustodyDirection.IN, payee.direction)
            assertEquals(7_699_911.toBigInteger(), payee.amount.amountMinor)
            assertEquals("0x893a99d22726c28d82469c226efd1a685ca0c570", payee.sourceAddress)
            val fee = records.single { it.walletId == "d4eac6e0-25e4-5fb7-8dc6-deb56bcf9864" }
            assertTrue(fee.developerWallet)
            assertEquals(100_000.toBigInteger(), fee.amount.amountMinor)
            val execution = records.single { it.operation == CustodyOperation.CONTRACT_EXECUTION }
            assertEquals(CustodyDirection.OUT, execution.direction)
            assertEquals(CustodyTransactionState.CONFIRMED, execution.state)
            assertEquals(CircleRecords.TX_HASH, execution.txHash)
            assertEquals("0.0000018510681", execution.networkFee)
            assertEquals("", execution.refId)
        }
}
