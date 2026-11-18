package com.circaso.shared

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkTest {
    @Test
    fun `a chain's name says which network it is on`() {
        listOf("BASE-SEPOLIA", "MATIC-AMOY", "ETH-SEPOLIA", "SOL-DEVNET", "AVAX-FUJI", "APTOS-TESTNET", "base-sepolia")
            .forEach { assertEquals(Network.TESTNET, Network.ofChain(it), it) }
        listOf(
            "BASE",
            "MATIC",
            "ETH",
            "SOL",
            "ARB",
            "AVAX",
        ).forEach { assertEquals(Network.MAINNET, Network.ofChain(it), it) }
    }

    @Test
    fun `only the two upper-case names are networks on the wire`() {
        assertEquals(Network.MAINNET, Network.parse("MAINNET"))
        assertEquals(Network.TESTNET, Network.parse("TESTNET"))
        listOf(null, "", "mainnet", "PROD", " TESTNET").forEach { assertNull(Network.parse(it), "$it") }
    }

    @Test
    fun `work with no network set is testnet, and names none when asked which was asked for`() =
        runTest(EmptyCoroutineContext) {
            assertEquals(Network.TESTNET, currentNetwork())
            assertNull(networkFilter())
        }

    @Test
    fun `work reads the network it was started with`() =
        runTest(WalletNetwork(Network.MAINNET)) {
            assertEquals(Network.MAINNET, currentNetwork())
            assertEquals(Network.MAINNET, networkFilter())
        }
}
