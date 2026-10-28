package com.circaso.wallet

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.presentation.api.WalletDeletionGuard
import com.circaso.wallet.presentation.services.WalletDirectoryService
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Identity's `DeletionGuard` answered by the wallet: nothing left of any US dollar stablecoin worth a cent. */
class DeletionGuardTest {
    private val h = Harness()
    private val guard = WalletDeletionGuard(WalletDirectoryService(h.hasWallet, h.checkFunds))
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
