package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.integrations.api.CustodyAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The supported chains and their assets, from the `INTEGRATIONS_CUSTODY_*` settings (decision 2026-10-28). */
class CustodyChainsConfigTest {
    private class MapConfig(private val values: Map<String, String>) : Config {
        override val environment = Environment.LOCAL

        override fun string(key: String): String? = values[key]

        override fun section(prefix: String): Config =
            MapConfig(values.filterKeys { it.startsWith("$prefix.") }.mapKeys { it.key.removePrefix("$prefix.") })
    }

    private fun parse(vararg values: Pair<String, String>) = CustodyChainsConfig.parse(MapConfig(values.toMap()))

    @Test
    fun `unset, the four testnets offer USDC and a new wallet starts on Base Sepolia`() {
        val parsed = parse()

        assertEquals("BASE-SEPOLIA", parsed.supported.default)
        assertEquals(
            listOf("BASE-SEPOLIA", "MATIC-AMOY", "ETH-SEPOLIA", "SOL-DEVNET"),
            parsed.supported.chains.map { it.blockchain },
        )
        val solana =
            parsed.supported
                .find("SOL-DEVNET")!!
                .assets
                .single()
        assertEquals(CustodyAsset("USDC", "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU", 6, usdPegged = true), solana)
        assertEquals("SCA", parsed.accountTypes["BASE-SEPOLIA"])
        assertEquals("EOA", parsed.accountTypes["SOL-DEVNET"])
    }

    @Test
    fun `production names its chains, the default and each chain's assets, the native coin with no address`() {
        val parsed =
            parse(
                "chains" to "BASE, SOL",
                "default-chain" to "BASE",
                "chain.base.assets" to "USDC:0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913:6:usd, ETH::18",
                "chain.sol.assets" to "usdc:EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v:6:USD",
                "chain.sol.account-type" to "EOA",
            )

        assertEquals("BASE", parsed.supported.default)
        val base = parsed.supported.find("BASE")!!.assets
        assertEquals(listOf("USDC", "ETH"), base.map { it.symbol })
        assertEquals(CustodyAsset("ETH", "", 18, usdPegged = false), base[1])
        assertEquals(
            "USDC",
            parsed.supported
                .find("SOL")!!
                .assets
                .single()
                .symbol,
        )
        assertTrue(
            parsed.supported
                .find("SOL")!!
                .assets
                .single()
                .usdPegged,
        )
    }

    @Test
    fun `a mainnet chain without assets, a default that isn't listed and a malformed asset stop startup`() {
        assertFailsWith<IllegalStateException> { parse("chains" to "BASE") }
        assertFailsWith<IllegalStateException> { parse("chains" to "BASE-SEPOLIA", "default-chain" to "BASE") }
        assertFailsWith<IllegalStateException> {
            parse("chains" to "BASE-SEPOLIA", "chain.base-sepolia.assets" to "USDC:0x1")
        }
        assertFailsWith<IllegalStateException> {
            parse("chains" to "BASE-SEPOLIA", "chain.base-sepolia.assets" to "USDC:0x1:6:eur")
        }
    }

    @Test
    fun `EVM token addresses match in any case, Solana mints exactly`() {
        assertTrue(CustodyChainsConfig.sameToken("0xAbC", "0xabc"))
        assertTrue(!CustodyChainsConfig.sameToken("EPjFWdd5", "epjfwdd5"))
    }
}
