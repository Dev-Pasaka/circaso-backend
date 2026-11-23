package com.circaso.integrations.impl.payhero

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.integrations.api.CollectionNotifications
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The merchant's FinoQ webhook signing secret (2026-11-23). FinoQ documents no scheme, so: a recognised signature
 * header that doesn't match is refused, no header is accepted, no secret is as before, and header names (never values)
 * are logged once per layout.
 */
class PayHeroSignatureTest {
    private val key = "crynce-9fQ2xLr7Tk3mVb8s"
    private val secret = "whsec_Zr8Lq2Vx7Tn4Kp9W"
    private var secrets: Map<String, String> = mapOf(key to secret)
    private var secretFails = false
    private val listener =
        object : CollectionNotifications {
            override suspend fun onPaymentCallback(callbackKey: String, reference: String): Resource<Unit> =
                Resource.success(Unit)

            override suspend fun callbackSigningSecret(callbackKey: String): String? {
                check(!secretFails) { "database down" }
                return secrets[callbackKey]
            }
        }
    private val webhook = PayHeroWebhook { listener }
    private val keyed = webhook.keyed(key)
    private val body =
        """{"success":true,"status":"success","reference":"UFD1","external_reference":"order-1",""" +
            """"transaction_type":"outbound_payment","amount":10}"""

    private fun hmac(secret: String, body: String): ByteArray =
        Mac
            .getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            .doFinal(body.toByteArray())

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun verify(headers: Map<String, String>) =
        runBlocking { keyed.verifySignature(headers, body.toByteArray()) }

    private fun logged(block: () -> Unit): List<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(PayHeroWebhook::class.java) as Logger
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.toList()
    }

    @Test
    fun `a good signature is accepted in hex, with a sha256 prefix, in base64 and as a shared token`() {
        val mac = hmac(secret, body)

        assertTrue(verify(mapOf("x-signature" to hex(mac))))
        assertTrue(verify(mapOf("x-finoq-signature" to "sha256=" + hex(mac).uppercase())))
        assertTrue(verify(mapOf("x-webhook-signature" to Base64.getEncoder().encodeToString(mac))))
        assertTrue(verify(mapOf("signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(mac))))
        assertTrue(verify(mapOf("x-webhook-secret" to secret)))
    }

    @Test
    fun `a bad signature is refused and logged as a security event, without its value or the secret`() {
        val forged = hex(hmac("someone-elses-secret", body))

        val events = logged { assertFalse(verify(mapOf("x-signature" to forged))) }

        val refusal = events.single { it.formattedMessage.startsWith("security: payhero callback refused") }
        assertEquals("WARN", refusal.level.toString())
        assertTrue("header=x-signature" in refusal.formattedMessage)
        events.forEach {
            assertFalse(forged in it.formattedMessage, it.formattedMessage)
            assertFalse(secret in it.formattedMessage, it.formattedMessage)
        }
    }

    @Test
    fun `a signature over another body is refused`() {
        assertFalse(verify(mapOf("x-signature" to hex(hmac(secret, "$body ")))))
    }

    @Test
    fun `with a secret set and no signature header the callback is accepted and the absence logged`() {
        val events = logged { assertTrue(verify(mapOf("content-type" to "application/json"))) }

        assertTrue(events.any { "outcome=missing" in it.formattedMessage })
    }

    @Test
    fun `with no secret set it is as before, whatever the headers say`() {
        secrets = emptyMap()

        assertTrue(verify(mapOf("x-signature" to "not-a-signature")))
        assertTrue(verify(emptyMap()))
    }

    @Test
    fun `a secret that can't be read never fails the callback`() {
        secretFails = true

        assertTrue(verify(mapOf("x-signature" to "anything")))
    }

    @Test
    fun `header names are logged once per layout, names only and never values`() {
        val headers =
            mapOf(
                "content-type" to "application/json",
                "x-finoq-signature" to "deadbeefcafe",
                "user-agent" to "FinoQ/1",
            )

        val events =
            logged {
                verify(headers)
                verify(headers)
                runBlocking { webhook.verifySignature(mapOf("x-new" to "value-1"), body.toByteArray()) }
            }

        val lines = events.map { it.formattedMessage }.filter { it.startsWith("payhero callback headers first seen") }
        assertEquals(
            listOf(
                "payhero callback headers first seen: names=[content-type,user-agent,x-finoq-signature] " +
                    "signing=[x-finoq-signature]",
                "payhero callback headers first seen: names=[x-new] signing=none",
            ),
            lines,
        )
        events.forEach {
            listOf("deadbeefcafe", "FinoQ/1", "application/json", "value-1", secret).forEach { value ->
                assertFalse(value in it.formattedMessage, it.formattedMessage)
            }
        }
    }
}
