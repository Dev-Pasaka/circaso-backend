package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The settings names and defaults (architecture.md → Pools; decisions 2026-10-27). */
class PostgresSettingsTest {
    private fun settings(env: Map<String, String>) = PostgresSettings(EnvConfig(env), EnvSecrets(env))

    @Test
    fun `requests fail fast and background work waits out a burst`() {
        val defaults = settings(emptyMap())

        assertEquals(Duration.ofSeconds(3), defaults.app.maxAcquireTime)
        assertEquals(Duration.ofSeconds(20), defaults.jobs.maxAcquireTime)
        val tuned =
            settings(
                mapOf("DATABASE_POOL_ACQUIRE_TIMEOUT_MS" to "500", "DATABASE_POOL_JOBS_ACQUIRE_TIMEOUT_MS" to "9000"),
            )
        assertEquals(Duration.ofMillis(500), tuned.app.maxAcquireTime)
        assertEquals(Duration.ofSeconds(9), tuned.jobs.maxAcquireTime)
    }

    @Test
    fun `TLS is verified outside local, and switched off only where it is configured so`() {
        assertEquals("disable", settings(mapOf("CIRCASO_ENV" to "local")).sslMode)
        assertEquals("verify-full", settings(mapOf("CIRCASO_ENV" to "staging")).sslMode)
        assertEquals("disable", settings(mapOf("CIRCASO_ENV" to "staging", "DATABASE_SSL_MODE" to "disable")).sslMode)
        assertNull(settings(emptyMap()).sslRootCert)
        assertEquals("/ca.pem", settings(mapOf("DATABASE_SSL_ROOT_CERT" to "/ca.pem")).sslRootCert)
    }

    @Test
    fun `the app talks R2DBC and Migrate talks JDBC, as the right logins`() {
        val defaults = settings(emptyMap())

        assertEquals("r2dbc:postgresql://localhost:5432/circaso", defaults.url)
        assertEquals("jdbc:postgresql://localhost:5432/circaso", defaults.migration.url)
        assertEquals(
            listOf("circaso_app", "circaso_jobs", "circaso_migrator"),
            listOf(defaults.app.user, defaults.jobs.user, defaults.migration.user),
        )
    }
}
