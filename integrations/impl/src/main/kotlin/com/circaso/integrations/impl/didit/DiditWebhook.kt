package com.circaso.integrations.impl.didit

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycNotifications
import com.circaso.integrations.api.WebhookVendor
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Didit's webhooks at `/v1/hooks/didit`, **one address for both Didit applications** (decision 2026-11-22). Each
 * delivery says which it is from (`environment`: `sandbox` or `live`) and is verified with that application's own
 * destination secret, so a delivery signed with one environment's secret can never speak for the other.
 *
 * **The signature** is HMAC-SHA256, hex. `X-Signature-V2` is checked first: it is over the body in Didit's canonical
 * form ([DiditCanonicalJson]) and survives a proxy that re-encodes the JSON. `X-Signature`, over the exact bytes, is
 * the fallback. `X-Signature-Simple` is never accepted: it covers four fields and not the body. For 24 hours after a
 * secret is rotated Didit also sends the same signatures made with the replaced secret (`…-Previous`); a match on
 * either is accepted, so a rotation needs no downtime. **The timestamp** is the one inside the signed body, refused
 * when it is more than 300 seconds from now, and so is an `X-Timestamp` header outside that window. **A repeat** is
 * told by `event_id`, the same on every retry.
 *
 * A verified delivery is still only a doorbell: just the session's id and the environment go to [notifications],
 * and the ramp reads the decision from Didit's API before it trusts an approval. The gateway waits for that
 * ([answersAfterTranslation]): when Didit can't be asked the answer is a 5xx, which Didit delivers again (twice, after
 * about one and four minutes).
 *
 * [secrets] holds the destination secret of each environment that has one; a delivery for an environment without
 * one fails its signature check. [notifications] is looked up on each delivery: the ramp module binds it and
 * integrations never depend on it.
 */
class DiditWebhook(
    private val secrets: Map<KycEnvironment, String>,
    private val clock: Clock = Clock.systemUTC(),
    private val notifications: () -> KycNotifications?,
) : WebhookVendor {
    private val log = LoggerFactory.getLogger(DiditWebhook::class.java)

    override val name: String = NAME

    override val answersAfterTranslation: Boolean = true

    override suspend fun verifySignature(headers: Map<String, String>, body: ByteArray): Boolean {
        val envelope = decode(body) ?: return false
        val environment = environmentOf(envelope.environment) ?: return false
        val secret =
            secrets[environment] ?: return false.also {
                log.warn("didit webhook refused: no webhook secret for environment={}", environment)
            }
        if (!isRecent(envelope.timestamp) || !headerIsRecent(headers[TIMESTAMP_HEADER])) return false
        return signedCanonically(headers, body, secret) || signedRaw(headers, body, secret)
    }

    /** The send time inside the signed body: a header alone could be rewritten on a replayed delivery. */
    override fun sentAt(headers: Map<String, String>, body: ByteArray): Instant? =
        decode(body)?.timestamp?.let(Instant::ofEpochSecond)

    override fun deliveryId(headers: Map<String, String>, body: ByteArray): String? =
        decode(body)?.eventId?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }

    override suspend fun translate(body: ByteArray): Resource<Unit> {
        val envelope = decode(body) ?: return unreadable()
        val environment = environmentOf(envelope.environment) ?: return unreadable()
        // Entity, transaction and business-session events are not the ramp's: acknowledged and ignored.
        val isSession = envelope.webhookType in SESSION_EVENTS && envelope.sessionKind != BUSINESS
        if (!isSession || envelope.sessionId.isEmpty() || envelope.sessionId.length > MAX_ID) {
            return Resource.success(Unit)
        }
        val listener =
            notifications() ?: return Resource.error(IntegrationError.Unavailable("KYC notifications")).also {
                log.warn("didit webhook not delivered: nothing listens for KYC notifications")
            }
        return listener.onStatusChanged(environment, envelope.sessionId)
    }

    private fun signedCanonically(
        headers: Map<String, String>,
        body: ByteArray,
        secret: String,
    ): Boolean {
        val given = listOfNotNull(headers[SIGNATURE_V2_HEADER], headers["$SIGNATURE_V2_HEADER$PREVIOUS"])
        if (given.isEmpty()) return false
        val canonical = DiditCanonicalJson.of(body.decodeToString()) ?: return false
        val expected = hmacHex(secret, canonical.toByteArray())
        return given.any { matches(expected, it) }
    }

    private fun signedRaw(
        headers: Map<String, String>,
        body: ByteArray,
        secret: String,
    ): Boolean {
        val given = listOfNotNull(headers[SIGNATURE_HEADER], headers["$SIGNATURE_HEADER$PREVIOUS"])
        if (given.isEmpty()) return false
        val expected = hmacHex(secret, body)
        return given.any { matches(expected, it) }
    }

    private fun isRecent(epochSeconds: Long?): Boolean {
        val sent = epochSeconds ?: return false
        return Duration.between(Instant.ofEpochSecond(sent), clock.instant()).abs() <= REPLAY_WINDOW
    }

    /** Didit always sends `X-Timestamp`; when it is there it must be recent too. */
    private fun headerIsRecent(header: String?): Boolean = header == null || isRecent(header.trim().toLongOrNull())

    private fun decode(body: ByteArray): WebhookEnvelope? =
        runCatching { diditJson.decodeFromString(WebhookEnvelope.serializer(), body.decodeToString()) }.getOrNull()

    private fun unreadable(): Resource<Unit> =
        Resource.error(IntegrationError.Rejected("The identity provider sent a notification that can't be read."))

    companion object {
        const val NAME = "didit"
        const val SIGNATURE_V2_HEADER = "x-signature-v2"
        const val SIGNATURE_HEADER = "x-signature"
        const val TIMESTAMP_HEADER = "x-timestamp"
        private const val PREVIOUS = "-previous"
        private const val BUSINESS = "business"
        private const val MAX_ID = 64
        private const val HMAC = "HmacSHA256"
        private val REPLAY_WINDOW: Duration = Duration.ofSeconds(300)

        /** A session's status changed, or a reviewer edited its data: both ring the same bell. */
        private val SESSION_EVENTS = setOf("status.updated", "data.updated")

        /** Didit names its two applications `sandbox` and `live`, exactly. */
        internal fun environmentOf(name: String): KycEnvironment? =
            when (name) {
                "sandbox" -> KycEnvironment.SANDBOX
                "live" -> KycEnvironment.LIVE
                else -> null
            }

        /** HMAC-SHA256 of [message] under [secret], as lower-case hex: how Didit writes its signature headers. */
        internal fun hmacHex(secret: String, message: ByteArray): String =
            Mac
                .getInstance(HMAC)
                .apply { init(SecretKeySpec(secret.toByteArray(), HMAC)) }
                .doFinal(message)
                .joinToString("") { "%02x".format(it) }

        /** Compares in constant time, so the answer's timing says nothing about how much of a guess was right. */
        private fun matches(expected: String, given: String): Boolean =
            MessageDigest.isEqual(expected.toByteArray(), given.trim().lowercase().toByteArray())
    }
}
