package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.impl.dev.FakeCustody
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.WalletNetwork
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Custody on two networks side by side (decision 2026-11-18): a call goes to the account of the coroutine's network. */
class NetworkCustodyTest {
    private val testnet = FakeCustody(supported = CustodyChainsConfig.TESTNETS)
    private val mainnet = FakeCustody(supported = CustodyChainsConfig.MAINNETS)

    @Test
    fun `a call reaches only the custody of the network the work is for`() =
        runBlocking<Unit> {
            val custody = NetworkCustody(mapOf(Network.TESTNET to testnet, Network.MAINNET to mainnet))

            custody.createUser("legacy")
            withContext(WalletNetwork(Network.MAINNET)) { custody.createUser("real") }
            withContext(WalletNetwork(Network.TESTNET)) { custody.createUser("test") }

            assertEquals(setOf("legacy", "test"), testnet.users.toSet())
            assertEquals(setOf("real"), mainnet.users.toSet())
            assertEquals("BASE-SEPOLIA", custody.chains().default)
            assertEquals("BASE", withContext(WalletNetwork(Network.MAINNET)) { custody.chains().default })
            assertEquals(Network.entries.toSet(), custody.networks)
        }

    @Test
    fun `mainnet without its key is unavailable, and testnet carries on`() =
        runBlocking<Unit> {
            val custody = NetworkCustody(mapOf(Network.TESTNET to testnet))

            val refused = withContext(WalletNetwork(Network.MAINNET)) { custody.createUser("real") }

            assertIs<IntegrationError.Unavailable>((refused as Resource.Error).error)
            assertEquals(setOf(Network.TESTNET), custody.networks)
            assertTrue(custody.createUser("test") is Resource.Success)
            assertTrue(mainnet.users.isEmpty())
        }

    @Test
    fun `the seeds are the built-in mainnet chains and the old variables for the testnet`() {
        val seeds =
            ConfiguredCustodySeeds(
                MapSettings(
                    mapOf(
                        "app-id" to "test-app",
                        "mainnet.app-id" to "main-app",
                        "chains" to "BASE-SEPOLIA,SOL-DEVNET",
                        "chain.sol-devnet.receive-disabled" to "true",
                    ),
                ),
            )

        val main = seeds.seed(Network.MAINNET)
        val test = seeds.seed(Network.TESTNET)

        assertEquals("main-app", main.appId)
        assertEquals("BASE", main.supported.default)
        assertEquals(listOf("BASE", "MATIC", "ETH", "SOL"), main.supported.chains.map { it.blockchain })
        assertEquals(
            "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
            main.supported
                .find("BASE")!!
                .assets
                .single()
                .tokenAddress,
        )
        assertEquals("EOA", main.accountTypes["SOL"])
        assertEquals("SCA", main.accountTypes["BASE"])
        assertEquals("test-app", test.appId)
        assertEquals(listOf("BASE-SEPOLIA", "SOL-DEVNET"), test.supported.chains.map { it.blockchain })
        assertEquals(false, test.supported.find("SOL-DEVNET")!!.receiveEnabled)
    }

    @Test
    fun `mainnet chain names in the old variables can't seed the testnet`() {
        val seeds =
            ConfiguredCustodySeeds(
                MapSettings(
                    mapOf(
                        "chains" to "BASE",
                        "default-chain" to "BASE",
                        "chain.base.assets" to "USDC:0x1:6:usd",
                    ),
                ),
            )

        assertEquals(CustodyChainsConfig.TESTNETS, seeds.seed(Network.TESTNET).supported)
        assertEquals("", seeds.seed(Network.MAINNET).appId)
    }
}

private class MapSettings(private val values: Map<String, String>) : Config {
    override val environment = com.circaso.infrastructure.api.Environment.LOCAL

    override fun string(key: String): String? = values[key]

    override fun section(prefix: String): Config =
        MapSettings(values.filterKeys { it.startsWith("$prefix.") }.mapKeys { it.key.removePrefix("$prefix.") })
}
