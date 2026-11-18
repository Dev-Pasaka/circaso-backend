package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryAccess
import com.circaso.integrations.api.TreasuryRefusal
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.integrations.api.TreasuryWalletRequest
import com.circaso.integrations.impl.text
import com.circaso.shared.Network
import com.circaso.shared.Resource
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Circle's developer-controlled wallets as the fee wallets use them (decision 2026-11-18): the paths and bodies as
 * Circle's reference has them, a fresh ciphertext on every signed request, and nothing sent twice.
 */
class CircleTreasuryTest {
    private val circle = FakeCircle()
    private val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val secretHex = "a1".repeat(32)
    private val secretBytes = ByteArray(32) { 0xa1.toByte() }
    private val treasury =
        CircleTreasury(
            mapOf(Network.MAINNET to TreasuryAccount(circle.client, EntitySecret.parse(secretHex))),
            circle.clock,
        )
    private val readOnly = CircleTreasury(mapOf(Network.TESTNET to TreasuryAccount(circle.client, null)), circle.clock)
    private val walletId = "5d1b5f3a-0b0e-4f55-8b2a-7b8a0b1c2d3e"
    private val address = "0x7a1b2c3d4e5f60718293a4b5c6d7e8f901234567"

    init {
        val pem =
            "-----BEGIN PUBLIC KEY-----\\n" +
                Base64.getEncoder().encodeToString(pair.public.encoded) +
                "\\n-----END PUBLIC KEY-----\\n"
        circle.reply("GET $PUBLIC_KEY", """{"data":{"publicKey":"$pem"}}""")
    }

