package com.circaso.integrations.impl.didit

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycNotifications
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Didit's webhook: the signature against a body signed with a test secret (the reference vectors in [DiditSamples],
 * and bodies this test signs itself), the timestamp window, the environment's own secret, and that a verified
 * delivery only rings the bell. Nothing here calls Didit.
 */
class DiditWebhookTest {
    private val rung = CopyOnWriteArrayList<Pair<KycEnvironment, String>>()
    private var listener: KycNotifications? =
        object : KycNotifications {
            override suspend fun onStatusChanged(environment: KycEnvironment, sessionId: String): Resource<Unit> {
                rung += environment to sessionId
                return answer
            }
        }
    private var answer: Resource<Unit> = Resource.success(Unit)
    private var now = Instant.ofEpochSecond(DiditSamples.SENT_AT + 2)
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }
    private val liveSecret = "live-webhook-secret"
    private val webhook =
        DiditWebhook(
            secrets = mapOf(KycEnvironment.SANDBOX to DiditSamples.SECRET, KycEnvironment.LIVE to liveSecret),
            clock = clock,
            notifications = { listener },
        )
    private val raw = DiditSamples.RAW.toByteArray()

    private fun headers(vararg pairs: Pair<String, String>) =
        mapOf("x-timestamp" to DiditSamples.SENT_AT.toString()) + pairs

    /** A delivery as Didit would send it now, for [environment], about [sessionId]. */
    private fun delivery(
        environment: String = "sandbox",
        sessionId: String = DiditSamples.SESSION_ID,
        type: String = "status.updated",
        timestamp: Long = DiditSamples.SENT_AT,
        extra: String = "",
    ) = (
        """{"environment":"$environment","event_id":"evt-1","session_id":"$sessionId","status":"Approved",""" +
            """"timestamp":$timestamp,"webhook_type":"$type"$extra}"""
    ).toByteArray()

    private fun v2(body: ByteArray, secret: String) =
        DiditWebhook.hmacHex(secret, DiditCanonicalJson.of(body.decodeToString())!!.toByteArray())

    @Test
    fun `X-Signature-V2 over the canonical JSON verifies, against Didit's own recipe`() =
        runBlocking<Unit> {
            assertTrue(webhook.verifySignature(headers("x-signature-v2" to DiditSamples.SIGNATURE_V2), raw))
            assertEquals(Instant.ofEpochSecond(DiditSamples.SENT_AT), webhook.sentAt(headers(), raw))
            assertEquals(DiditSamples.EVENT_ID, webhook.deliveryId(headers(), raw), "the same on every retry")
            assertEquals("didit", webhook.name)
            assertTrue(webhook.answersAfterTranslation, "a failure answers non-2xx, so Didit delivers again")
        }

    @Test
    fun `V2 still verifies when a proxy re-encoded the body, which the raw signature does not survive`() =
        runBlocking<Unit> {
            val reencoded = DiditSamples.CANONICAL.replace(",", ", ").toByteArray()
            assertEquals(DiditSamples.CANONICAL, DiditCanonicalJson.of(reencoded.decodeToString()))

            assertTrue(webhook.verifySignature(headers("x-signature-v2" to DiditSamples.SIGNATURE_V2), reencoded))
            assertFalse(webhook.verifySignature(headers("x-signature" to DiditSamples.SIGNATURE_RAW), reencoded))
        }

    @Test
    fun `X-Signature over the raw bytes is the fallback when V2 is missing or wrong`() =
        runBlocking<Unit> {
            assertTrue(webhook.verifySignature(headers("x-signature" to DiditSamples.SIGNATURE_RAW), raw))
            assertTrue(
                webhook.verifySignature(
                    headers("x-signature-v2" to "0".repeat(64), "x-signature" to DiditSamples.SIGNATURE_RAW),
                    raw,
                ),
            )
            assertTrue(
                webhook.verifySignature(headers("x-signature" to DiditSamples.SIGNATURE_RAW.uppercase()), raw),
                "hex case doesn't matter",
            )
        }

    @Test
    fun `a wrong, missing or simple signature is refused, and so is a body changed after it was signed`() =
        runBlocking<Unit> {
            assertFalse(webhook.verifySignature(headers(), raw), "no signature at all")
            assertFalse(webhook.verifySignature(headers("x-signature-v2" to "0".repeat(64)), raw))
            assertFalse(webhook.verifySignature(headers("x-signature" to "not hex"), raw))

            // X-Signature-Simple covers four fields and not the body: never accepted, even when it is right.
            val simple = "${DiditSamples.SENT_AT}:${DiditSamples.SESSION_ID}:Approved:status.updated"
            val simpleSignature = DiditWebhook.hmacHex(DiditSamples.SECRET, simple.toByteArray())
            assertFalse(webhook.verifySignature(headers("x-signature-simple" to simpleSignature), raw))

            val tampered = DiditSamples.RAW.replace("\"status\":\"Approved\"", "\"status\":\"Declined\"").toByteArray()
            assertFalse(webhook.verifySignature(headers("x-signature-v2" to DiditSamples.SIGNATURE_V2), tampered))
            assertFalse(webhook.verifySignature(headers("x-signature" to DiditSamples.SIGNATURE_RAW), tampered))
            assertFalse(webhook.verifySignature(headers("x-signature-v2" to "x"), "not json".toByteArray()))
        }

    @Test
    fun `each environment is verified with its own secret - one environment's secret can't speak for the other`() =
        runBlocking<Unit> {
            val live = delivery(environment = "live")
            assertTrue(webhook.verifySignature(headers("x-signature-v2" to v2(live, liveSecret)), live))
            assertFalse(
                webhook.verifySignature(headers("x-signature-v2" to v2(live, DiditSamples.SECRET)), live),
                "signed with the sandbox secret, claiming to be live",
            )
            val sandbox = delivery(environment = "sandbox")
            assertFalse(webhook.verifySignature(headers("x-signature-v2" to v2(sandbox, liveSecret)), sandbox))

            val unknown = delivery(environment = "staging")
            assertFalse(webhook.verifySignature(headers("x-signature-v2" to v2(unknown, liveSecret)), unknown))
        }

    @Test
    fun `an environment with no secret on this server refuses every delivery`() =
        runBlocking<Unit> {
            val sandboxOnly = DiditWebhook(mapOf(KycEnvironment.SANDBOX to DiditSamples.SECRET), clock) { listener }
            val live = delivery(environment = "live")

            assertFalse(sandboxOnly.verifySignature(headers("x-signature-v2" to v2(live, liveSecret)), live))
            assertTrue(sandboxOnly.verifySignature(headers("x-signature-v2" to DiditSamples.SIGNATURE_V2), raw))
        }

    @Test
    fun `a delivery older or newer than 300 seconds is refused, by the timestamp inside the signed body`() =
        runBlocking<Unit> {
            val signed = headers("x-signature-v2" to DiditSamples.SIGNATURE_V2)

            now = Instant.ofEpochSecond(DiditSamples.SENT_AT + 300)
            assertTrue(webhook.verifySignature(signed, raw), "300 seconds is still inside the window")
            now = Instant.ofEpochSecond(DiditSamples.SENT_AT + 301)
            assertFalse(webhook.verifySignature(signed, raw), "a replayed delivery")
            now = Instant.ofEpochSecond(DiditSamples.SENT_AT - 301)
            assertFalse(webhook.verifySignature(signed, raw), "a delivery from the future")

            // A fresh header can't freshen an old body: the body's own timestamp is the one that was signed.
            now = Instant.ofEpochSecond(DiditSamples.SENT_AT + 3_600)
            val fresh =
                mapOf("x-timestamp" to now.epochSecond.toString(), "x-signature-v2" to DiditSamples.SIGNATURE_V2)
            assertFalse(webhook.verifySignature(fresh, raw))

            // And a stale header is refused even on a body that is recent.
            now = Instant.ofEpochSecond(DiditSamples.SENT_AT + 2)
            val stale = mapOf("x-timestamp" to "1700000000", "x-signature-v2" to DiditSamples.SIGNATURE_V2)
            assertFalse(webhook.verifySignature(stale, raw))

            val untimed =
                """{"environment":"sandbox","event_id":"e","session_id":"s","webhook_type":"status.updated"}"""
            assertFalse(
                webhook.verifySignature(
                    headers("x-signature-v2" to v2(untimed.toByteArray(), DiditSamples.SECRET)),
                    untimed.toByteArray(),
                ),
                "no timestamp in the body",
            )
        }

    @Test
    fun `during a secret rotation the previous secret's signature is accepted too`() =
        runBlocking<Unit> {
            // Didit has rotated: the regular header is made with a secret we don't hold yet, `-Previous` with ours.
            val rotated =
                headers(
                    "x-signature-v2" to v2(raw, "the-new-secret"),
                    "x-signature-v2-previous" to DiditSamples.SIGNATURE_V2,
                )
            assertTrue(webhook.verifySignature(rotated, raw))

            val rotatedRaw =
                headers(
                    "x-signature" to DiditWebhook.hmacHex("the-new-secret", raw),
                    "x-signature-previous" to DiditSamples.SIGNATURE_RAW,
                )
            assertTrue(webhook.verifySignature(rotatedRaw, raw))
        }

    @Test
    fun `a verified delivery only rings the bell - the environment and the session id, never the decision`() =
        runBlocking<Unit> {
            assertIs<Resource.Success<Unit>>(webhook.translate(raw))
            assertIs<Resource.Success<Unit>>(webhook.translate(delivery(environment = "live", sessionId = "s-2")))
            assertIs<Resource.Success<Unit>>(webhook.translate(delivery(type = "data.updated", sessionId = "s-3")))

            assertEquals(
                listOf(
                    KycEnvironment.SANDBOX to DiditSamples.SESSION_ID,
                    KycEnvironment.LIVE to "s-2",
                    KycEnvironment.SANDBOX to "s-3",
                ),
                rung.toList(),
            )
        }

    @Test
    fun `events that aren't a user session's are acknowledged and ring nothing`() =
        runBlocking<Unit> {
            listOf(
                delivery(type = "user.status.updated"),
                delivery(type = "transaction.created"),
                delivery(extra = ""","session_kind":"business""""),
                delivery(sessionId = ""),
            ).forEach { assertIs<Resource.Success<Unit>>(webhook.translate(it)) }

            assertTrue(rung.isEmpty())
        }

    @Test
    fun `a body that can't be read is refused, and has no delivery id or send time`() =
        runBlocking<Unit> {
            val junk = "not json".toByteArray()

            assertIs<IntegrationError.Rejected>((webhook.translate(junk) as Resource.Error).error)
            assertIs<IntegrationError.Rejected>(
                (webhook.translate(delivery(environment = "staging")) as Resource.Error).error,
            )
            assertNull(webhook.deliveryId(emptyMap(), junk))
            assertNull(webhook.sentAt(emptyMap(), junk))
            assertTrue(rung.isEmpty())
        }

    @Test
    fun `when the listener can't apply it, or nothing listens, the delivery is not acknowledged`() =
        runBlocking<Unit> {
            answer = Resource.error(IntegrationError.Unavailable("Didit"))
            assertIs<IntegrationError.Unavailable>((webhook.translate(raw) as Resource.Error).error)

            listener = null
            assertIs<IntegrationError.Unavailable>((webhook.translate(raw) as Resource.Error).error)
        }
}
