package com.circaso.integrations.impl.payhero

import com.circaso.integrations.api.CollectionNotifications
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayHeroWebhookTest {
    private val rung = CopyOnWriteArrayList<Pair<String, String>>()
    private val payoutsRung = CopyOnWriteArrayList<Pair<String, String>>()
    private var listener: CollectionNotifications? =
        object : CollectionNotifications {
            override suspend fun onPaymentCallback(callbackKey: String, reference: String): Resource<Unit> {
                rung += callbackKey to reference
                return Resource.success(Unit)
            }

            override suspend fun onPayoutCallback(callbackKey: String, reference: String): Resource<Unit> {
                payoutsRung += callbackKey to reference
                return Resource.success(Unit)
            }
        }
    private val webhook = PayHeroWebhook { listener }
    private val keyed = webhook.keyed("crynce-9fQ2xLr7Tk3mVb8s")

    private fun callback(reference: String = "order-1", status: String = "Success") =
        """{"forward_url":"","response":{"Amount":1300,"CheckoutRequestID":"ws_CO_1",""" +
            """"ExternalReference":"$reference",""" +
            """"MerchantRequestID":"m-1","MpesaReceiptNumber":"SKL4XYZ123","Phone":"+254712345678","ResultCode":0,""" +
            """"ResultDesc":"ok","Status":"$status"},"status":true}"""

    @Test
    fun `a callback only rings the bell with the address's key and our own reference, whatever it claims`() =
        runBlocking<Unit> {
            assertIs<Resource.Success<Unit>>(keyed.translate(callback(status = "Success").toByteArray()))
            assertIs<Resource.Success<Unit>>(keyed.translate(callback(status = "Failed").toByteArray()))

            assertEquals(List(2) { "crynce-9fQ2xLr7Tk3mVb8s" to "order-1" }, rung.toList())
        }

    @Test
    fun `there is no signature to check, and the delivery id is the key and the checkout id`() =
        runBlocking<Unit> {
            val body = callback().toByteArray()

            assertTrue(keyed.verifySignature(emptyMap(), body), "PayHero signs nothing: the body is never trusted")
            assertNull(keyed.sentAt(emptyMap(), body))
            assertEquals("crynce-9fQ2xLr7Tk3mVb8s:ws_CO_1", keyed.deliveryId(emptyMap(), body))
            assertEquals("payhero", keyed.name)
        }

    @Test
    fun `a callback on the address without a key is refused and rings nothing`() =
        runBlocking<Unit> {
            assertIs<IntegrationError.Rejected>((webhook.translate(callback().toByteArray()) as Resource.Error).error)
            assertTrue(rung.isEmpty())
        }

    @Test
    fun `a body that names no payment, or isn't JSON, is refused and rings nothing`() =
        runBlocking<Unit> {
            assertIs<IntegrationError.Rejected>((keyed.translate("{}".toByteArray()) as Resource.Error).error)
            assertIs<IntegrationError.Rejected>((keyed.translate("not json".toByteArray()) as Resource.Error).error)
            assertIs<IntegrationError.Rejected>(
                (keyed.translate(callback(reference = "x".repeat(65)).toByteArray()) as Resource.Error).error,
            )
            assertTrue(rung.isEmpty())
            assertNull(keyed.deliveryId(emptyMap(), "not json".toByteArray()))
        }

    @Test
    fun `with nothing listening the callback is not acknowledged as handled`() =
        runBlocking<Unit> {
            listener = null

            assertIs<IntegrationError.Unavailable>((keyed.translate(callback().toByteArray()) as Resource.Error).error)
        }

    /** FinoQ's flat layout (docs.payhero.africa), as its three documented examples. */
    private fun flat(
        type: String?,
        status: String = "success",
        reference: String = "order-9",
        transactionId: String = "txn_d0affc67",
    ) = """{"success":${status == "success"},"status":"$status","message":"Transaction processed successfully.",""" +
        """"reference":"UFD004139536.eO","external_reference":"$reference","amount":10,"currency":"KES",""" +
        """"transaction_id":"$transactionId","transaction_date":"2026-06-30T00:41:39Z",""" +
        (type?.let { """"transaction_type":"$it",""" } ?: "") +
        """"provider_reference":"UFUSB1OXWV","provider":"sasapay","callback_urls":null}"""

    @Test
    fun `the flat layout says which it is - a pay-out rings the payout bell and a pay-in the payment bell`() =
        runBlocking<Unit> {
            assertIs<Resource.Success<Unit>>(keyed.translate(flat("outbound_payment").toByteArray()))
            assertEquals(listOf("crynce-9fQ2xLr7Tk3mVb8s" to "order-9"), payoutsRung.toList())
            assertTrue(rung.isEmpty(), "a payout never rings the payment bell")

            assertIs<Resource.Success<Unit>>(
                keyed.translate(flat("inbound_payment", reference = "order-1").toByteArray()),
            )
            assertEquals(listOf("crynce-9fQ2xLr7Tk3mVb8s" to "order-1"), rung.toList())
            assertEquals(1, payoutsRung.size)
        }

    @Test
    fun `a failed payout rings the same bell - what the callback claims is never the outcome`() =
        runBlocking<Unit> {
            keyed.translate(flat("outbound_payment", status = "failed").toByteArray())
            keyed.translate(flat("outbound_payment", status = "success").toByteArray())

            assertEquals(List(2) { "crynce-9fQ2xLr7Tk3mVb8s" to "order-9" }, payoutsRung.toList())
        }

    @Test
    fun `a layout that doesn't say which it is rings both bells`() =
        runBlocking<Unit> {
            assertIs<Resource.Success<Unit>>(keyed.translate(callback(reference = "order-9").toByteArray()))
            assertIs<Resource.Success<Unit>>(keyed.translate(flat(type = null, reference = "order-8").toByteArray()))

            val both = listOf("crynce-9fQ2xLr7Tk3mVb8s" to "order-9", "crynce-9fQ2xLr7Tk3mVb8s" to "order-8")
            assertEquals(both, rung.toList())
            assertEquals(both, payoutsRung.toList(), "the old nested layout carries no type; neither does this one")
        }

    @Test
    fun `a listener that only takes payments still handles a callback of unknown kind, and refuses a payout`() =
        runBlocking<Unit> {
            listener =
                object : CollectionNotifications {
                    override suspend fun onPaymentCallback(callbackKey: String, reference: String) =
                        Resource.success(Unit)
                }

            assertIs<Resource.Success<Unit>>(keyed.translate(callback().toByteArray()))
            assertIs<IntegrationError.NotImplemented>(
                (keyed.translate(flat("outbound_payment").toByteArray()) as Resource.Error).error,
            )
        }

    @Test
    fun `the flat layout's delivery id is its transaction and what it says, so a later word isn't a repeat`() {
        val queued = flat("outbound_payment", status = "failed").toByteArray()
        val paid = flat("outbound_payment", status = "success").toByteArray()
        val noTransaction = flat("inbound_payment", transactionId = "").toByteArray()

        assertEquals("crynce-9fQ2xLr7Tk3mVb8s:txn_d0affc67:FAILED", keyed.deliveryId(emptyMap(), queued))
        assertEquals("crynce-9fQ2xLr7Tk3mVb8s:txn_d0affc67:SUCCESS", keyed.deliveryId(emptyMap(), paid))
        assertEquals("crynce-9fQ2xLr7Tk3mVb8s:UFD004139536.eO:SUCCESS", keyed.deliveryId(emptyMap(), noTransaction))
    }

    @Test
    fun `a layout nobody has seen rings nothing and never throws`() =
        runBlocking<Unit> {
            val unknown =
                listOf(
                    """{"Body":{"stkCallback":{"ResultCode":0,"CallbackMetadata":{"Item":[{"Name":"Amount"}]}}}}""",
                    """{"reference":"UFD004139536.eO","status":"success"}""",
                    """{"external_reference":{"id":"order-9"}}""",
                    """[1,2,3]""",
                    "",
                )

            unknown.forEach { body ->
                assertIs<IntegrationError.Rejected>((keyed.translate(body.toByteArray()) as Resource.Error).error, body)
            }
            assertTrue(rung.isEmpty() && payoutsRung.isEmpty())
        }
}