    private inline fun <reified T> HttpRequestData.sent(): T = circleJson.decodeFromString(text())

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun secretIn(ciphertext: String): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                pair.private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            doFinal(Base64.getDecoder().decode(ciphertext))
        }

    private fun wallet(chain: String, id: String = walletId) =
        """{"id":"$id","address":"$address","blockchain":"$chain","walletSetId":"set-1","accountType":"EOA",""" +
            """"refId":"circaso-fees","custodyType":"DEVELOPER","state":"LIVE"}"""

    @Test
    fun `what a server can do follows what it holds`() {
        assertEquals(TreasuryAccess.FULL, treasury.access(Network.MAINNET))
        assertEquals(TreasuryAccess.NO_ACCOUNT, treasury.access(Network.TESTNET))
        assertEquals(TreasuryAccess.READ_ONLY, readOnly.access(Network.TESTNET))
        assertTrue(readOnly.canRead(Network.TESTNET))
        assertFalse(readOnly.canSign(Network.TESTNET))
    }

    @Test
    fun `a wallet set is created with the key, the name and a ciphertext of the entity secret`() =
        runBlocking<Unit> {
            circle.reply(
                "POST $WALLET_SETS",
                """{"data":{"walletSet":{"id":"set-1","custodyType":"DEVELOPER"}}}""",
                201,
            )
            val key = UUID.randomUUID()

            assertEquals("set-1", treasury.createWalletSet(Network.MAINNET, "Circaso fees", key).data())

            val request = circle.requests("POST $WALLET_SETS").single()
            assertEquals("Bearer ${FakeCircle.API_KEY}", request.headers[HttpHeaders.Authorization])
            assertNull(
                request.headers["Idempotency-Key"],
                "no header: the shared client must never send the same ciphertext twice after a timeout",
            )
            val body = request.sent<CreateWalletSetBody>()
            assertEquals(key.toString(), body.idempotencyKey)
            assertEquals("Circaso fees", body.name)
            assertContentEquals(secretBytes, secretIn(body.entitySecretCiphertext))
            assertFalse(secretHex in request.text(), "the secret itself never leaves")
        }

    @Test
    fun `every signed request carries a new ciphertext, and the public key is fetched once`() =
        runBlocking<Unit> {
            circle.reply("POST $WALLET_SETS", """{"data":{"walletSet":{"id":"set-1"}}}""")

            repeat(3) { treasury.createWalletSet(Network.MAINNET, "Circaso fees", UUID.randomUUID()) }

            val ciphertexts =
                circle.requests("POST $WALLET_SETS").map { it.sent<CreateWalletSetBody>().entitySecretCiphertext }
            assertEquals(3, ciphertexts.toSet().size)
            assertEquals(1, circle.requests("GET $PUBLIC_KEY").size)
        }

    @Test
    fun `one externally owned wallet is created under the fee refId`() =
        runBlocking<Unit> {
            circle.reply("POST $WALLETS", """{"data":{"wallets":[${wallet("BASE")}]}}""", 201)
            val key = UUID.randomUUID()

            val made =
                treasury
                    .createWallets(
                        Network.MAINNET,
                        TreasuryWalletRequest("set-1", listOf("BASE"), "Circaso fee wallet", "circaso-fees", key),
                    ).data()

            assertEquals(listOf(walletId to "BASE"), made.map { it.walletId to it.blockchain })
            assertEquals(address, made.single().address)
            assertTrue(made.single().developerOwned)
            val body = circle.requests("POST $WALLETS").single().sent<CreateDeveloperWalletsBody>()
            assertEquals(key.toString(), body.idempotencyKey)
            assertEquals("set-1", body.walletSetId)
            assertEquals(listOf("BASE"), body.blockchains)
            assertEquals(1, body.count)
            assertEquals("EOA", body.accountType)
            assertEquals(listOf(WalletMetadata("Circaso fee wallet", "circaso-fees")), body.metadata)
            assertContentEquals(secretBytes, secretIn(body.entitySecretCiphertext))
            val text = circle.requests("POST $WALLETS").single().text()
            assertTrue("\"count\":1" in text && "\"accountType\":\"EOA\"" in text, "defaults are sent, not left out")
        }

    @Test
    fun `deriving on another chain is a PUT with the metadata and no secret at all`() =
        runBlocking<Unit> {
            val route = "PUT /v1/w3s/developer/wallets/$walletId/blockchains/MATIC"
            circle.reply(route, """{"data":{"wallet":${wallet("MATIC", "w-matic")}}}""", 201)

            val derived =
                readOnly.deriveWallet(
                    Network.TESTNET,
                    walletId,
                    "MATIC",
                    "Circaso fee wallet",
                    "circaso-fees",
                )

            assertEquals("w-matic", derived.data().walletId)
            assertEquals(address, derived.data().address)
            val request = circle.requests(route).single()
            assertEquals(DeriveWalletBody(WalletMetadata("Circaso fee wallet", "circaso-fees")), request.sent())
            assertFalse("entitySecretCiphertext" in request.text())
            assertTrue(circle.requests("GET $PUBLIC_KEY").isEmpty(), "needs the API key only")
        }

    @Test
    fun `wallets are found by address, chain and refId with the API key alone`() =
        runBlocking<Unit> {
            val users = wallet("BASE-SEPOLIA", "w-user").replace("DEVELOPER", "ENDUSER")
            circle.reply("GET /v1/w3s/wallets", """{"data":{"wallets":[${wallet("BASE-SEPOLIA")},$users]}}""")

            val found =
                readOnly
                    .findWallets(
                        Network.TESTNET,
                        address = address,
                        blockchain = "BASE-SEPOLIA",
                        refId = "circaso-fees",
                    ).data()

            assertEquals(listOf(true, false), found.map { it.developerOwned })
            val url = circle.requests("GET /v1/w3s/wallets").single().url
            assertEquals(address, url.parameters["address"])
            assertEquals("BASE-SEPOLIA", url.parameters["blockchain"])
            assertEquals("circaso-fees", url.parameters["refId"])
            assertEquals("50", url.parameters["pageSize"])
        }

    @Test
    fun `balances come back in minor units, the chain's own coin marked`() =
        runBlocking<Unit> {
            circle.reply(
                "GET /v1/w3s/wallets/$walletId/balances",
                """{"data":{"tokenBalances":[
                    {"amount":"1234.5","token":{"id":"t-usdc","blockchain":"BASE","symbol":"USDC","decimals":6,
                     "isNative":false,"tokenAddress":"0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"}},
                    {"amount":"0.000000000000000001","token":{"id":"t-eth","blockchain":"BASE","symbol":"ETH",
                     "decimals":18,"isNative":true}},
                    {"amount":"7","token":{"id":"t-odd","blockchain":"BASE","symbol":"","decimals":null}}]}}""",
            )

            val held = treasury.balances(Network.MAINNET, walletId).data()

            assertEquals(listOf("USDC", "ETH"), held.map { it.symbol }, "a coin Circle doesn't describe is left out")
            assertEquals(BigInteger("1234500000"), held[0].amountMinor)
            assertFalse(held[0].native)
            assertEquals("t-usdc", held[0].tokenId)
            assertEquals(BigInteger.ONE, held[1].amountMinor)
            assertTrue(held[1].native)
            assertEquals("TreasuryBalance(USDC, ***)", held[0].toString())
        }

    private fun transfer(amountMinor: String, key: UUID = UUID.randomUUID()) =
        TreasuryTransfer(
            walletId = walletId,
            blockchain = "BASE",
            tokenId = "t-usdc",
            decimals = 6,
            amountMinor = BigInteger(amountMinor),
            destinationAddress = "0x00000000000000000000000000000000000000aa",
            refId = "circaso-fee-withdrawal:1",
            idempotencyKey = key,
        )

    @Test
    fun `a transfer names the coin by Circle's token id and the amount as an exact decimal`() =
        runBlocking<Unit> {
            circle.reply("POST $TRANSFER", """{"data":{"id":"tx-1","state":"INITIATED"}}""", 201)
            val key = UUID.randomUUID()

            val sent = treasury.send(Network.MAINNET, transfer("123456789012345678901234567", key)).data()

            assertEquals("tx-1", sent.transactionId)
            assertEquals(TreasuryTransactionState.IN_FLIGHT, sent.state)
            val request = circle.requests("POST $TRANSFER").single()
            assertNull(request.headers["Idempotency-Key"])
            val body = request.sent<DeveloperTransferBody>()
            assertEquals(key.toString(), body.idempotencyKey)
            assertEquals(walletId, body.walletId)
            assertEquals("t-usdc", body.tokenId)
            assertEquals(listOf("123456789012345678901.234567"), body.amounts, "no floating point on the way")
            assertEquals("0x00000000000000000000000000000000000000aa", body.destinationAddress)
            assertEquals("MEDIUM", body.feeLevel)
            assertEquals("circaso-fee-withdrawal:1", body.refId)
            assertContentEquals(secretBytes, secretIn(body.entitySecretCiphertext))
        }

    @Test
    fun `minor units become Circle's decimal string exactly`() {
        assertEquals("2.5", CircleTreasury.decimalOf(BigInteger("2500000"), 6))
        assertEquals("0.000001", CircleTreasury.decimalOf(BigInteger.ONE, 6))
        assertEquals("100", CircleTreasury.decimalOf(BigInteger("100000000"), 6))
        assertEquals("0.000000000000000001", CircleTreasury.decimalOf(BigInteger.ONE, 18))
        assertEquals("7", CircleTreasury.decimalOf(BigInteger("7"), 0))
    }

    @Test
    fun `a refusal says Circle's status and code and nothing else, and an outage is an unknown outcome`() =
        runBlocking<Unit> {
            circle.reply("POST $TRANSFER", """{"code":155201,"message":"secret-looking text $secretHex"}""", 400)
            val refused = treasury.send(Network.MAINNET, transfer("1")) as Resource.Error
            val refusal = assertIs<TreasuryRefusal>(refused.error)
            assertEquals(400 to 155201, refusal.status to refusal.providerCode)
            assertEquals("The custody provider refused the request (HTTP 400, code 155201).", refusal.message)

            circle.reply("POST $TRANSFER", """{"code":-1}""", 503)
            assertIs<IntegrationError.Unavailable>(
                (treasury.send(Network.MAINNET, transfer("1")) as Resource.Error).error,
            )

            circle.reply("POST $TRANSFER", """{"data":{}}""", 201)
            assertIs<IntegrationError.Unavailable>(
                (treasury.send(Network.MAINNET, transfer("1")) as Resource.Error).error,
                "a yes we can't read is unknown: the same key is asked again",
            )

            circle.down = true
            assertIs<IntegrationError.Unavailable>(
                (treasury.send(Network.MAINNET, transfer("1")) as Resource.Error).error,
            )
        }

    @Test
    fun `without the entity secret nothing is signed and Circle isn't asked`() =
        runBlocking<Unit> {
            val refused = readOnly.send(Network.TESTNET, transfer("1"))
            val noAccount = treasury.balances(Network.TESTNET, walletId)

            assertIs<IntegrationError.Unavailable>((refused as Resource.Error).error)
            assertIs<IntegrationError.Unavailable>((noAccount as Resource.Error).error)
            assertTrue(circle.clients.requests.isEmpty())
        }

    @Test
    fun `the fee estimate is the medium level's network fee, asked without a ciphertext`() =
        runBlocking<Unit> {
            circle.reply(
                "POST $ESTIMATE",
                """{"data":{"low":{"networkFee":"0.00001"},"medium":{"networkFee":"0.000021","gasLimit":"65000"},
                    "high":{"networkFee":"0.00005"}}}""",
            )

            val fee = treasury.estimateTransfer(Network.MAINNET, transfer("2500000")).data()

            assertEquals(BigDecimal("0.000021"), fee)
            val request = circle.requests("POST $ESTIMATE").single()
            assertEquals(
                EstimateTransferBody(walletId, "t-usdc", listOf("2.5"), "0x00000000000000000000000000000000000000aa"),
                request.sent(),
            )
            assertFalse("entitySecretCiphertext" in request.text())
        }

    @Test
    fun `a transaction is read with its hash, and Circle's states become ours`() =
        runBlocking<Unit> {
            fun reply(state: String, extra: String = "") =
                circle.reply(
                    "GET /v1/w3s/transactions/tx-1",
                    """{"data":{"transaction":{"id":"tx-1","state":"$state","txHash":"0xhash"$extra}}}""",
                )

            suspend fun state() = treasury.transaction(Network.MAINNET, "tx-1").data()

            for (flying in listOf("INITIATED", "CLEARED", "QUEUED", "SENT", "STUCK", "SOMETHING_NEW")) {
                reply(flying)
                assertEquals(TreasuryTransactionState.IN_FLIGHT, state().state, flying)
            }
            for (done in listOf("CONFIRMED", "COMPLETE")) {
                reply(done)
                assertEquals(TreasuryTransactionState.CONFIRMED to "0xhash", state().let { it.state to it.txHash })
            }
            for (failed in listOf("FAILED", "DENIED", "CANCELLED")) {
                reply(failed, ""","errorReason":"INSUFFICIENT_NATIVE_TOKEN"""")
                assertEquals(
                    TreasuryTransactionState.FAILED to "INSUFFICIENT_NATIVE_TOKEN",
                    state().let { it.state to it.failureReason },
                )
            }
        }

    private companion object {
        const val PUBLIC_KEY = "/v1/w3s/config/entity/publicKey"
        const val WALLET_SETS = "/v1/w3s/developer/walletSets"
        const val WALLETS = "/v1/w3s/developer/wallets"
        const val TRANSFER = "/v1/w3s/developer/transactions/transfer"
        const val ESTIMATE = "/v1/w3s/transactions/transfer/estimateFee"
    }
}
