package com.circaso.wallet

import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.AssetTotal
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.FiatValue
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.presentation.dto.AddChainRequest
import com.circaso.wallet.presentation.dto.AmountRequest
import com.circaso.wallet.presentation.dto.PayeeRequest
import com.circaso.wallet.presentation.dto.PrepareTransferRequest
import com.circaso.wallet.presentation.mappers.createWalletCommand
import com.circaso.wallet.presentation.mappers.toCommand
import com.circaso.wallet.presentation.mappers.toResponse
import com.circaso.wallet.presentation.mappers.walletQuery
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The request checks that answer `request.malformed`, and amounts on the wire. */
class MappersTest {
    private val amina = UserId.new()

    @Test
    fun `currency is an ISO 4217 code in any case, anything else is malformed`() {
        assertEquals("KES", walletQuery(amina, "kes").data().currency)
        assertEquals(null, walletQuery(amina, null).data().currency)
        assertEquals("request.malformed", walletQuery(amina, "KESH").code())
        assertEquals("request.malformed", walletQuery(amina, "ABC").code())
    }

    @Test
    fun `the blockchain is checked for shape and the Circle key is derived from the request's key`() {
        assertEquals("SOL-DEVNET", AddChainRequest("sol-devnet").toCommand(amina).data().blockchain)
        assertEquals("request.malformed", AddChainRequest("base sepolia!").toCommand(amina).code())

        val first = createWalletCommand(amina, "k-1").idempotencyKey
        assertEquals(first, createWalletCommand(amina, "k-1").idempotencyKey, "a repeat asks Circle with the same key")
        assertNotEquals(first, createWalletCommand(amina, "k-2").idempotencyKey)
        assertNotEquals(first, createWalletCommand(UserId.new(), "k-1").idempotencyKey)
    }

    @Test
    fun `amounts go out as strings, in minor units and in whole units, past what a JSON number holds`() {
        val wallet =
            ChainWallet("w-1", amina, "BASE-SEPOLIA", "SCA", "0xabc", ChainWalletState.ACTIVE, Instant.EPOCH)
        val eth = BigInteger("12000000000000000001")
        val view =
            WalletView(
                status = WalletStatus.ACTIVE,
                chains =
                    listOf(
                        ChainHoldings(
                            wallet,
                            listOf(
                                Holding("USDC", "0x1", 6, true, BigInteger.valueOf(20_500_000), BigDecimal("2649.63")),
                                Holding("ETH", "", 18, false, eth, usdPrice = BigDecimal("3512.2700")),
                            ),
                        ),
                    ),
                totals = listOf(AssetTotal("USDC", 6, BigInteger.ZERO)),
                fiat = FiatValue("KES", BigDecimal("2649.63"), BigDecimal("129.25"), Instant.EPOCH, partial = true),
                hold = null,
            )

        val response = view.toResponse()

        val assets = response.chains.single().assets
        assertEquals("20500000", assets[0].amountMinor)
        assertEquals("20.5", assets[0].amount)
        assertEquals("2649.63", assets[0].fiatAmount)
        assertEquals("12000000000000000001", assets[1].amountMinor)
        assertEquals("12.000000000000000001", assets[1].amount)
        assertEquals(null, assets[1].fiatAmount)
        assertEquals(null, assets[0].usdPrice, "no price set on the holding, none sent")
        assertEquals("3512.27", assets[1].usdPrice, "as the feed wrote it, without trailing zeros")
        assertEquals("0", response.totals.single().amount)
        assertEquals("129.25", response.fiat?.rate)
        assertEquals(true, response.fiat?.partial)
    }

    private fun transfer(
        amount: AmountRequest,
        to: PayeeRequest = PayeeRequest(userId = UserId.new().toString()),
        blockchain: String = "base-sepolia",
    ) = PrepareTransferRequest(blockchain, to, amount).toCommand(amina, "k-1")

