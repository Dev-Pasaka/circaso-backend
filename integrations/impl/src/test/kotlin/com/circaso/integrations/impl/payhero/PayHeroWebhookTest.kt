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
    private var listener: CollectionNotifications? =
        object : CollectionNotifications {
            override suspend fun onPaymentCallback(callbackKey: String, reference: String): Resource<Unit> {
                rung += callbackKey to reference
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
}
