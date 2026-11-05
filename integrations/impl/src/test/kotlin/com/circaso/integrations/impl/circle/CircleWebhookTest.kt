package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyChallenge
import com.circaso.integrations.api.CustodyChallengeKind
import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyNotifications
import com.circaso.integrations.api.CustodyOperation
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircleWebhookTest {
    private val circle = FakeCircle()
    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    private val received = CopyOnWriteArrayList<CustodyTransaction>()
    private val challenges = CopyOnWriteArrayList<CustodyChallenge>()
    private var answer: Resource<Unit> = Resource.success(Unit)
    private var listener: CustodyNotifications? =
        object : CustodyNotifications {
            override suspend fun onTransaction(update: CustodyTransaction): Resource<Unit> {
                received += update
                return answer
            }

            override suspend fun onWalletChallenge(challenge: CustodyChallenge): Resource<Unit> {
                challenges += challenge
                return answer
            }
        }
    private val webhook = CircleWebhook(circle.client, circle.settings, circle.clock) { listener }

    init {
        circle.reply(
            "GET /v2/notifications/publicKey/$KEY_ID",
            """{"data":{"id":"$KEY_ID","algorithm":"ECDSA_SHA_256",""" +
                """"publicKey":"${Base64.getEncoder().encodeToString(keyPair.public.encoded)}",""" +
                """"createDate":"2026-01-15T21:47:35.107250Z"}}""",
        )
        circle.reply("GET /v1/w3s/tokens/${FakeCircle.USDC_TOKEN_ID}", FakeCircle.USDC_TOKEN_REPLY)
    }

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun sign(body: ByteArray, keys: KeyPair = keyPair): String =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(body)
            Base64.getEncoder().encodeToString(sign())
        }

    private fun headers(body: ByteArray, keyId: String = KEY_ID) =
        mapOf("x-circle-key-id" to keyId, "x-circle-signature" to sign(body))

    @Test
    fun `a body signed with Circle's key verifies, and the key is fetched once`() =
        runBlocking<Unit> {
            val body = notification("transactions.inbound", transaction("INBOUND", "COMPLETE")).toByteArray()

            assertTrue(webhook.verifySignature(headers(body), body))
            assertTrue(webhook.verifySignature(headers(body), body))

            val fetches = circle.requests("GET /v2/notifications/publicKey/$KEY_ID")
            assertEquals(1, fetches.size, "a key id always names the same key")
            assertEquals("Bearer ${FakeCircle.API_KEY}", fetches.single().headers["Authorization"])
        }

    @Test
    fun `a changed body, another key, an unknown key id or missing headers don't verify`() =
        runBlocking<Unit> {
            val body = notification("transactions.inbound", transaction("INBOUND", "COMPLETE")).toByteArray()
            val other =
                KeyPairGenerator.getInstance("EC").run {
                    initialize(ECGenParameterSpec("secp256r1"))
                    generateKeyPair()
                }

            assertFalse(webhook.verifySignature(headers(body), body + ' '.code.toByte()))
            assertFalse(
                webhook.verifySignature(
                    mapOf("x-circle-key-id" to KEY_ID, "x-circle-signature" to sign(body, other)),
                    body,
                ),
            )
            assertFalse(webhook.verifySignature(headers(body, "00000000-0000-0000-0000-000000000000"), body))
            assertFalse(webhook.verifySignature(headers(body, "../../v1/w3s/wallets"), body))
            assertFalse(webhook.verifySignature(mapOf("x-circle-key-id" to KEY_ID), body))
            assertFalse(webhook.verifySignature(mapOf("x-circle-key-id" to KEY_ID, "x-circle-signature" to "%%"), body))
            assertTrue(
                circle.clients.requests.all { it.url.encodedPath.startsWith("/v2/notifications/publicKey/") },
                "a key id that isn't a UUID never reaches Circle's URL",
            )
            assertEquals(
                1,
                circle.requests("GET /v2/notifications/publicKey/00000000-0000-0000-0000-000000000000").size,
            )
        }

    @Test
    fun `the notification id is the delivery id and there is no replay window`() {
        val body = notification("transactions.inbound", transaction("INBOUND", "COMPLETE")).toByteArray()
        assertEquals("notification-1", webhook.deliveryId(emptyMap(), body))
        assertNull(webhook.sentAt(emptyMap(), body), "Circle signs no send time")
        assertNull(webhook.deliveryId(emptyMap(), "not json".toByteArray()))
        assertTrue(webhook.answersAfterTranslation)
        assertEquals("circle", webhook.name)
    }

    @Test
    fun `an inbound USDC transfer reaches the wallet in our words`() =
        runBlocking<Unit> {
            val body = notification("transactions.inbound", transaction("INBOUND", "COMPLETE"))

            assertIs<Resource.Success<Unit>>(webhook.translate(body.toByteArray()))

            val update = received.single()
            assertEquals("tx-1", update.circleTxId)
            assertEquals("circle-user-1", update.circleUserId)
            assertEquals("wallet-1", update.walletId)
            assertEquals("BASE-SEPOLIA", update.blockchain)
            assertEquals(CustodyDirection.IN, update.direction)
            assertEquals(CustodyTransactionState.CONFIRMED, update.state)
            assertEquals(Money(20_000_000, Asset.USDC), update.amount)
            assertEquals("0x00000000000000000000000000000000000000a1", update.sourceAddress)
            assertEquals("0x00000000000000000000000000000000000000b2", update.destinationAddress)
            assertEquals("0xhash", update.txHash)
            assertEquals("", update.refId)
            assertEquals("", update.failureReason)
            assertEquals(Instant.parse("2026-10-27T08:01:30Z"), update.updatedAt)
        }

    @Test
    fun `Circle's transaction states map to ours`() =
        runBlocking<Unit> {
            val expected =
                mapOf(
                    "INITIATED" to CustodyTransactionState.SUBMITTED,
                    "CLEARED" to CustodyTransactionState.SUBMITTED,
                    "QUEUED" to CustodyTransactionState.SUBMITTED,
                    "SENT" to CustodyTransactionState.SUBMITTED,
                    "STUCK" to CustodyTransactionState.STUCK,
                    "CONFIRMED" to CustodyTransactionState.CONFIRMED,
                    "COMPLETE" to CustodyTransactionState.CONFIRMED,
                    "FAILED" to CustodyTransactionState.FAILED,
                    "DENIED" to CustodyTransactionState.FAILED,
                    "CANCELLED" to CustodyTransactionState.FAILED,
                    "SOMETHING_NEW" to CustodyTransactionState.SUBMITTED,
                )
            for ((state, ours) in expected) {
                webhook.translate(notification("transactions.outbound", transaction("OUTBOUND", state)).toByteArray())
                assertEquals(ours, received.last().state, state)
            }
        }

    @Test
    fun `an outbound transfer carries our refId and the failure reason`() =
        runBlocking<Unit> {
            val failed =
                transaction("OUTBOUND", "FAILED", refId = "transfer-7", errorReason = "INSUFFICIENT_NATIVE_TOKEN")
            webhook.translate(notification("transactions.outbound", failed).toByteArray())

            val update = received.single()
            assertEquals(CustodyDirection.OUT, update.direction)
            assertEquals("transfer-7", update.refId)
            assertEquals(CustodyTransactionState.FAILED, update.state)
            assertEquals("INSUFFICIENT_NATIVE_TOKEN", update.failureReason)
        }

    @Test
    fun `a token other than USDC and other notification types are acknowledged and left alone`() =
        runBlocking<Unit> {
            val otherToken = "7adb2b7d-c9cd-5164-b2d4-b73b088274dc"
            circle.reply(
                "GET /v1/w3s/tokens/$otherToken",
                """{"data":{"token":{"id":"$otherToken","blockchain":"BASE-SEPOLIA","tokenAddress":"",""" +
                    """"symbol":"ETH-SEPOLIA","isNative":true}}}""",
            )
            val eth = transaction("INBOUND", "COMPLETE", tokenId = otherToken)

            assertIs<Resource.Success<Unit>>(webhook.translate(notification("transactions.inbound", eth).toByteArray()))
            assertIs<Resource.Success<Unit>>(
                webhook.translate(notification("challenges.initialize", """{"id":"c-1"}""").toByteArray()),
            )
            assertTrue(received.isEmpty())
        }

    @Test
    fun `a completed PIN setup or wallet creation reaches the wallet with Circle's user`() =
        runBlocking<Unit> {
            val setup = notification("challenges.initialize", challenge("INITIALIZE", status = "COMPLETE"))
            assertIs<Resource.Success<Unit>>(webhook.translate(setup.toByteArray()))
            // Circle's pages also show the outcome as `state`, and spell it COMPLETED.
            val added =
                notification("challenges.createWallet", challenge("CREATE_WALLET", status = null, state = "COMPLETED"))
            assertIs<Resource.Success<Unit>>(webhook.translate(added.toByteArray()))

            assertEquals(
                listOf(
                    CustodyChallenge(CIRCLE_USER, CHALLENGE_ID, CustodyChallengeKind.SETUP, completed = true),
                    CustodyChallenge(CIRCLE_USER, CHALLENGE_ID, CustodyChallengeKind.CREATE_WALLET, completed = true),
                ),
                challenges,
            )
            assertTrue(received.isEmpty())
        }

    @Test
    fun `a failed setup is passed on as failed, one still open and other challenges are ignored`() =
        runBlocking<Unit> {
            val failed = notification("challenges.initialize", challenge("INITIALIZE", status = "FAILED"))
            assertIs<Resource.Success<Unit>>(webhook.translate(failed.toByteArray()))
            assertEquals(listOf(false), challenges.map { it.completed })

            for (status in listOf("PENDING", "IN_PROGRESS")) {
                val open = notification("challenges.initialize", challenge("INITIALIZE", status = status))
                assertIs<Resource.Success<Unit>>(webhook.translate(open.toByteArray()))
            }
            val transfer = notification("challenges.createTransaction", challenge("CREATE_TRANSACTION"))
            assertIs<Resource.Success<Unit>>(webhook.translate(transfer.toByteArray()))
            val unknown = notification("wallets.created", """{"id":"w-1"}""")
            assertIs<Resource.Success<Unit>>(webhook.translate(unknown.toByteArray()))
            assertEquals(1, challenges.size)
        }

    @Test
    fun `a setup the wallet couldn't follow makes Circle deliver it again`() =
        runBlocking<Unit> {
            val setup = notification("challenges.initialize", challenge("INITIALIZE")).toByteArray()
            answer = Resource.error(IntegrationError.Unavailable("Circle"))
            assertIs<Resource.Error>(webhook.translate(setup))
            listener = null
            assertIs<Resource.Error>(webhook.translate(setup))
        }

    @Test
    fun `a failure reaches the gateway so Circle delivers again`() =
        runBlocking<Unit> {
            val body = notification("transactions.inbound", transaction("INBOUND", "COMPLETE")).toByteArray()

            answer = Resource.error(IntegrationError.Unavailable("wallet"))
            assertIs<Resource.Error>(webhook.translate(body))

            listener = null
            assertIs<IntegrationError.Unavailable>((webhook.translate(body) as Resource.Error).error)

            assertIs<IntegrationError.Rejected>((webhook.translate("{".toByteArray()) as Resource.Error).error)
            // Retrying a body that can't be read changes nothing, so it is acknowledged (its shape logged).
            assertIs<Resource.Success<Unit>>(
                webhook.translate(
                    notification("transactions.inbound", """{"transactionType":"INBOUND","tokenId":"t"}""")
                        .toByteArray(),
                ),
            )

            circle.replies.remove("GET /v1/w3s/tokens/${FakeCircle.USDC_TOKEN_ID}")
            circle.down = true
            listener =
                object : CustodyNotifications {
                    override suspend fun onTransaction(update: CustodyTransaction) = Resource.success(Unit)
                }
            val unresolved = transaction("INBOUND", "COMPLETE", tokenId = "11111111-1111-1111-1111-111111111111")
            assertIs<IntegrationError.Unavailable>(
                (webhook.translate(notification("transactions.inbound", unresolved).toByteArray()) as Resource.Error)
                    .error,
            )
        }

    @Test
    fun `the batch's execution is followed in all three shapes Circle sends - API, webhook without operation`() =
        runBlocking<Unit> {
            for (payload in listOf(CircleRecords.SENDER_EXECUTION, CircleRecords.SENDER_EXECUTION_WEBHOOK)) {
                val body = notification("transactions.outbound", payload).toByteArray()
                assertIs<Resource.Success<Unit>>(webhook.translate(body), "never 400: Circle would retry for nothing")
            }

            assertEquals(2, received.size)
            for (update in received) {
                assertEquals(CustodyOperation.CONTRACT_EXECUTION, update.operation)
                assertEquals(CustodyDirection.OUT, update.direction)
                assertEquals("8822478d-1b93-54c5-87b6-619290cd7d80", update.circleTxId)
                assertEquals("5e1a6fd7-e13b-51c7-a1b0-cf13fb183238", update.walletId)
                assertEquals(CircleRecords.TX_HASH, update.txHash)
                assertEquals("", update.refId)
                assertEquals(CustodyTransactionState.CONFIRMED, update.state)
            }
        }

    @Test
    fun `the payee's leg is a plain inbound transfer, the fee wallet's is marked as the developer's`() =
        runBlocking<Unit> {
            webhook.translate(notification("transactions.inbound", CircleRecords.PAYEE_INBOUND).toByteArray()).data()
            webhook.translate(notification("transactions.inbound", CircleRecords.FEE_INBOUND).toByteArray()).data()

            val (payee, fee) = received
            assertEquals(7_699_911L, payee.amount.amountMinor)
            assertFalse(payee.developerWallet)
            assertEquals(CustodyOperation.TRANSFER, payee.operation)
            assertEquals(100_000L, fee.amount.amountMinor)
            assertTrue(fee.developerWallet)
        }

    @Test
    fun `an inbound record without a token, or without an id, is acknowledged and not handed on`() =
        runBlocking<Unit> {
            val inbound = CircleRecords.SENDER_EXECUTION_WEBHOOK.replace("OUTBOUND", "INBOUND")
            val noId = CircleRecords.PAYEE_INBOUND.replace(""""id":"54bc4e3c-fa44-57a4-9f41-6426a126a9c8",""", "")

            assertIs<Resource.Success<Unit>>(
                webhook.translate(notification("transactions.inbound", inbound).toByteArray()),
            )
            assertIs<Resource.Success<Unit>>(
                webhook.translate(notification("transactions.inbound", noId).toByteArray()),
            )
            assertTrue(received.isEmpty())
        }

    @Test
    fun `an unreadable notification's shape names its fields and their presence, never a value`() {
        val noId = CircleRecords.PAYEE_INBOUND.replace(""""id":"54bc4e3c-fa44-57a4-9f41-6426a126a9c8",""", "")
        val shape = CircleWebhook.shapeOf(notification("transactions.inbound", noId).toByteArray())

        assertTrue("walletId" in shape.fields.split(","))
        assertTrue("id:absent" in shape.present)
        assertTrue("tokenId:set" in shape.present)
        assertTrue("refId:absent" in shape.present)
        assertFalse("6e62c682" in shape.fields + shape.present, "no values")
        assertFalse("7.699911" in shape.fields + shape.present, "no amounts")
    }

    @Test
    fun `a key id Circle refused is not asked about again for a few minutes`() =
        runBlocking<Unit> {
            val junk = "11111111-2222-3333-4444-555555555555"
            val route = "GET /v2/notifications/publicKey/$junk"
            val body = "{}".toByteArray()

            repeat(3) { assertFalse(webhook.verifySignature(headers(body, junk), body)) }
            assertEquals(1, circle.requests(route).size, "Circle answered 404 once; the id is remembered as unknown")

            circle.now = circle.now.plus(CircleNotificationKeys.UNKNOWN_FOR).plusSeconds(1)
            assertFalse(webhook.verifySignature(headers(body, junk), body))
            assertEquals(2, circle.requests(route).size, "after the pause Circle is asked again")
        }

    @Test
    fun `a key id that couldn't be checked while Circle was down is asked again`() =
        runBlocking<Unit> {
            val body = "{}".toByteArray()
            circle.down = true
            assertFalse(webhook.verifySignature(headers(body), body))
            circle.down = false

            assertTrue(webhook.verifySignature(headers(body), body), "the real key still verifies")
            assertEquals(2, circle.requests("GET /v2/notifications/publicKey/$KEY_ID").size)
        }

    @Test
    fun `the unknown ids are bounded, so a flood of junk can't grow without end`() =
        runBlocking<Unit> {
            // Without the fetch cap, which would otherwise stop the flood long before the memory fills.
            val uncapped = CircleWebhook(circle.client, circle.settings, circle.clock, Int.MAX_VALUE) { listener }
            val body = "{}".toByteArray()
            val first = UUID.randomUUID().toString()
            uncapped.verifySignature(headers(body, first), body)
            repeat(CircleNotificationKeys.UNKNOWN_LIMIT) {
                uncapped.verifySignature(headers(body, UUID.randomUUID().toString()), body)
            }

            uncapped.verifySignature(headers(body, first), body)
            assertEquals(
                2,
                circle.requests("GET /v2/notifications/publicKey/$first").size,
                "when full the memory is emptied, so the first junk id is asked about again",
            )
        }

    @Test
    fun `a node fetches at most ten unknown keys a minute and refuses the rest as unverifiable`() =
        runBlocking<Unit> {
            val body = "{}".toByteArray()
            repeat(CircleNotificationKeys.FETCHES_PER_MINUTE) {
                assertFalse(webhook.verifySignature(headers(body, UUID.randomUUID().toString()), body))
            }
            assertEquals(10, circle.clients.requests.size)

            assertFalse(webhook.verifySignature(headers(body), body), "even Circle's real key waits for the cap")
            assertEquals(10, circle.clients.requests.size, "the eleventh fetch never went out")

            circle.now = circle.now.plusSeconds(61)
            assertTrue(webhook.verifySignature(headers(body), body), "a minute later the real key is fetched")
            assertEquals(11, circle.clients.requests.size)
        }

    @Test
    fun `a 401 or 403 from Circle is our key being wrong, so the id isn't remembered as unknown`() =
        runBlocking<Unit> {
            val id = "22222222-3333-4444-5555-666666666666"
            val body = "{}".toByteArray()
            for (status in listOf(401, 403)) {
                circle.reply("GET /v2/notifications/publicKey/$id", """{"code":401,"message":"x"}""", status)
                assertFalse(webhook.verifySignature(headers(body, id), body))
            }
            assertEquals(2, circle.requests("GET /v2/notifications/publicKey/$id").size)

            circle.reply("GET /v2/notifications/publicKey/$id", """{"code":2,"message":"x"}""", 422)
            assertFalse(webhook.verifySignature(headers(body, id), body))
            assertFalse(webhook.verifySignature(headers(body, id), body))
            assertEquals(3, circle.requests("GET /v2/notifications/publicKey/$id").size, "a 422 is remembered")
        }

    companion object {
        const val KEY_ID = "879dc113-5ca4-4ff7-a6b7-54652083fcf8"

        const val CIRCLE_USER = "2f1dcb5e-312a-4b15-8240-abeffc0e3463"
        const val CHALLENGE_ID = "c4d1da72-111e-4d52-bdbf-2e74a2d803d5"

        /** A challenge notification's `notification`, as Circle's docs show it (user-controlled wallets). */
        fun challenge(
            type: String,
            status: String? = "COMPLETE",
            state: String? = null,
        ): String =
            """{"id":"$CHALLENGE_ID","userId":"$CIRCLE_USER","type":"$type",""" +
                (status?.let { """"status":"$it",""" } ?: "") +
                (state?.let { """"state":"$it",""" } ?: "") +
                """"correlationIds":["54399e5a-1bf6-4921-9559-10c1115678cd"],"errorCode":0,"errorMessage":""}"""

        fun notification(type: String, payload: String): String =
            """{"subscriptionId":"sub-1","notificationId":"notification-1","notificationType":"$type",""" +
                """"notification":$payload,"timestamp":"2026-10-27T08:01:31Z","version":2}"""

        /** A transaction as Circle's API and webhooks show it (developers.circle.com, get-transaction). */
        fun transaction(
            type: String,
            state: String,
            refId: String = "",
            errorReason: String? = null,
            tokenId: String = FakeCircle.USDC_TOKEN_ID,
        ): String =
            """{"id":"tx-1","blockchain":"BASE-SEPOLIA","tokenId":"$tokenId","walletId":"wallet-1",""" +
                """"sourceAddress":"0x00000000000000000000000000000000000000a1",""" +
                """"destinationAddress":"0x00000000000000000000000000000000000000b2",""" +
                """"transactionType":"$type","custodyType":"ENDUSER","state":"$state","amounts":["20"],""" +
                """"nfts":null,"txHash":"0xhash","blockHash":"0xblock","blockHeight":1,""" +
                """"networkFee":"0.0000001","firstConfirmDate":"2026-10-27T08:01:10Z","operation":"TRANSFER",""" +
                """"userId":"circle-user-1","refId":"$refId",""" +
                (errorReason?.let { """"errorReason":"$it",""" } ?: "") +
                """"abiParameters":null,"createDate":"2026-10-27T08:00:00Z","updateDate":"2026-10-27T08:01:30Z"}"""
    }
}
