package com.circaso.wallet

import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.AssetTotal
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.FiatValue
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.presentation.dto.AddChainRequest
import com.circaso.wallet.presentation.mappers.createWalletCommand
import com.circaso.wallet.presentation.mappers.toCommand
import com.circaso.wallet.presentation.mappers.toResponse
import com.circaso.wallet.presentation.mappers.walletQuery
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
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
        assertEquals("SOL-DEVNET", AddChainRequest("sol-devnet").toCommand(amina, "k-1").data().blockchain)
        assertEquals("request.malformed", AddChainRequest("base sepolia!").toCommand(amina, "k-1").code())

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
                                Holding("ETH", "", 18, false, eth),
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
        assertEquals("0", response.totals.single().amount)
        assertEquals("129.25", response.fiat?.rate)
        assertEquals(true, response.fiat?.partial)
    }
}
