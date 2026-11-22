package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.Email
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryAccess
import com.circaso.integrations.api.TreasuryRefusal
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.integrations.api.TreasuryWalletRequest
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Network
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The stand-in treasuries (decision 2026-11-22): what lets the ramp run locally and in tests with no money anywhere. */
class TreasuryTest {
    private val network = Network.TESTNET

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun request(refId: String, accountType: String = TreasuryWalletRequest.ACCOUNT_SCA) =
        TreasuryWalletRequest(
            "set-1",
            listOf("BASE-SEPOLIA"),
            "Ramp merchant float",
            refId,
            UUID.randomUUID(),
            accountType,
        )

    private fun transfer(
        from: String,
        to: String,
        amount: Long,
        key: UUID = UUID.randomUUID(),
    ) = TreasuryTransfer(from, "BASE-SEPOLIA", "dev-usdc", 6, BigInteger.valueOf(amount), to, "ref-1", key)

    @Test
    fun `the fake makes the same wallet for the same refId, with the account type it was asked for`() =
        runBlocking<Unit> {
            val treasury = FakeTreasury()

            val first = treasury.createWallets(network, request("merchant-1")).data().single()
            val again = treasury.createWallets(network, request("merchant-1")).data().single()
            val other =
                treasury
                    .createWallets(
                        network,
                        request("merchant-2", TreasuryWalletRequest.ACCOUNT_EOA),
                    ).data()
                    .single()

            assertEquals(first, again)
            assertEquals("SCA" to "EOA", first.accountType to other.accountType)
            assertNotEquals(first.address, other.address)
            assertTrue(Regex("0x[0-9a-f]{40}").matches(first.address), first.address)
            assertEquals(listOf(first), treasury.findWallets(network, refId = "merchant-1").data())
            assertEquals(TreasuryAccess.FULL, treasury.access(network))
            treasury.grant(Network.MAINNET, TreasuryAccess.NO_ACCOUNT)
            assertTrue(!treasury.canRead(Network.MAINNET) && treasury.canSign(network))
        }

    @Test
    fun `the fake moves only what a wallet holds, once per key, and a send waits for the test to settle it`() =
        runBlocking<Unit> {
            val treasury = FakeTreasury(usdcContract = { _, _ -> "0xusdc" })
            val merchant = treasury.createWallets(network, request("merchant-1")).data().single()
            val revenue = treasury.createWallets(network, request("ramp-revenue")).data().single()
            treasury.fund(merchant.walletId, 10_000_000)

            val held = treasury.balances(network, merchant.walletId).data().single()
            assertEquals(
                Triple("USDC", "0xusdc", BigInteger.valueOf(10_000_000)),
                Triple(held.symbol, held.tokenAddress, held.amountMinor),
            )
            assertEquals(Money(10_000_000, Asset.USDC), treasury.balance(network, merchant.walletId).data())

            val refused = treasury.send(network, transfer(merchant.walletId, revenue.address, 10_000_001))
            assertIs<TreasuryRefusal>((refused as Resource.Error).error)

            val key = UUID.randomUUID()
            val sent = treasury.send(network, transfer(merchant.walletId, revenue.address, 4_000_000, key)).data()
            val replay = treasury.send(network, transfer(merchant.walletId, revenue.address, 4_000_000, key)).data()
            assertEquals(sent, replay)
            assertEquals(TreasuryTransactionState.IN_FLIGHT, sent.state)
            assertEquals(1, treasury.sends.size, "the same key never sends twice")
            assertEquals(Money(6_000_000, Asset.USDC), treasury.balance(network, merchant.walletId).data())
            assertEquals(Money(4_000_000, Asset.USDC), treasury.balance(network, revenue.walletId).data())

            treasury.settle(sent.transactionId)
            val confirmed = treasury.transaction(network, sent.transactionId).data()
            assertEquals(TreasuryTransactionState.CONFIRMED, confirmed.state)
            assertTrue(confirmed.txHash.startsWith("0x"))

            treasury.failure = IntegrationError.Unavailable("Circle")
            assertIs<Resource.Error>(treasury.balance(network, merchant.walletId))
            assertIs<Resource.Error>(treasury.send(network, transfer(merchant.walletId, revenue.address, 1)))
        }

    @Test
    fun `the log treasury confirms a send at once and never runs dry, whatever this process remembers`() =
        runBlocking<Unit> {
            val treasury = LogTreasury()
            val wallet = treasury.createWallets(network, request("merchant-1")).data().single()

            val sent = treasury.send(network, transfer(wallet.walletId, "0xuser", 999_000_000)).data()
            val more = treasury.send(network, transfer(wallet.walletId, "0xuser", 999_000_000)).data()

            assertEquals(TreasuryTransactionState.CONFIRMED, sent.state)
            assertEquals(TreasuryTransactionState.CONFIRMED, more.state)
            assertEquals(
                FakeTreasury.ENDLESS_FLOAT,
                treasury
                    .balances(network, wallet.walletId)
                    .data()
                    .single()
                    .amountMinor,
            )

            // another process (or this one after a restart) made the wallet and the transaction: the answers agree
            val elsewhere = LogTreasury()
            val read = elsewhere.transaction(network, sent.transactionId).data()
            assertEquals(TreasuryTransactionState.CONFIRMED to sent.txHash, read.state to read.txHash)
            assertEquals(
                FakeTreasury.ENDLESS_FLOAT,
                elsewhere
                    .balances(network, wallet.walletId)
                    .data()
                    .single()
                    .amountMinor,
            )
            assertEquals(
                Money(FakeTreasury.ENDLESS_FLOAT, Asset.USDC),
                LogMerchantFloat().balance(network, wallet.walletId).data(),
            )
        }

    @Test
    fun `the log email answers a merchant's sign-in code like any other email`() =
        runBlocking<Unit> {
            val receipt = LogEmail().send("pay@crynce.co.ke", Email.MerchantSignInCode("482913", 10)).data()

            assertEquals("log", receipt.provider)
        }
}
