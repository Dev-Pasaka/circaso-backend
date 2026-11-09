package com.circaso.integrations.impl.apns

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushPlatform
import com.circaso.integrations.api.PushReceipt
import com.circaso.integrations.api.PushTarget
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock

/** APNs' error body: only the reason is read (`BadDeviceToken`, `Unregistered`, `ExpiredProviderToken`, …). */
@Serializable
private data class ApnsAlertError(val reason: String? = null)

/**
 * Every push to an iPhone except a ring (decision 2026-11-09): the app has no Firebase SDK, so it registers its own
 * APNs device token and the server posts to APNs itself (`POST /3/device/{token}`, topic = the bundle id).
 *
 * - A normal push is an alert the phone shows as it is.
 * - A [PushMessage.dataOnly] push (a chat message, money) is an alert with `mutable-content`, so Circaso's Notification
 *   Service Extension can open the message on the phone and replace the words; [PushMessage.title] and
 *   [PushMessage.body] are the fallback it shows if it can't. A chat's pushes share a `thread-id` (the chat), so iOS
 *   groups them into one conversation.
 * - A [PushMessage.silent] push is a background push (`content-available`, no alert, priority 5).
 *
 * The ids ride at the top level of the payload under the same names as FCM's `data`, all strings. A token APNs no
 * longer accepts is [IntegrationError.Rejected], so the caller can drop it. The token and the words are never logged:
 * one line per failure with APNs' status, reason and the token's last 6 characters.
 */
class ApnsAlertPush(
    private val tokens: ApnsProviderToken,
    private val transport: ApnsTransport,
    private val bundleId: String,
    private val clock: Clock = Clock.systemUTC(),
) : PushGateway {
    private val log = LoggerFactory.getLogger(ApnsAlertPush::class.java)

    override suspend fun send(target: PushTarget, message: PushMessage): Resource<PushReceipt> {
        val token = target.token
        val tail = token.takeLast(TOKEN_TAIL)
        if (!HEX.matches(token)) return Resource.error(IntegrationError.Rejected("Not an APNs device token."))
        val response =
            try {
                transport.post("/3/device/$token", headers(message), payload(message))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("apns push unreachable: error={} token=…{}", e::class.simpleName, tail)
                return Resource.error(IntegrationError.Unavailable("APNs"), e)
            }
        val reason = reason(response.body)
        return when {
            response.status == OK -> {
                Resource.success(PushReceipt(response.apnsId ?: "", "apns"))
            }

            response.status == GONE || (response.status == BAD_REQUEST && reason in BAD_TOKEN) -> {
                log.info("apns no longer accepts a token: status={} reason={} token=…{}", response.status, reason, tail)
                Resource.error(IntegrationError.Rejected("The device token is no longer valid."))
            }

            response.status == FORBIDDEN && reason in STALE_PROVIDER_TOKEN -> {
                tokens.expired()
                log.warn("apns refused our provider token: reason={}", reason)
                Resource.error(IntegrationError.Unavailable("APNs"))
            }

            response.status == TOO_MANY || response.status >= SERVER_ERROR -> {
                log.warn("apns unavailable: status={} reason={}", response.status, reason)
                Resource.error(IntegrationError.Unavailable("APNs"))
            }

            else -> {
                log.warn("apns refused the push: status={} reason={} token=…{}", response.status, reason, tail)
                Resource.error(IntegrationError.Rejected("APNs refused the push (status ${response.status})."))
            }
        }
    }

    private fun headers(message: PushMessage): Map<String, String> =
        buildMap {
            put("authorization", "bearer ${tokens.current()}")
            put("apns-topic", bundleId)
            put("apns-push-type", if (message.silent) "background" else "alert")
            put("apns-priority", if (message.silent) "5" else "10")
            message.ttlSeconds?.let { put("apns-expiration", (clock.instant().epochSecond + it).toString()) }
            put("content-type", "application/json")
        }

    internal fun payload(message: PushMessage): String {
        val ids = message.data
        val aps =
            if (message.silent) {
                ApnsAps(contentAvailable = 1)
            } else {
                ApnsAps(
                    alert = ApnsAlert(message.title, message.body),
                    sound = message.sound ?: "default",
                    mutableContent = if (message.dataOnly) 1 else null,
                    threadId = ids.chatId,
                    category = if (ids.messageId != null && ids.kind == null) CHAT_CATEGORY else null,
                )
            }
        val payload =
            ApnsAlertPayload(
                aps = aps,
                chatId = ids.chatId,
                messageId = ids.messageId,
                callId = ids.callId,
                kind = ids.kind,
                callerName = ids.callerName,
                callKind = ids.callKind,
                spaceId = ids.spaceId,
                transferId = ids.transferId,
                transactionId = ids.transactionId,
                walletId = ids.walletId,
                requestId = ids.requestId,
                userId = ids.userId,
                ticketId = ids.ticketId,
                senderId = ids.senderId,
                sentAt = ids.sentAt,
                messageKind = ids.messageKind,
                ciphertext = ids.ciphertext,
            )
        return ApnsJson.encodeToString(ApnsAlertPayload.serializer(), payload)
    }

    private fun reason(body: String): String? =
        if (body.isBlank()) {
            null
        } else {
            runCatching { CircasoJson.decodeFromString(ApnsAlertError.serializer(), body).reason }.getOrNull()
        }

    private companion object {
        /** The notification category the iPhone registers for chat messages (Reply, Mark as Read). */
        const val CHAT_CATEGORY = "message"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val GONE = 410
        const val TOO_MANY = 429
        const val SERVER_ERROR = 500
        const val TOKEN_TAIL = 6
        val HEX = Regex("^[0-9a-fA-F]{16,200}$")
        val BAD_TOKEN = setOf("BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered")
        val STALE_PROVIDER_TOKEN = setOf("ExpiredProviderToken", "InvalidProviderToken")
    }
}

