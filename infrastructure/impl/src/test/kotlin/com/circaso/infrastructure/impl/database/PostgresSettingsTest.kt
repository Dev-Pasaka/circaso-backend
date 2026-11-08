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
    fun `the pools open full and stay full, idle connections are kept, and every size can be tuned`() {
        val defaults = settings(emptyMap())

        assertEquals(listOf(10, 10, 10), defaults.app.let { listOf(it.maxSize, it.initialSize, it.minIdle) })
        assertEquals(listOf(3, 3, 3), defaults.jobs.let { listOf(it.maxSize, it.initialSize, it.minIdle) })
        assertNull(defaults.timings.maxIdleTime, "an idle connection is never closed")
        assertEquals(Duration.ofHours(6), defaults.timings.maxLifeTime, "long-lived: a new backend has a cold catalog")
        assertEquals(Duration.ofSeconds(5), defaults.timings.maintenanceInterval)

        val tuned =
            settings(
                mapOf(
                    "DATABASE_POOL_APP_SIZE" to "20",
                    "DATABASE_POOL_APP_INITIAL_SIZE" to "5",
                    "DATABASE_POOL_APP_MIN_IDLE" to "8",
                    "DATABASE_POOL_JOBS_SIZE" to "2",
                    "DATABASE_POOL_JOBS_MIN_IDLE" to "9", // more than the pool holds: capped at its size
                    "DATABASE_POOL_MAX_IDLE_MS" to "60000",
                    "DATABASE_POOL_MAX_LIFE_MS" to "600000",
                    "DATABASE_POOL_MAINTENANCE_MS" to "1000",
                ),
            )
        assertEquals(listOf(20, 5, 8), tuned.app.let { listOf(it.maxSize, it.initialSize, it.minIdle) })
        assertEquals(listOf(2, 2, 2), tuned.jobs.let { listOf(it.maxSize, it.initialSize, it.minIdle) })
        assertEquals(Duration.ofMinutes(1), tuned.timings.maxIdleTime)
        assertEquals(Duration.ofMinutes(10), tuned.timings.maxLifeTime)
        assertEquals(Duration.ofSeconds(1), tuned.timings.maintenanceInterval)
        assertNull(settings(mapOf("DATABASE_POOL_MAX_IDLE_MS" to "0")).timings.maxIdleTime, "0 also means never")
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
