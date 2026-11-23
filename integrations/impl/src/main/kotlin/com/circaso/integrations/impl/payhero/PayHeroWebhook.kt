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
 * **Payments and payouts share the address** (2026-11-23). PayHero documents two layouts and not which one a payout
 * sends, so both are read ([CallbackBody]). FinoQ's flat layout says which it is (`transaction_type`): a pay-in rings
 * the payment bell, a pay-out the payout bell. The old nested layout doesn't say, so it rings **both**: each listener
 * ignores a reference that isn't its own, and a bell decides nothing either way. What the callback claims happened
 * (`status`, `ResultCode`) is never read for the outcome.
 *
 * A layout that names no reference rings nothing and is refused here; the gateway has already answered `200` (this
 * vendor is acknowledged before it is translated), so PayHero is never given a 5xx for a body we don't know. Every
 * layout is logged once, as field names and value kinds only ([ShapeLog]), so the first real payout shows what it
 * sends.
 *
 * A callback on the plain `/v1/hooks/payhero` (no key) is refused. [notifications] is looked up on each delivery:
 * the ramp module binds it and integrations never depend on it.
 */
class PayHeroWebhook(private val notifications: () -> CollectionNotifications?) : KeyedWebhookVendor {
    private val log = LoggerFactory.getLogger(PayHeroWebhook::class.java)
    private val shapes = ShapeLog(log)

    override val name: String = NAME

    override fun keyed(key: String): WebhookVendor = Keyed(key)

    /** There is nothing to verify: see the class comment. What the body says is never trusted. */
    override suspend fun verifySignature(headers: Map<String, String>, body: ByteArray): Boolean = true

    override fun sentAt(headers: Map<String, String>, body: ByteArray): Instant? = null

    override fun deliveryId(headers: Map<String, String>, body: ByteArray): String? = decode(body)?.deliveryId

    override suspend fun translate(body: ByteArray): Resource<Unit> =
        Resource.error(IntegrationError.Rejected("The payment callback came on an address without a key."))

    private suspend fun ring(key: String, body: ByteArray): Resource<Unit> {
        shapes.note("callback", null, body.decodeToString())
        val callback = decode(body)
        val reference =
            callback?.ours?.takeIf { it.length <= MAX_REFERENCE }
                ?: return Resource.error(IntegrationError.Rejected("The payment callback names no payment."))
        val listener =
            notifications() ?: return Resource.error(IntegrationError.Unavailable("Collection notifications")).also {
                log.warn("payhero callback not delivered: nothing listens for collection notifications")
            }
        log.info("payhero callback: layout={} about={}", callback.layout, callback.about)
        return when (callback.about) {
            About.PAYMENT -> listener.onPaymentCallback(key, reference)
            About.PAYOUT -> listener.onPayoutCallback(key, reference)
            About.EITHER -> ringBoth(listener, key, reference)
        }
    }

    /** The layout doesn't say which it is: both bells, and it is handled if either listener took it. */
    private suspend fun ringBoth(
        listener: CollectionNotifications,
        key: String,
        reference: String,
    ): Resource<Unit> {
        val payment = listener.onPaymentCallback(key, reference)
        val payout = listener.onPayoutCallback(key, reference)
        return if (payment is Resource.Error && payout is Resource.Success) payout else payment
    }

    private fun decode(body: ByteArray): Callback? {
        val read =
            runCatching { payHeroJson.decodeFromString(CallbackBody.serializer(), body.decodeToString()) }.getOrNull()
                ?: return null
        return read.response?.let(::nested) ?: flat(read)
    }

    /** The old site's layout: no type, and the checkout id is the delivery's id. */
    private fun nested(result: CallbackResult): Callback =
        Callback(
            layout = "nested",
            ours = result.externalReference.ifEmpty { null },
            about = About.EITHER,
            deliveryId = result.checkoutRequestId.ifEmpty { result.externalReference }.ifEmpty { null },
        )

    /**
     * FinoQ's layout. Its `status` is part of the delivery's id (and of nothing else), so a later callback that says
     * something new about the same payout isn't dropped as a repeat of the first.
     */
    private fun flat(body: CallbackBody): Callback {
        val id = listOf(body.transactionId, body.reference, body.externalReference).firstOrNull { !it.isNullOrBlank() }
        val said = body.status?.let { PayHeroPayouts.word(it) }.orEmpty()
        return Callback(
            layout = "flat",
            ours = body.externalReference?.ifBlank { null },
            about =
                when (body.transactionType?.trim()?.lowercase()) {
                    INBOUND -> About.PAYMENT
                    OUTBOUND -> About.PAYOUT
                    else -> About.EITHER
                },
            deliveryId = id?.take(MAX_ID)?.let { if (said.isEmpty()) it else "$it:$said" },
        )
    }

    private enum class About { PAYMENT, PAYOUT, EITHER }

    private class Callback(
        val layout: String,
        val ours: String?,
        val about: About,
        val deliveryId: String?,
    )

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
        const val MAX_ID = 96
        const val INBOUND = "inbound_payment"
        const val OUTBOUND = "outbound_payment"
    }
}
