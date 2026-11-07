package com.circaso.wallet

import com.circaso.shared.UserId
import com.circaso.wallet.presentation.api.WalletsImpl
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `Wallets.depositAddress` for share links: the address comes from the wallet's own record, per chain. */
class DepositAddressTest {
    private val h = Harness()
    private val wallets = WalletsImpl(h.directory)
    private val amina = UserId.new()

    @Test
    fun `someone without a wallet has no address`() =
        runBlocking<Unit> {
            assertNull(wallets.depositAddress(amina, TEST_CHAINS.default))
        }

    @Test
    fun `the address recorded on that chain, whatever the case of the chain's name`() =
        runBlocking<Unit> {
            val base = h.walletOn(amina)
            assertEquals(base.address, wallets.depositAddress(amina, TEST_CHAINS.default))
            assertEquals(base.address, wallets.depositAddress(amina, TEST_CHAINS.default.lowercase()))
            assertNull(wallets.depositAddress(amina, "NOT-A-CHAIN"))
        }
}
