package com.circaso.integrations.impl.apns

import com.circaso.integrations.api.VoipPushGateway
import com.circaso.integrations.api.VoipPushResult
import com.circaso.integrations.api.VoipRing
import com.circaso.shared.CircasoJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** What the iPhone's PushKit delegate reads: the FCM `call.ringing` push's fields, with no `aps` alert. */
@Serializable
private data class ApnsVoipPayload(
    val kind: String,
    val callId: String,
    val chatId: String,
    val callKind: String,
    val callerName: String,
)

/** APNs' error body: only the reason is read (`BadDeviceToken`, `Unregistered`, `ExpiredProviderToken`, …). */
@Serializable
private data class ApnsError(val reason: String? = null)

/**
 * Rings an iPhone through an APNs VoIP push (decision 2026-11-08): `POST /3/device/{token}` with `apns-push-type:
 * voip`, the topic `<bundle id>.voip`, priority 10 and expiration 0 (a ring is delivered now or never; the phone gets
 * `call.missed` through FCM after). [tokens] signs the provider token; [transport] is the HTTP/2 connection.
 *
 * Each send logs one line: the call, the outcome, APNs' status and reason, and the token's last 6 characters only.
 */
class ApnsVoipPush(private val tokens: ApnsProviderToken, private val transport: ApnsTransport, bundleId: String) :
    VoipPushGateway {
    private val log = LoggerFactory.getLogger(ApnsVoipPush::class.java)
    private val topic = "$bundleId.voip"

    override val enabled: Boolean = true

    override suspend fun ring(voipToken: String, ring: VoipRing): VoipPushResult {
        val tail = voipToken.takeLast(TOKEN_TAIL)
        if (!HEX.matches(voipToken)) {
            log.warn(
                "apns voip ring: callId={} outcome={} reason=not-hex token=…{}",
                ring.callId,
                VoipPushResult.TOKEN_INVALID,
                tail,
            )
            return VoipPushResult.TOKEN_INVALID
        }
        val response =
            try {
                transport.post("/3/device/$voipToken", headers(), payload(ring))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(
                    "apns voip ring: callId={} outcome={} error={} token=…{}",
                    ring.callId,
                    VoipPushResult.FAILED,
                    e::class.simpleName,
                    tail,
                )
                return VoipPushResult.FAILED
            }
        val reason = reason(response)
        val outcome =
            when {
                response.status == OK -> {
                    VoipPushResult.SENT
                }

                response.status == GONE || (response.status == BAD_REQUEST && reason in BAD_TOKEN) -> {
                    VoipPushResult.TOKEN_INVALID
                }

                response.status == FORBIDDEN && reason in STALE_PROVIDER_TOKEN -> {
                    tokens.expired()
                    VoipPushResult.FAILED
                }

                else -> {
                    VoipPushResult.FAILED
                }
            }
        val line = "apns voip ring: callId={} outcome={} status={} reason={} apnsId={} token=…{}"
        val args = arrayOf<Any?>(ring.callId, outcome, response.status, reason, response.apnsId, tail)
        if (outcome == VoipPushResult.FAILED) log.warn(line, *args) else log.info(line, *args)
        return outcome
    }

    private fun headers(): Map<String, String> =
        mapOf(
            "authorization" to "bearer ${tokens.current()}",
            "apns-push-type" to "voip",
            "apns-topic" to topic,
            "apns-priority" to "10",
            "apns-expiration" to "0",
            "content-type" to "application/json",
        )

    private fun payload(ring: VoipRing): String =
        CircasoJson.encodeToString(
            ApnsVoipPayload.serializer(),
            ApnsVoipPayload(RINGING, ring.callId, ring.chatId, ring.callKind, ring.callerName),
        )

    private fun reason(response: ApnsResponse): String? =
        if (response.body.isBlank()) {
            null
        } else {
            runCatching { CircasoJson.decodeFromString(ApnsError.serializer(), response.body).reason }.getOrNull()
        }

    private companion object {
        const val RINGING = "call.ringing"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val GONE = 410
        const val TOKEN_TAIL = 6
        val HEX = Regex("^[0-9a-fA-F]{16,200}$")
        val BAD_TOKEN = setOf("BadDeviceToken")
        val STALE_PROVIDER_TOKEN = setOf("ExpiredProviderToken", "InvalidProviderToken")
    }
}
