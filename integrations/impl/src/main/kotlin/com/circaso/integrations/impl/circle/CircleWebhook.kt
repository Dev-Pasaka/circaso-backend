package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyNotifications
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebhookVendor
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * Circle's notifications at `/v1/hooks/circle`. Each is signed with ECDSA over SHA-256 of the raw body: the signature
 * (base64 DER) in `X-Circle-Signature`, the key id in `X-Circle-Key-Id`. `transactions.inbound` and
 * `transactions.outbound` are handed to [notifications] in our words; every other type is acknowledged and ignored.
 *
 * The gateway waits for the translation ([answersAfterTranslation]): a failure answers non-200, so Circle delivers
 * again with the same `notificationId`, which is the delivery id for the duplicate check. No replay window: Circle
 * signs no send time, so the replay guard is the signature plus the `notificationId` duplicate check (decision
 * 2026-10-27).
 *
 * [notifications] is looked up on each delivery: the wallet module binds it and integrations never depend on it.
 */
class CircleWebhook internal constructor(
    client: CircleClient,
    settings: CircleSettings,
    clock: Clock = Clock.systemUTC(),
    private val notifications: () -> CustodyNotifications?,
) : WebhookVendor {
    private val log = LoggerFactory.getLogger(CircleWebhook::class.java)
    private val keys = CircleNotificationKeys(client, clock)
    private val transactions = CircleTransactions(CircleTokens(client), settings)

    override val name: String = "circle"

    override val answersAfterTranslation: Boolean = true

    override suspend fun verifySignature(headers: Map<String, String>, body: ByteArray): Boolean {
        val keyId = headers[KEY_ID_HEADER] ?: return false
        val signature =
            headers[SIGNATURE_HEADER]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                ?: return false
        val key = keys.get(keyId) ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(body)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    override fun sentAt(headers: Map<String, String>, body: ByteArray): Instant? = null

    override fun deliveryId(headers: Map<String, String>, body: ByteArray): String? =
        decode<NotificationHeader>(body)?.notificationId?.takeIf { it.isNotEmpty() }

    override suspend fun translate(body: ByteArray): Resource<Unit> {
        val header = decode<NotificationHeader>(body) ?: return unreadable()
        if (header.notificationType !in TRANSACTION_TYPES) return Resource.success(Unit)

        val transaction = decode<TransactionNotification>(body)?.notification ?: return unreadable()
        val update =
            when (val translated = transactions.translate(transaction)) {
                is Resource.Error -> return translated
                is Resource.Success -> translated.data ?: return Resource.success(Unit)
            }
        val listener =
            notifications() ?: return Resource.error(IntegrationError.Unavailable("Custody notifications")).also {
                log.warn("circle notification not delivered: nothing listens for custody notifications")
            }
        return listener.onTransaction(update)
    }

    private inline fun <reified T> decode(body: ByteArray): T? =
        runCatching { circleJson.decodeFromString<T>(body.decodeToString()) }.getOrNull()

    private fun unreadable(): Resource<Unit> =
        Resource.error(IntegrationError.Rejected("Circle sent a notification we can't read."))

    private companion object {
        const val KEY_ID_HEADER = "x-circle-key-id"
        const val SIGNATURE_HEADER = "x-circle-signature"
        val TRANSACTION_TYPES = setOf("transactions.inbound", "transactions.outbound")
    }
}
