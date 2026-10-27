package com.circaso.infrastructure.impl.config

import com.circaso.infrastructure.api.Environment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvConfigTest {
    private val env =
        mapOf(
            "DATABASE_URL" to "r2dbc:postgresql://a:5432/circaso",
            "DATABASE_POOL_WARM" to "true",
            "INTEGRATIONS_SMS_PROVIDER" to "log",
            "REDIS_POOL_SIZE" to "8",
            "BLANK" to "  ",
        )

    @Test
    fun `a dotted key reads its upper-case variable, dashes included`() {
        val config = EnvConfig(env)

        assertEquals("r2dbc:postgresql://a:5432/circaso", config.string("database.url"))
        assertEquals("log", config.string("integrations.sms.provider"))
        assertNull(config.string("database.user"))
        assertEquals("circaso_app", config.string("database.user", "circaso_app"))
    }

    @Test
    fun `a section puts its prefix in front of every key`() {
        val database = EnvConfig(env).section("database")

        assertEquals("r2dbc:postgresql://a:5432/circaso", database.string("url"))
        assertEquals("log", EnvConfig(env).section("integrations").section("sms").string("provider"))
    }

    @Test
    fun `numbers and booleans are parsed, and a bad value names its key`() {
        val config = EnvConfig(env)

        assertEquals(8, config.int("redis.pool-size", 1))
        assertEquals(1, config.int("redis.missing", 1))
        assertTrue(config.boolean("database.pool.warm", false))
        assertFalse(config.boolean("database.missing", false))
        val bad =
            assertFailsWith<IllegalStateException> {
                EnvConfig(
                    mapOf("REDIS_POOL_SIZE" to "many"),
                ).int("redis.pool-size", 1)
            }
        assertTrue("redis.pool-size" in bad.message!!)
        assertFailsWith<IllegalStateException> {
            EnvConfig(mapOf("DATABASE_POOL_WARM" to "yes")).boolean("database.pool.warm", false)
        }
    }

    @Test
    fun `a blank value counts as not set, and a required key that is missing says so`() {
        val config = EnvConfig(env)

        assertNull(config.string("blank"))
        assertFailsWith<IllegalStateException> { config.requireString("database.user") }
    }

    @Test
    fun `the environment comes from CIRCASO_ENV and defaults to local`() {
        assertEquals(Environment.LOCAL, EnvConfig(emptyMap()).environment)
        assertEquals(Environment.PRODUCTION, EnvConfig(mapOf("CIRCASO_ENV" to "Production")).environment)
        assertEquals(
            Environment.STAGING,
            EnvConfig(mapOf("CIRCASO_ENV" to "staging")).section("x").environment,
            "a section keeps it",
        )
        assertFailsWith<IllegalArgumentException> { EnvConfig(mapOf("CIRCASO_ENV" to "prod")) }
    }

    @Test
    fun `a missing secret is null, a required one fails, and none ever prints`() {
        val secrets = EnvSecrets(mapOf("DATABASE_PASSWORD" to "hunter2", "EMPTY" to ""))

        assertEquals("hunter2", secrets.get("DATABASE_PASSWORD"))
        assertNull(secrets.get("EMPTY"))
        assertNull(secrets.get("NOPE"))
        assertFailsWith<IllegalStateException> { secrets.require("NOPE") }
        assertFalse(secrets.toString().contains("hunter2"))
    }
}
