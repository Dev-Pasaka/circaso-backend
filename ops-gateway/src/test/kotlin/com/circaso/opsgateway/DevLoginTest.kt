package com.circaso.opsgateway

import com.circaso.infrastructure.api.Environment
import com.circaso.opsgateway.auth.DevLogin
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevLoginTest {
    private val secret = "a-sandbox-only-secret-of-32-chars!"
    private val staffId = UUID.randomUUID()

    @Test
    fun `a token it issued verifies and names the staff member`() {
        val login = DevLogin(secret, Duration.ofHours(8))

        val claims = login.verify(login.issue(staffId, "support@circaso.com").token)

        assertNotNull(claims)
        assertEquals(staffId.toString(), claims.sub)
        assertEquals("support@circaso.com", claims.email)
    }

    @Test
    fun `a changed token, a token from another secret and an expired token are refused`() {
        val login = DevLogin(secret, Duration.ofHours(8))
        val token = login.issue(staffId, "support@circaso.com").token
        val past = Clock.fixed(Instant.now().minus(Duration.ofHours(9)), ZoneOffset.UTC)

        assertNull(login.verify(token.dropLast(2) + "xx"))
        assertNull(DevLogin("another-secret-also-thirty-two-chars", Duration.ofHours(8)).verify(token))
        assertNull(login.verify(DevLogin(secret, Duration.ofHours(8), past).issue(staffId, "s@c.com").token))
    }

    @Test
    fun `the secret is checked exactly`() {
        val login = DevLogin(secret, Duration.ofHours(8))

        assertTrue(login.secretMatches(secret))
        assertFalse(login.secretMatches(secret.dropLast(1)))
        assertFalse(login.secretMatches(""))
    }

    @Test
    fun `the dev login refuses to start in production`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                OpsGatewayConfig(
                    environment = Environment.PRODUCTION,
                    access = AccessSettings("circaso.cloudflareaccess.com", "aud"),
                    allowedOrigins = setOf("https://admin.circaso.com"),
                    devLoginSecret = secret,
                )
            }

        assertTrue(failure.message!!.contains("OPS_DEV_LOGIN_SECRET"))
    }

    @Test
    fun `production needs Cloudflare Access, and a dev login secret must be long`() {
        assertFailsWith<IllegalArgumentException> {
            OpsGatewayConfig(Environment.PRODUCTION, access = null, allowedOrigins = emptySet(), devLoginSecret = null)
        }
        assertFailsWith<IllegalArgumentException> {
            OpsGatewayConfig(Environment.STAGING, access = null, allowedOrigins = emptySet(), devLoginSecret = "short")
        }
        // the sandbox may run the dev login, with or without Access
        OpsGatewayConfig(Environment.STAGING, access = null, allowedOrigins = emptySet(), devLoginSecret = secret)
    }

    @Test
    fun `allowed origins must be bare origins`() {
        assertFailsWith<IllegalArgumentException> {
            OpsGatewayConfig(Environment.LOCAL, null, setOf("https://admin.circaso.com/app"), null)
        }
    }
}
