package com.circaso.integrations.impl.payhero

import com.circaso.integrations.api.CollectionNotifications
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.KeyedWebhookVendor
import com.circaso.integrations.api.WebhookVendor
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * PayHero's callback at `/v1/hooks/payhero/{key}`: each ramp merchant has a callback address of their own, whose key
 * is the only secret a provider that signs nothing allows (decision 2026-11-22). **PayHero signs nothing**, so this
 * callback is a doorbell and never a fact: exactly one value is taken from the body, our own reference, and handed
 * with the address's key to [notifications], which asks PayHero itself what happened before anything moves. A forged
 * callback can at most cause one status query for an order that is really waiting on that merchant.
 *
 * A callback on the plain `/v1/hooks/payhero` (no key) is refused. [notifications] is looked up on each delivery:
 * the ramp module binds it and integrations never depend on it.
 */
class PayHeroWebhook(private val notifications: () -> CollectionNotifications?) : KeyedWebhookVendor {
    private val log = LoggerFactory.getLogger(PayHeroWebhook::class.java)

    override val name: String = NAME

    override fun keyed(key: String): WebhookVendor = Keyed(key)

    /** There is nothing to verify: see the class comment. What the body says is never trusted. */
    override suspend fun verifySignature(headers: Map<String, String>, body: ByteArray): Boolean = true

    override fun sentAt(headers: Map<String, String>, body: ByteArray): Instant? = null

    override fun deliveryId(headers: Map<String, String>, body: ByteArray): String? =
        decode(body)?.let { result -> result.checkoutRequestId.ifEmpty { result.externalReference } }?.ifEmpty { null }

    override suspend fun translate(body: ByteArray): Resource<Unit> =
        Resource.error(IntegrationError.Rejected("The payment callback came on an address without a key."))

    private suspend fun ring(key: String, body: ByteArray): Resource<Unit> {
        val reference =
            decode(body)?.externalReference?.takeIf { it.isNotEmpty() && it.length <= MAX_REFERENCE }
                ?: return Resource.error(IntegrationError.Rejected("The payment callback names no payment."))
        val listener =
            notifications() ?: return Resource.error(IntegrationError.Unavailable("Collection notifications")).also {
                log.warn("payhero callback not delivered: nothing listens for collection notifications")
            }
        return listener.onPaymentCallback(key, reference)
    }

    private fun decode(body: ByteArray): CallbackResult? =
        runCatching {
            payHeroJson.decodeFromString(CallbackBody.serializer(), body.decodeToString()).response
        }.getOrNull()

    /** One delivery on a keyed address: this vendor's guards, with the key carried into the translation. */
    private inner class Keyed(private val key: String) : WebhookVendor {
        override val name: String = NAME

        override suspend fun verifySignature(headers: Map<String, String>, body: ByteArray): Boolean = true

        override fun sentAt(headers: Map<String, String>, body: ByteArray): Instant? = null

        override fun deliveryId(headers: Map<String, String>, body: ByteArray): String? =
            this@PayHeroWebhook.deliveryId(headers, body)?.let { "$key:$it" }

        override suspend fun translate(body: ByteArray): Resource<Unit> = ring(key, body)
    }

    private companion object {
        const val NAME = "payhero"
        const val MAX_REFERENCE = 64
    }
}