/** Only the fields that are set: an iPhone reads the ids from `userInfo` under FCM's `data` names. */
private val ApnsJson =
    Json {
        explicitNulls = false
        encodeDefaults = true
    }

@Serializable
private data class ApnsAlert(val title: String, val body: String)

@Serializable
private data class ApnsAps(
    val alert: ApnsAlert? = null,
    val sound: String? = null,
    @SerialName("mutable-content") val mutableContent: Int? = null,
    @SerialName("content-available") val contentAvailable: Int? = null,
    @SerialName("thread-id") val threadId: String? = null,
    val category: String? = null,
)

/** `aps` and, at the top level, the ids that are set (all strings), as FCM's `data` names them. */
@Serializable
private data class ApnsAlertPayload(
    val aps: ApnsAps,
    val chatId: String? = null,
    val messageId: String? = null,
    val callId: String? = null,
    val kind: String? = null,
    val callerName: String? = null,
    val callKind: String? = null,
    val spaceId: String? = null,
    val transferId: String? = null,
    val transactionId: String? = null,
    val walletId: String? = null,
    val requestId: String? = null,
    val userId: String? = null,
    val ticketId: String? = null,
    val senderId: String? = null,
    val sentAt: String? = null,
    val messageKind: String? = null,
    val ciphertext: String? = null,
)

/**
 * Sends iPhone pushes through [ios] (APNs) and every other through [other] (FCM): the iPhone registers an APNs token
 * since 2026-11-09. Without APNs configured, [ios] is null and everything goes through [other] as before.
 */
class PlatformPushGateway(private val other: PushGateway, private val ios: PushGateway?) : PushGateway {
    override suspend fun send(target: PushTarget, message: PushMessage): Resource<PushReceipt> =
        if (target.platform == PushPlatform.IOS &&
            ios != null
        ) {
            ios.send(target, message)
        } else {
            other.send(target, message)
        }
}