    @Test
    fun `W4 amounts are strings - amountMinor exact, amount in whole units, or both when they agree`() {
        assertEquals(20_000_000, transfer(AmountRequest("USDC", amountMinor = "20000000")).data().amount.amountMinor)
        assertEquals(20_500_000, transfer(AmountRequest("usdc", amount = "20.5")).data().amount.amountMinor)
        assertEquals(1, transfer(AmountRequest("USDC", "1", "0.000001")).data().amount.amountMinor)

        assertEquals("wallet.amount_invalid", transfer(AmountRequest("USDC", "2", "0.000001")).code(), "disagree")
        assertEquals("wallet.amount_invalid", transfer(AmountRequest("USDC", amount = "0.0000001")).code())
        assertEquals("wallet.amount_invalid", transfer(AmountRequest("USDC", amount = "-1")).code())
        assertEquals("wallet.amount_invalid", transfer(AmountRequest("USDC", amountMinor = "1.5")).code())
        assertEquals(
            "wallet.amount_invalid",
            transfer(AmountRequest("USDC", amountMinor = "99999999999999999999")).code(),
        )
        assertEquals("wallet.amount_invalid", transfer(AmountRequest("DOGE", amountMinor = "1")).code())
        assertEquals("request.malformed", transfer(AmountRequest("USDC")).code())
    }

    @Test
    fun `W4 needs exactly one payee, ids that read, and a chain name, and keys the transfer by the request`() {
        val amount = AmountRequest("USDC", amountMinor = "1")
        assertEquals("BASE-SEPOLIA", transfer(amount).data().blockchain)
        assertEquals("request.malformed", transfer(amount, PayeeRequest()).code())
        assertEquals("request.malformed", transfer(amount, PayeeRequest("x", "0xabc")).code())
        assertEquals("request.malformed", transfer(amount, PayeeRequest(userId = "nope")).code())
        assertEquals("request.malformed", transfer(amount, PayeeRequest(address = " ")).code())
        assertEquals("request.malformed", transfer(amount, blockchain = "base sepolia!").code())

        val first = transfer(amount).data().idempotencyKey
        assertEquals(first, transfer(amount).data().idempotencyKey, "a repeat finds the same transfer")
        assertNotEquals(
            first,
            PrepareTransferRequest(
                "BASE",
                PayeeRequest(address = "0x1"),
                amount,
            ).toCommand(amina, "k-2").data().idempotencyKey,
        )
    }

    @Test
    fun `W4 refuses a proposalId until Asha's proposals exist`() {
        val request =
            PrepareTransferRequest(
                "BASE",
                PayeeRequest(address = "0x1"),
                AmountRequest("USDC", amountMinor = "1"),
                proposalId = UUID.randomUUID().toString(),
            )
        val error = (request.toCommand(amina, "k-1") as Resource.Error).error
        assertEquals("request.malformed", error.code)
        assertEquals(
            "proposalId",
            error.details
                ?.fields
                ?.single()
                ?.field,
        )
    }

    @Test
    fun `W5 shows origin and proposalId to the sender only`() {
        val bob = UserId.new()
        val transfer =
            Transfer(
                transferId = TransferId.new(),
                senderId = amina,
                senderWalletId = "w-1",
                blockchain = "BASE",
                idempotencyKey = UUID.randomUUID(),
                recipientId = bob,
                destinationAddress = "0xabc",
                amount = Money(1, Asset.USDC),
                state = TransferState.CONFIRMED,
                circleTxId = null,
                txHash = null,
                chatId = null,
                origin = TransferOrigin.ASHA,
                proposalId = UUID.randomUUID(),
                failureReason = null,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )

        val sender = transfer.toResponse(amina)
        val recipient = transfer.toResponse(bob)

        assertEquals("OUT", sender.direction)
        assertEquals("ASHA", sender.origin)
        assertEquals(transfer.proposalId.toString(), sender.proposalId)
        assertEquals("IN", recipient.direction)
        assertEquals(null, recipient.origin)
        assertEquals(null, recipient.proposalId)
    }
}
