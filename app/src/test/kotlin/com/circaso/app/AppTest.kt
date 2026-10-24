package com.circaso.app

import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.HealthCheck
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppTest {
    private fun check(name: String, outcome: () -> Resource<Unit>) =
        object : HealthCheck {
            override val name = name

            override suspend fun check() = outcome()
        }

    private object Down : DomainError {
        override val code = "internal.error"
        override val message = "down"
        override val kind = ErrorKind.UPSTREAM
    }

    @Test
    fun `live answers 200 whatever the connections are doing`() =
        testApplication {
            application { circasoModule(AppConfig(), listOf(check("scylla") { Resource.error(Down) })) }

            val response = client.get("/health/live")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "up",
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["status"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `ready is 200 when there is nothing to check or every check passes`() =
        testApplication {
            application { circasoModule(AppConfig(), listOf(check("redis") { Resource.success(Unit) })) }

            val response = client.get("/health/ready")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ready", body["status"]!!.jsonPrimitive.content)
            assertEquals("up", body["checks"]!!.jsonObject["redis"]!!.jsonPrimitive.content)
        }

    @Test
    fun `ready is 503 and names what is down when a check fails or throws`() =
        testApplication {
            application {
                circasoModule(
                    AppConfig(),
                    listOf(
                        check("redis") { Resource.success(Unit) },
                        check("scylla") { Resource.error(Down) },
                        check("streams") { error("boom") },
                    ),
                )
            }

            val response = client.get("/health/ready")
            val checks = Json.parseToJsonElement(response.bodyAsText()).jsonObject["checks"]!!.jsonObject

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("up", checks["redis"]!!.jsonPrimitive.content)
            assertEquals("down", checks["scylla"]!!.jsonPrimitive.content)
            assertEquals("down", checks["streams"]!!.jsonPrimitive.content)
        }

    @Test
    fun `the app answers unknown routes in the envelope`() =
        testApplication {
            application { circasoModule(AppConfig()) }

            val response = client.get("/nope")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(
                "request.not_found",
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["code"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `config reads the environment and falls back to local defaults`() {
        assertEquals(AppConfig(), AppConfig.fromEnv(emptyMap()))

        val config =
            AppConfig.fromEnv(
                mapOf(
                    "CIRCASO_ENV" to "Production",
                    "PORT" to "9000",
                    "HOST" to "127.0.0.1",
                    "APP_VERSION" to "1.2.3",
                ),
            )

        assertEquals(AppConfig(Environment.PRODUCTION, "127.0.0.1", 9000, "1.2.3"), config)
    }

    @Test
    fun `config refuses an unknown environment or a bad port`() {
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("CIRCASO_ENV" to "prod")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("PORT" to "abc")) }
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("PORT" to "70000")) }
    }

    @Test
    fun `an env file understands comments, quotes, export and values with equals signs`() {
        val parsed =
            DotEnv.parse(
                listOf(
                    "# a comment",
                    "",
                    "PLAIN=value",
                    "export EXPORTED=yes",
                    "QUOTED=\"two words\"",
                    "SINGLE='x y'",
                    "TRAILING=kept   # not part of the value",
                    "KEY=abc=def==",
                    "EMPTY=",
                    "bad name=1",
                    "1BAD=2",
                ),
            )

        assertEquals(
            listOf(
                "PLAIN" to "value",
                "EXPORTED" to "yes",
                "QUOTED" to "two words",
                "SINGLE" to "x y",
                "TRAILING" to "kept",
                "KEY" to "abc=def==",
            ),
            parsed,
        )
    }

    @Test
    fun `an env file fills in what the environment lacks and never overrides what it has`() {
        val dir =
            Files
                .createTempDirectory("dotenv")
                .toFile()
        val sub = File(dir, "app/deeper").also { it.mkdirs() }
        File(
            dir,
            ".env",
        ).writeText("CIRCASO_TEST_DOTENV_NEW=from-file\nCIRCASO_TEST_DOTENV_SET=from-file\nSECRET_KEY=abc=def==\n")
        try {
            // found by walking up from a subdirectory; the second name is "really set" in the map standing for the environment
            val settings = DotEnv.environment(sub, env = mapOf("CIRCASO_TEST_DOTENV_SET" to "from-env"))

            assertEquals("from-file", settings["CIRCASO_TEST_DOTENV_NEW"])
            assertEquals(
                "from-env",
                settings["CIRCASO_TEST_DOTENV_SET"],
                "a variable that is really set wins over the file",
            )
            assertEquals("abc=def==", settings["SECRET_KEY"])
            assertEquals(
                "from-file",
                System.getProperty("CIRCASO_TEST_DOTENV_NEW"),
                "logging reads it from a system property",
            )
            assertNull(System.getProperty("CIRCASO_TEST_DOTENV_SET"))
            assertEquals(emptyMap(), DotEnv.read(File(System.getProperty("java.io.tmpdir"), "nowhere")))
        } finally {
            listOf("CIRCASO_TEST_DOTENV_NEW", "SECRET_KEY").forEach(System::clearProperty)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the banner shows the logo, where the server listens and where to look`() {
        val text =
            Banner.render(
                AppConfig(host = "0.0.0.0", port = 8080, version = "1.2.3"),
                com.circaso.infrastructure.impl.logging
                    .Palette(false),
            )

        assertEquals(5, Banner.logo().size)
        assertEquals(
            1,
            Banner
                .logo()
                .map { it.length }
                .toSet()
                .size,
            "every logo line is the same width",
        )
        assertTrue(Banner.logo().joinToString("\n").contains("|_|  |_|"), "the logo ends with the M of .COM")
        assertTrue("Application started on http://localhost:8080" in text)
        assertTrue("http://localhost:8080/docs" in text && "http://localhost:8080/health/ready" in text)
        assertTrue("version 1.2.3" in text)
        assertTrue("\u001B[" !in text, "no color codes when colors are off")
    }

    @Test
    fun `production does not advertise the api docs`() {
        val text =
            Banner.render(
                AppConfig(environment = Environment.PRODUCTION, host = "10.0.0.5", port = 9000),
                com.circaso.infrastructure.impl.logging
                    .Palette(false),
            )

        assertTrue("Application started on http://10.0.0.5:9000" in text)
        assertTrue("/docs" !in text)
    }

    @Test
    fun `the banner prints only in the pretty log format`() {
        val out = ByteArrayOutputStream()
        val saved = System.out
        System.setOut(PrintStream(out))
        try {
            Banner.print(
                AppConfig(),
                "JSON",
                com.circaso.infrastructure.impl.logging
                    .Palette(false),
            )
            assertEquals("", out.toString())
            Banner.print(
                AppConfig(),
                "pretty",
                com.circaso.infrastructure.impl.logging
                    .Palette(false),
            )
            assertTrue("Application started" in out.toString())
        } finally {
            System.setOut(saved)
        }
    }
}
