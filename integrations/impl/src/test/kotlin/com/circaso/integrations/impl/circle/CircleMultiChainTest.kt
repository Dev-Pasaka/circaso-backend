package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.CustodyChain
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SupportedChains
import com.circaso.integrations.impl.text
import com.circaso.shared.Resource
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A wallet on several chains (decision 2026-10-28): adding a chain, each offered asset's balance, the chain checks. */
class CircleMultiChainTest {
    private val circle = FakeCircle()
    private val custody = circle.custody
    private val user = "circle-user-1"

    private inline fun <reified T> HttpRequestData.sent(): T = circleJson.decodeFromString(text())

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    @Test
    fun `the default chain is Base Sepolia among the four testnets, each offering USDC`() {
        assertEquals("BASE-SEPOLIA", custody.supported.default)
        assertEquals(
            listOf("BASE-SEPOLIA", "MATIC-AMOY", "ETH-SEPOLIA", "SOL-DEVNET"),
            custody.supported.chains.map { it.blockchain },
        )
        assertTrue(custody.supported.chains.all { chain -> chain.assets.single().let { it.symbol == "USDC" } })
    }

    @Test
    fun `addWallet asks Circle for a wallet on one more chain as the user, with that chain's account type`() =
        runBlocking<Unit> {
            circle.reply("POST /v1/w3s/user/wallets", """{"data":{"challengeId":"challenge-2"}}""")
            val key = UUID.randomUUID()

            val challenge = custody.addWallet(user, "SOL-DEVNET", key).data()

            assertEquals("challenge-2", challenge.challengeId)
            assertEquals(circle.tokenOf(1), challenge.sdk.userToken)
            val request = circle.requests("POST /v1/w3s/user/wallets").single()
            assertEquals(circle.tokenOf(1), request.headers["X-User-Token"])
            assertEquals(key.toString(), request.headers["Idempotency-Key"])
            val body = request.sent<AddWalletsBody>()
            assertEquals(key.toString(), body.idempotencyKey)
            assertEquals(listOf("SOL-DEVNET"), body.blockchains)
            assertEquals("EOA", body.accountType, "Solana has no smart-contract accounts")
        }

    @Test
    fun `a chain that isn't supported is refused without calling Circle`() =
        runBlocking<Unit> {
            val refusals =
                listOf(
                    custody.addWallet(user, "AVAX-FUJI", UUID.randomUUID()),
                    custody.balances(user, "w-1", "AVAX-FUJI"),
                    custody.validateAddress("AVAX-FUJI", "0x1"),
                )
            refusals.forEach { assertIs<IntegrationError.Rejected>((it as Resource.Error).error) }
            assertTrue(circle.clients.requests.isEmpty())
        }

    @Test
    fun `balances lists every offered asset on the chain, zero for one not held, and skips other tokens`() =
        runBlocking<Unit> {
            val weth = "0x00000000000000000000000000000000000000ee"
            val settings =
                CircleSettings(
                    appId = "app-1",
                    supported =
                        SupportedChains(
                            default = "BASE-SEPOLIA",
                            chains =
                                listOf(
                                    CustodyChain(
                                        "BASE-SEPOLIA",
                                        listOf(
                                            CustodyAsset("USDC", FakeCircle.USDC_ADDRESS, 6, usdPegged = true),
                                            CustodyAsset("WETH", weth, 18, usdPegged = false),
                                            CustodyAsset("ETH", "", 18, usdPegged = false),
                                        ),
                                    ),
                                ),
                        ),
                )
            val custody = CircleCustody(circle.client, settings, circle.clock)
            val notOffered = "0x0000000000000000000000000000000000000bad"
            val wethInCaps = weth.uppercase().replace("0X", "0x")
            val usdc = FakeCircle.USDC_ADDRESS.lowercase()

            fun line(token: String, amount: String) =
                """{"token":{"blockchain":"BASE-SEPOLIA","tokenAddress":"$token"},"amount":"$amount"}"""
            circle.reply(
                "GET /v1/w3s/wallets/w-1/balances",
                """{"data":{"tokenBalances":[""" +
                    line(notOffered, "5") + "," +
                    line(wethInCaps, "12.000000000000000001") + "," +
                    line(usdc, "20.5") + "]}}",
            )

            val balances = custody.balances(user, "w-1", "BASE-SEPOLIA").data()

            assertEquals(listOf("USDC", "WETH", "ETH"), balances.map { it.asset.symbol }, "in the configured order")
            assertEquals(BigInteger.valueOf(20_500_000), balances[0].amountMinor)
            assertEquals(BigInteger("12000000000000000001"), balances[1].amountMinor, "past what a Long holds")
            assertEquals(BigInteger.ZERO, balances[2].amountMinor, "the native coin, never held")
            assertEquals(
                circle.tokenOf(1),
                circle.requests("GET /v1/w3s/wallets/w-1/balances").single().headers["X-User-Token"],
            )
        }

    @Test
    fun `an amount Circle gives with more decimals than the asset is unavailable, never rounded`() =
        runBlocking<Unit> {
            circle.reply(
                "GET /v1/w3s/wallets/w-1/balances",
                """{"data":{"tokenBalances":[{"token":{"blockchain":"BASE-SEPOLIA",""" +
                    """"tokenAddress":"${FakeCircle.USDC_ADDRESS}"},"amount":"1.0000001"}]}}""",
            )
            assertIs<IntegrationError.Unavailable>(
                (custody.balances(user, "w-1", "BASE-SEPOLIA") as Resource.Error).error,
            )
        }

    @Test
    fun `transfers and address checks name the chain they are given`() =
        runBlocking<Unit> {
            circle.reply("POST /v1/w3s/transactions/validateAddress", """{"data":{"isValid":true}}""")
            assertTrue(custody.validateAddress("SOL-DEVNET", "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin").data())
            val body =
                circle.requests("POST /v1/w3s/transactions/validateAddress").single().sent<ValidateAddressBody>()
            assertEquals("SOL-DEVNET", body.blockchain)
        }
}
