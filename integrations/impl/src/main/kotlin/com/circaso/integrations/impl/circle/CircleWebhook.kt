package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyNotifications
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebhookVendor
import com.circaso.shared.Resource
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
    keyFetchesPerMinute: Int = CircleNotificationKeys.FETCHES_PER_MINUTE,
    private val notifications: () -> CustodyNotifications?,
) : WebhookVendor {
    private val log = LoggerFactory.getLogger(CircleWebhook::class.java)
    private val keys = CircleNotificationKeys(client, clock, keyFetchesPerMinute)
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

        val transaction = decode<TransactionNotification>(body)?.notification ?: return unreadable(body, header)
        val update =
            when (val translated = transactions.translate(transaction)) {
                // A transaction we can't read is one we can't follow: Circle sending it again changes nothing, so it is
                // acknowledged, and its shape (field names, never values) logged so the next one can be read.
                is Resource.Error -> {
                    if (translated.error !is IntegrationError.Rejected) return translated
                    logShape(body, header)
                    return Resource.success(Unit)
                }

                is Resource.Success -> {
                    translated.data ?: return Resource.success(Unit)
                }
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
        Resource.error(IntegrationError.Rejected("The custody provider sent a notification that can't be read."))

    private fun unreadable(body: ByteArray, header: NotificationHeader): Resource<Unit> {
        logShape(body, header)
        return Resource.success(Unit)
    }

    /**
     * What an unreadable notification looked like: its type, the fields of its `notification` and which of the ones
     * we read were present. Names and presence only; values carry addresses, amounts and ids.
     */
    private fun logShape(body: ByteArray, header: NotificationHeader) {
        val shape = shapeOf(body)
        log.warn(
            "circle notification not followed: type={} fields={} present={}",
            header.notificationType,
            shape.fields,
            shape.present,
        )
    }

    internal companion object {
        const val KEY_ID_HEADER = "x-circle-key-id"
        const val SIGNATURE_HEADER = "x-circle-signature"
        val TRANSACTION_TYPES = setOf("transactions.inbound", "transactions.outbound")

        /** The fields [TransactionPayload] reads to follow a transaction. */
        private val FOLLOWED =
            listOf("id", "walletId", "transactionType", "operation", "tokenId", "refId", "custodyType", "txHash")

        /** Field names of the body's `notification` object, and which [FOLLOWED] ones are non-empty. */
        internal fun shapeOf(body: ByteArray): Shape {
            val notification =
                runCatching { circleJson.decodeFromString<NotificationFields>(body.decodeToString()) }
                    .getOrNull()
                    ?.notification
                    ?: return Shape(fields = "<no notification object>", present = "")
            val present =
                FOLLOWED.joinToString(",") { name ->
                    val value = notification[name]
                    val filled =
                        when (value) {
                            null, JsonNull -> "absent"
                            is JsonPrimitive -> if (value.content.isEmpty()) "empty" else "set"
                            else -> "set"
                        }
                    "$name:$filled"
                }
            return Shape(fields = notification.keys.sorted().joinToString(","), present = present)
        }
    }

    internal data class Shape(val fields: String, val present: String)
}
