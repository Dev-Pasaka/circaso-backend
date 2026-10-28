package com.circaso.wallet

import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.CustodyChain
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.presentation.api.WalletDeletionGuard
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Identity's `DeletionGuard` answered by the wallet: nothing left of any US dollar stablecoin worth a cent. */
class DeletionGuardTest {
    private val h = Harness()
    private val guard = WalletDeletionGuard(h.directory)
    private val amina = UserId.new()

    @Test
    fun `an account that never asked for a wallet may be deleted, without asking Circle`() =
        runBlocking<Unit> {
            assertIs<Resource.Success<Unit>>(guard.canDelete(amina))
            assertEquals(0, h.custody.calls.size)
        }

    @Test
    fun `an empty wallet, or less than a cent, may be deleted`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            assertIs<Resource.Success<Unit>>(guard.canDelete(amina))

            val base = h.wallets.wallets(amina).single()
            h.custody.held[base.circleWalletId] = mapOf("USDC" to BigInteger.valueOf(9_999))
            assertIs<Resource.Success<Unit>>(guard.canDelete(amina))
        }

    @Test
    fun `a cent of USDC on any chain refuses with wallet funds_remaining`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)
            val solana = h.custody.sign(circleUserId, "SOL-DEVNET")
            h.custody.held[solana.walletId] = mapOf("USDC" to BigInteger.valueOf(10_000))

            assertEquals("wallet.funds_remaining", guard.canDelete(amina).code())
        }

    @Test
    fun `the cent is counted over every chain - 0,009 USDC on each of four chains is 0,036 and refuses`() =
        runBlocking<Unit> {
            val fourChains =
                TEST_CHAINS.copy(
                    chains =
                        TEST_CHAINS.chains +
                            CustodyChain(
                                "ETH-SEPOLIA",
                                listOf(CustodyAsset("USDC", USDC_SEPOLIA, 6, usdPegged = true)),
                            ),
                )
            val h4 = Harness(chains = fourChains)
            val guard4 = WalletDeletionGuard(h4.directory)
            val chains = listOf("BASE-SEPOLIA", "SOL-DEVNET", "MATIC-AMOY", "ETH-SEPOLIA")
            chains.forEach { h4.fund(amina, 9_000, it) }
            assertEquals(4, h4.wallets.wallets(amina).size)

            assertEquals("wallet.funds_remaining", guard4.canDelete(amina).code())

            chains.drop(1).forEach { h4.fund(amina, 0, it) }
            assertIs<Resource.Success<Unit>>(guard4.canDelete(amina), "0.009 on one chain alone is dust")
        }

    @Test
    fun `a chain retired from receiving is still read, and its funds block deletion`() =
        runBlocking<Unit> {
            h.fund(amina, 2_000_000, "MATIC-AMOY")

            assertEquals("wallet.funds_remaining", guard.canDelete(amina).code())
            assertTrue(h.warnings.chainsNotConfigured.isEmpty())
        }

    @Test
    fun `the guard reads Circle, not the 15-second cache`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            val base = h.wallets.wallets(amina).single()
            h.cache.put(base.circleWalletId, emptyList())
            h.custody.held[base.circleWalletId] = mapOf("USDC" to BigInteger.valueOf(5_000_000))

            assertEquals("wallet.funds_remaining", guard.canDelete(amina).code())
        }

    @Test
    fun `any amount of an asset that isn't a US dollar stablecoin refuses`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            val base = h.wallets.wallets(amina).single()
            h.custody.held[base.circleWalletId] = mapOf("ETH" to BigInteger.ONE)

            assertEquals("wallet.funds_remaining", guard.canDelete(amina).code())
        }

    @Test
    fun `a wallet Circle made that we haven't recorded yet is still checked`() =
        runBlocking<Unit> {
            h.create(amina).data()
            val signed = h.custody.sign(h.circleUserOf(amina))
            h.custody.held[signed.walletId] = mapOf("USDC" to BigInteger.valueOf(1_000_000))

            assertEquals("wallet.funds_remaining", guard.canDelete(amina).code())
        }

    @Test
    fun `a second Circle wallet on a chain, which our rows can't hold, is still checked`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)
            val second = h.custody.sign(circleUserId, "BASE-SEPOLIA")
            h.custody.held[second.walletId] = mapOf("USDC" to BigInteger.valueOf(2_000_000))

            assertEquals("wallet.funds_remaining", guard.canDelete(amina).code())
            assertEquals(1, h.wallets.wallets(amina).size, "one row per chain: the second was never recorded")
        }

    @Test
    fun `a wallet on a chain no longer configured refuses with custody unavailable and warns`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)
            val removed = h.custody.sign(circleUserId, "AVAX-FUJI")

            assertEquals("wallet.custody_unavailable", guard.canDelete(amina).code())
            assertEquals(listOf("AVAX-FUJI" to removed.walletId), h.warnings.chainsNotConfigured)
            val shown =
                h
                    .wallet(amina)
                    .data()
                    .chains
                    .single { it.wallet.blockchain == "AVAX-FUJI" }
            assertTrue(shown.holdings.isEmpty(), "W1 shows the chain with nothing on it")
        }

    @Test
    fun `when Circle can't answer, deletion is refused rather than allowed on a guess`() =
        runBlocking<Unit> {
            h.activeWallet(amina)
            h.custody.down = true
            assertEquals("wallet.custody_unavailable", guard.canDelete(amina).code())
        }
}
