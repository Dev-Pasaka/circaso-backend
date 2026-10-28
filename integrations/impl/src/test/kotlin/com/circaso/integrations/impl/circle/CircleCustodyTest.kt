package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.integrations.api.CustodyWalletState
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PinChallenge
import com.circaso.integrations.api.TransferRequest
import com.circaso.integrations.impl.text
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircleCustodyTest {
    private val circle = FakeCircle()
    private val custody = circle.custody
    private val user = "circle-user-1"

    /** The body we sent, read back as the class it was written from. */
    private inline fun <reified T> HttpRequestData.sent(): T = circleJson.decodeFromString(text())

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    @Test
    fun `createUser registers our circle user id with the API key and an idempotency key`() =
        runBlocking<Unit> {
            circle.reply("POST /v1/w3s/users", """{"data":{"id":"$user","pinStatus":"UNSET"}}""")

            assertIs<Resource.Success<Unit>>(custody.createUser(user))

            val request = circle.requests("POST /v1/w3s/users").single()
            assertEquals("https://api.circle.com/v1/w3s/users", request.url.toString())
            assertEquals("Bearer ${FakeCircle.API_KEY}", request.headers[HttpHeaders.Authorization])
            val body = request.sent<CreateUserBody>()
            assertEquals(user, body.userId)
            assertEquals(
                body.idempotencyKey,
                request.headers["Idempotency-Key"],
                "the key in the body is also the header, so the shared client may retry the POST",
            )
        }

    @Test
    fun `a user that already exists is success`() =
        runBlocking<Unit> {
            circle.reply(
                "POST /v1/w3s/users",
                """{"code":155101,"message":"Existing user already created with the provided userId."}""",
                409,
            )
            assertIs<Resource.Success<Unit>>(custody.createUser(user))
        }

    @Test
    fun `createWallet asks for the initialize challenge on the configured chain as the user`() =
        runBlocking<Unit> {
            circle.reply("POST /v1/w3s/user/initialize", """{"data":{"challengeId":"challenge-1"}}""")
            val key = UUID.randomUUID()

            val challenge = custody.createWallet(user, key).data()

            assertEquals("challenge-1", challenge.challengeId)
            assertTrue(challenge.needsSignature)
            assertEquals("app-1", challenge.sdk.appId)
            assertEquals(circle.tokenOf(1), challenge.sdk.userToken)
            assertEquals("key-1", challenge.sdk.encryptionKey)
            assertEquals(circle.now.plus(Duration.ofMinutes(60)), challenge.sdk.expiresAt, "from the token's exp")

            val token = circle.requests("POST /v1/w3s/users/token").single().sent<UserTokenBody>()
            assertEquals(user, token.userId)
            val request = circle.requests("POST /v1/w3s/user/initialize").single()
            assertEquals(circle.tokenOf(1), request.headers["X-User-Token"])
            assertEquals(key.toString(), request.headers["Idempotency-Key"])
            val body = request.sent<InitializeBody>()
            assertEquals(key.toString(), body.idempotencyKey)
            assertEquals("SCA", body.accountType)
            assertEquals(
                listOf("BASE-SEPOLIA"),
                body.blockchains,
            )
        }

    @Test
    fun `a user already initialized is success with nothing to sign`() =
        runBlocking<Unit> {
            circle.reply(
                "POST /v1/w3s/user/initialize",
                """{"code":155106,"message":"The user has already been initialized."}""",
                409,
            )

            val challenge = custody.createWallet(user, UUID.randomUUID()).data()

            assertFalse(challenge.needsSignature)
            assertEquals(circle.tokenOf(1), challenge.sdk.userToken)
        }

    @Test
    fun `a user token is reused until five minutes before it expires`() =
        runBlocking<Unit> {
            val first = custody.sdkSession(user).data()
            circle.now = circle.now.plus(Duration.ofMinutes(54))
            val second = custody.sdkSession(user).data()
            assertEquals(1, circle.tokensIssued)
            assertEquals(first.userToken, second.userToken)

            circle.now = circle.now.plus(Duration.ofMinutes(1)).plusSeconds(1)
            val third = custody.sdkSession(user).data()
            assertEquals(2, circle.tokensIssued, "inside the last five minutes a new token is fetched")
            assertEquals(circle.tokenOf(2), third.userToken)
            assertEquals(circle.now.plus(Duration.ofMinutes(60)), third.expiresAt)
        }

    @Test
    fun `tokens are kept per user`() =
        runBlocking<Unit> {
            custody.sdkSession("user-a")
            custody.sdkSession("user-b")
            custody.sdkSession("user-a")
            assertEquals(2, circle.tokensIssued)
        }

    @Test
    fun `the retry with a new token sends the same idempotency key, so Circle can't act twice`() =
        runBlocking<Unit> {
            val expired = """{"code":155104,"message":"The user token has expired."}"""
            circle.reply("POST /v1/w3s/user/transactions/transfer", expired, 403)
            circle.reply("PUT /v1/w3s/user/pin", expired, 403)
            val transferKey = UUID.randomUUID()
            val pinKey = UUID.randomUUID()

            custody.prepareTransfer(
                user,
                TransferRequest("BASE-SEPOLIA", "w-1", "0x1", Money(1, Asset.USDC), transferKey, "ref"),
            )
            custody.pinChallenge(user, PinChallenge.CHANGE, pinKey)

            val transfers = circle.requests("POST /v1/w3s/user/transactions/transfer")
            assertEquals(2, transfers.size)
            assertEquals(listOf(circle.tokenOf(1), circle.tokenOf(2)), transfers.map { it.headers["X-User-Token"] })
            for (attempt in transfers) {
                assertEquals(transferKey.toString(), attempt.headers["Idempotency-Key"])
                assertEquals(transferKey.toString(), attempt.sent<TransferBody>().idempotencyKey)
            }
            val pins = circle.requests("PUT /v1/w3s/user/pin")
            assertEquals(2, pins.size)
            for (attempt in pins) {
                assertEquals(pinKey.toString(), attempt.headers["Idempotency-Key"])
                assertEquals(pinKey.toString(), attempt.sent<PinBody>().idempotencyKey)
            }
        }

    @Test
    fun `a token Circle refuses is dropped and the call made once more with a new one`() =
        runBlocking<Unit> {
            custody.sdkSession(user)
            circle.reply("GET /v1/w3s/wallets", """{"code":155104,"message":"The user token has expired."}""", 403)
            val refused = custody.wallets(user)
            assertIs<IntegrationError.Unavailable>((refused as Resource.Error).error)
            assertEquals(2, circle.tokensIssued, "one retry with a fresh token, not a loop")
            assertEquals(
                listOf(circle.tokenOf(1), circle.tokenOf(2)),
                circle.requests("GET /v1/w3s/wallets").map { it.headers["X-User-Token"] },
            )

            circle.reply("GET /v1/w3s/wallets", """{"data":{"wallets":[]}}""")
            assertEquals(emptyList(), custody.wallets(user).data())
            assertEquals(2, circle.tokensIssued, "the fresh token is kept")
        }

    @Test
    fun `wallets come back in our words with the address exactly as Circle gave it`() =
        runBlocking<Unit> {
            circle.reply(
                "GET /v1/w3s/wallets",
                """{"data":{"wallets":[{"id":"w-1","state":"LIVE","walletSetId":"s","custodyType":"ENDUSER",""" +
                    """"userId":"$user","address":"0xAbC0000000000000000000000000000000000001",""" +
                    """"blockchain":"BASE-SEPOLIA","accountType":"SCA"},""" +
                    """{"id":"w-2","state":"FROZEN","address":"0x2","blockchain":"BASE","accountType":"SCA"}]}}""",
            )

            val wallets = custody.wallets(user).data()

            assertEquals("w-1", wallets[0].walletId)
            assertEquals("0xAbC0000000000000000000000000000000000001", wallets[0].address)
            assertEquals("BASE-SEPOLIA", wallets[0].blockchain)
            assertEquals("SCA", wallets[0].accountType)
            assertEquals(CustodyWalletState.LIVE, wallets[0].state)
            assertEquals(CustodyWalletState.FROZEN, wallets[1].state)
        }

    @Test
    fun `the balance is the USDC line on the configured chain, in minor units`() =
        runBlocking<Unit> {
            circle.reply(
                "GET /v1/w3s/wallets/w-1/balances",
                """{"data":{"tokenBalances":[""" +
                    """{"token":{"blockchain":"BASE-SEPOLIA","tokenAddress":"","symbol":"ETH-SEPOLIA"},""" +
                    """"amount":"1"},""" +
                    """{"token":{"blockchain":"BASE","tokenAddress":"${FakeCircle.USDC_ADDRESS}"},"amount":"9"},""" +
                    """{"token":{"blockchain":"BASE-SEPOLIA",""" +
                    """"tokenAddress":"0x036cbd53842c5426634e7929541ec2318f3dcf7e"},"amount":"20.5"}]}}""",
            )

            assertEquals(Money(20_500_000, Asset.USDC), custody.balance(user, "w-1", Asset.USDC).data())
            assertEquals(
                circle.tokenOf(1),
                circle.requests("GET /v1/w3s/wallets/w-1/balances").single().headers["X-User-Token"],
            )
        }

    @Test
    fun `a wallet that never held USDC has a zero balance`() =
        runBlocking<Unit> {
            circle.reply("GET /v1/w3s/wallets/w-1/balances", """{"data":{"tokenBalances":[]}}""")
            assertEquals(Money(0, Asset.USDC), custody.balance(user, "w-1", Asset.USDC).data())
        }

    @Test
    fun `assets that aren't offered are refused without calling Circle`() =
        runBlocking<Unit> {
            assertIs<IntegrationError.Rejected>((custody.balance(user, "w-1", Asset.USDT) as Resource.Error).error)
            val transfer = TransferRequest("BASE-SEPOLIA", "w-1", "0x1", Money(1, Asset.USDT), UUID.randomUUID(), "ref")
            assertIs<IntegrationError.Rejected>((custody.prepareTransfer(user, transfer) as Resource.Error).error)
            assertTrue(circle.clients.requests.isEmpty())
        }

    @Test
    fun `prepareTransfer names USDC by address and chain, with our key and refId`() =
        runBlocking<Unit> {
            circle.reply("POST /v1/w3s/user/transactions/transfer", """{"data":{"challengeId":"challenge-9"}}""")
            val key = UUID.randomUUID()
            val request =
                TransferRequest(
                    blockchain = "BASE-SEPOLIA",
                    walletId = "w-1",
                    destinationAddress = "0x00000000000000000000000000000000000000b0",
                    amount = Money(20_500_000, Asset.USDC),
                    idempotencyKey = key,
                    refId = "transfer-1",
                )

            val challenge = custody.prepareTransfer(user, request).data()

            assertEquals("challenge-9", challenge.challengeId)
            val sent = circle.requests("POST /v1/w3s/user/transactions/transfer").single()
            assertEquals(circle.tokenOf(1), sent.headers["X-User-Token"])
            assertEquals(key.toString(), sent.headers["Idempotency-Key"])
            val body = sent.sent<TransferBody>()
            assertEquals(key.toString(), body.idempotencyKey)
            assertEquals("w-1", body.walletId)
            assertEquals("0x00000000000000000000000000000000000000b0", body.destinationAddress)
            assertEquals(listOf("20.5"), body.amounts)
            assertEquals(FakeCircle.USDC_ADDRESS, body.tokenAddress)
            assertEquals("BASE-SEPOLIA", body.blockchain)
            assertFalse("tokenId" in sent.text(), "Circle refuses tokenId together with tokenAddress")
            assertEquals("MEDIUM", body.feeLevel)
            assertEquals("transfer-1", body.refId)
        }

    @Test
    fun `Circle's user errors become our custody errors and everything else is unavailable or rejected`() =
        runBlocking<Unit> {
            val route = "POST /v1/w3s/user/transactions/transfer"
            val transfer = TransferRequest("BASE-SEPOLIA", "w-1", "0x1", Money(1, Asset.USDC), UUID.randomUUID(), "ref")
            val cases =
                listOf(
                    Triple(400, 155119, IntegrationError.PinLocked::class),
                    Triple(400, 155201, IntegrationError.InsufficientFunds::class),
                    Triple(400, 155258, IntegrationError.InsufficientFunds::class),
                    Triple(400, 155219, IntegrationError.AddressInvalid::class),
                    Triple(400, 2, IntegrationError.Rejected::class),
                    Triple(404, 156001, IntegrationError.Rejected::class),
                    Triple(401, 401, IntegrationError.Unavailable::class),
                    Triple(429, 429, IntegrationError.Unavailable::class),
                    Triple(500, 500, IntegrationError.Unavailable::class),
                )
            for ((status, code, expected) in cases) {
                circle.reply(route, """{"code":$code,"message":"x"}""", status)
                val error = (custody.prepareTransfer(user, transfer) as Resource.Error).error
                assertEquals(expected, error::class, "status $status code $code")
            }

            circle.down = true
            assertIs<IntegrationError.Unavailable>(
                (custody.validateAddress("BASE-SEPOLIA", "0x1") as Resource.Error).error,
            )
        }

    @Test
    fun `transaction reads one transaction as the user and resolves its token once`() =
        runBlocking<Unit> {
            circle.reply("GET /v1/w3s/tokens/${FakeCircle.USDC_TOKEN_ID}", FakeCircle.USDC_TOKEN_REPLY)
            circle.reply(
                "GET /v1/w3s/transactions/tx-1",
                """{"data":{"transaction":${CircleWebhookTest.transaction("OUTBOUND", "COMPLETE")}}}""",
            )

            val transaction = custody.transaction(user, "tx-1").data()
            custody.transaction(user, "tx-1")

            assertEquals("tx-1", transaction.circleTxId)
            assertEquals(CustodyDirection.OUT, transaction.direction)
            assertEquals(CustodyTransactionState.CONFIRMED, transaction.state)
            assertEquals(Money(20_000_000, Asset.USDC), transaction.amount)
            assertEquals(1, circle.requests("GET /v1/w3s/tokens/${FakeCircle.USDC_TOKEN_ID}").size)
        }

    @Test
    fun `validateAddress checks on the chain it is given`() =
        runBlocking<Unit> {
            circle.reply("POST /v1/w3s/transactions/validateAddress", """{"data":{"isValid":true}}""")
            assertTrue(custody.validateAddress("BASE-SEPOLIA", "0xabc").data())
            val body = circle.requests("POST /v1/w3s/transactions/validateAddress").single().sent<ValidateAddressBody>()
            assertEquals("BASE-SEPOLIA", body.blockchain)
            assertEquals("0xabc", body.address)

            circle.reply("POST /v1/w3s/transactions/validateAddress", """{"data":{"isValid":false}}""")
            assertFalse(custody.validateAddress("BASE-SEPOLIA", "0x123").data())
        }

    @Test
    fun `pin challenges change with PUT and restore with POST`() =
        runBlocking<Unit> {
            circle.reply("PUT /v1/w3s/user/pin", """{"data":{"challengeId":"change-1"}}""")
            circle.reply("POST /v1/w3s/user/pin/restore", """{"data":{"challengeId":"restore-1"}}""")
            val key = UUID.randomUUID()

            assertEquals("change-1", custody.pinChallenge(user, PinChallenge.CHANGE, key).data().challengeId)
            assertEquals("restore-1", custody.pinChallenge(user, PinChallenge.RESTORE, key).data().challengeId)
            val change = circle.requests("PUT /v1/w3s/user/pin").single()
            assertEquals(key.toString(), change.sent<PinBody>().idempotencyKey)
            assertEquals(circle.tokenOf(1), change.headers["X-User-Token"])
        }

    @Test
    fun `a success without a challenge id is not taken for one`() =
        runBlocking<Unit> {
            circle.reply("PUT /v1/w3s/user/pin", """{"data":{}}""")
            val result = custody.pinChallenge(user, PinChallenge.CHANGE, UUID.randomUUID())
            assertIs<IntegrationError.Unavailable>((result as Resource.Error).error)
        }

    @Test
    fun `amounts convert exactly between minor units and Circle's decimals`() {
        assertEquals("20.5", CircleTransactions.decimalOf(Money(20_500_000, Asset.USDC)))
        assertEquals("0.000001", CircleTransactions.decimalOf(Money(1, Asset.USDC)))
        assertEquals("100", CircleTransactions.decimalOf(Money(100_000_000, Asset.USDC)))
        assertEquals(1L, CircleTransactions.minorUnits("0.000001", Asset.USDC))
        assertEquals(20_500_000L, CircleTransactions.minorUnits("20.50", Asset.USDC))
        assertNull(CircleTransactions.minorUnits("0.0000001", Asset.USDC), "finer than USDC's 6 decimals")
        assertNull(CircleTransactions.minorUnits("abc", Asset.USDC))
    }

    @Test
    fun `the sdk session never prints its token`() =
        runBlocking<Unit> {
            val session = custody.sdkSession(user).data()
            assertFalse(session.userToken in session.toString())
            assertFalse(session.encryptionKey in session.toString())
            assertTrue(Instant.EPOCH.isBefore(session.expiresAt))
        }
}
