package com.circaso.integrations.impl.apns

import com.circaso.integrations.api.VoipPushGateway
import com.circaso.integrations.api.VoipPushResult
import com.circaso.integrations.api.VoipRing
import com.circaso.integrations.api.VoipRingEnded
import com.circaso.shared.CircasoJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Collections

/** What the iPhone's PushKit delegate reads: the FCM `call.ringing` push's fields, with no `aps` alert. */
@Serializable
private data class ApnsVoipPayload(
    val kind: String,
    val callId: String,
    val chatId: String,
    val callKind: String,
    val callerName: String,
)

/** The ring stopped (2026-11-09): `kind` `call.ended`, so the PushKit delegate ends that CallKit call. */
@Serializable
private data class ApnsVoipEndedPayload(
    val kind: String,
    val callId: String,
    val chatId: String,
    val reason: String,
)

/** APNs' error body: only the reason is read (`BadDeviceToken`, `Unregistered`, `ExpiredProviderToken`, …). */
@Serializable
private data class ApnsError(val reason: String? = null)

/**
 * Rings an iPhone through an APNs VoIP push (decision 2026-11-08): `POST /3/device/{token}` with `apns-push-type:
 * voip`, the topic `<bundle id>.voip`, priority 10 and expiration 0 (a ring is delivered now or never; the phone gets
 * `call.missed` through FCM after). [tokens] signs the provider token; [transport] is the HTTP/2 connection.
 *
 * Development and production tokens (2026-11-11): an iPhone running an Xcode build has a sandbox token, one from
 * TestFlight or the App Store a production token, and each APNs host answers `BadDeviceToken` to the other kind. So a
 * `BadDeviceToken` from [transport] is tried once on [otherHost] (the other environment) before the token counts as
 * invalid, and the host that took it is remembered for that token. One server then rings both kinds of iPhone, and a
 * sandbox token is no longer forgotten after its first ring.
 *
 * Each send logs one line: the call, the outcome, APNs' status and reason, the host when it was [otherHost], and the
 * token's last 6 characters only.
 */
class ApnsVoipPush(
    private val tokens: ApnsProviderToken,
    private val transport: ApnsTransport,
    bundleId: String,
    private val otherHost: ApnsTransport? = null,
) : VoipPushGateway {
    private val log = LoggerFactory.getLogger(ApnsVoipPush::class.java)
    private val topic = "$bundleId.voip"

    override val enabled: Boolean = true

    /** Tokens that [otherHost] took, most recent last; capped so it can't grow without bound. */
    private val onOtherHost: MutableSet<String> =
        Collections.synchronizedSet(
            Collections.newSetFromMap(
                object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
                        size > REMEMBERED_TOKENS
                },
            ),
        )

    override suspend fun ring(voipToken: String, ring: VoipRing): VoipPushResult =
        send(voipToken, "ring", ring.callId, payload(ring))

    override suspend fun ended(voipToken: String, ended: VoipRingEnded): VoipPushResult =
        send(
            voipToken,
            "ended",
            ended.callId,
            CircasoJson.encodeToString(
                ApnsVoipEndedPayload.serializer(),
                ApnsVoipEndedPayload(ENDED, ended.callId, ended.chatId, ended.reason),
            ),
        )

    /** One VoIP push; [what] (`ring`, `ended`) names it in the log line. */
    private suspend fun send(
        voipToken: String,
        what: String,
        callId: String,
        body: String,
    ): VoipPushResult {
        val tail = voipToken.takeLast(TOKEN_TAIL)
        if (!HEX.matches(voipToken)) {
            log.warn(
                "apns voip {}: callId={} outcome={} reason=not-hex token=…{}",
                what,
                callId,
                VoipPushResult.TOKEN_INVALID,
                tail,
            )
            return VoipPushResult.TOKEN_INVALID
        }
        val first = if (otherHost != null && voipToken in onOtherHost) otherHost else transport
        var (response, failure) = post(first, voipToken, body)
        var used = first
        val second = (if (first === transport) otherHost else transport)?.takeIf { response.isBadDeviceToken() }
        if (second != null) {
            // The other host decides. If it can't be reached, the ring failed but the token isn't called invalid.
            val (retried, retryFailure) = post(second, voipToken, body)
            response = retried
            failure = retryFailure
            used = second
            if (retried?.status == OK) {
                if (second === otherHost) onOtherHost += voipToken else onOtherHost -= voipToken
            }
        }
        if (failure != null || response == null) {
            log.warn(
                "apns voip {}: callId={} outcome={} error={} token=…{}",
                what,
                callId,
                VoipPushResult.FAILED,
                failure?.let { it::class.simpleName },
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
        val host = if (used === otherHost) "other" else "main"
        val line = "apns voip {}: callId={} outcome={} status={} reason={} host={} apnsId={} token=…{}"
        val args = arrayOf<Any?>(what, callId, outcome, response.status, reason, host, response.apnsId, tail)
        if (outcome == VoipPushResult.FAILED) log.warn(line, *args) else log.info(line, *args)
        return outcome
    }

    /** One POST to [to]; the response, or the exception that stopped it. */
    private suspend fun post(
        to: ApnsTransport,
        voipToken: String,
        body: String,
    ): Pair<ApnsResponse?, Exception?> =
        try {
            to.post("/3/device/$voipToken", headers(), body) to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to e
        }

    private fun ApnsResponse?.isBadDeviceToken(): Boolean =
        this != null && status == BAD_REQUEST && reason(this) in BAD_TOKEN

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
        const val ENDED = "call.ended"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val GONE = 410
        const val TOKEN_TAIL = 6
        const val REMEMBERED_TOKENS = 10_000
        val HEX = Regex("^[0-9a-fA-F]{16,200}$")
        val BAD_TOKEN = setOf("BadDeviceToken")
        val STALE_PROVIDER_TOKEN = setOf("ExpiredProviderToken", "InvalidProviderToken")
    }
}
