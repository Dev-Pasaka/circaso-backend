package com.circaso.app

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.EmailTemplateIds
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.impl.resend.ResendEmail
import com.circaso.shared.Resource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.ktor.ext.getKoin
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Email template ids are rows staff set, not lines of the environment (decision 2026-11-22). Boots both processes for
 * real with Resend as the email provider: they start with no template id anywhere, an id still in the old variable is
 * imported once, staff set one on the ops gateway, and the app's process reads both back from the table.
 */
class EmailTemplatesWiringTest {
    private companion object {
        const val SECRET = "a-sandbox-only-secret-of-32-chars!"

        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379).also { it.start() }

        init {
            koinApplication { modules(businessModules(EnvConfig(emptyMap()))) }
                .koin
                .getAll<DatabaseModuleSpec>()
                .forEach(TestPostgres::migrate)
        }
    }

    private fun env(extra: Map<String, String> = emptyMap()) =
        mapOf(
            "CIRCASO_ENV" to "local",
            "REDIS_URL" to "redis://${redis.host}:${redis.getMappedPort(6379)}",
            "INTEGRATIONS_SMS_PROVIDER" to "fake",
            "INTEGRATIONS_EMAIL_PROVIDER" to "resend",
            "RESEND_API_KEY" to "re_test_key",
            "INTEGRATIONS_EMAIL_FROM" to "Circaso <no-reply@circaso.com>",
            // nothing listens here: a send that got as far as Resend would fail differently from one with no id
            "INTEGRATIONS_EMAIL_BASE_URL" to "http://127.0.0.1:1",
            "WARMUP_WORKERS" to "1",
            "WARMUP_ROUNDS" to "1",
            "OPS_ALLOWED_ORIGINS" to "http://localhost:5173",
            "OPS_DEV_LOGIN_SECRET" to SECRET,
            "OPS_BOOTSTRAP_ADMINS" to "founder@circaso.com",
        ) + TestPostgres.env().filterKeys { it.startsWith("DATABASE_") } + extra

    private fun clearTemplates() =
        TestPostgres.superuser { connection ->
            connection.createStatement().use { it.execute("DELETE FROM ops.email_templates") }
        }

    @Test
    fun `both processes start with no template id, the old variable is imported once, and staff set the rest`() {
        clearTemplates()

        // the app, with no template id anywhere: it starts, and only the email itself isn't sent
        testApplication {
            var koin: Koin? = null
            application {
                circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
                koin = getKoin()
            }
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            val gateway = koin!!.get<EmailGateway>()
            assertIs<ResendEmail>(gateway)
            assertNull(koin!!.get<EmailTemplateIds>().idOf(EmailTemplate.WELCOME))
            val unsent = gateway.send("sam@example.com", Email.Welcome)
            val error = (unsent as Resource.Error).error
            assertIs<IntegrationError.Unavailable>(error)
            assertTrue(!error.isTransient, "no id is set: nothing to retry, and Resend was never asked")
        }

        // the ops process, which still has one id in the old variable: it starts, imports it, and staff set another
        val withSeed = env(mapOf("INTEGRATIONS_EMAIL_TEMPLATES_WELCOME" to "tpl_welcome_from_env"))
        testApplication {
            application { circasoOpsServe(EnvConfig(withSeed), EnvSecrets(withSeed)) }
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            val login =
                client.post("/v1/ops/auth/dev-login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"founder@circaso.com","secret":"$SECRET"}""")
                }
            val token = Regex("\"token\":\"([^\"]+)\"").find(login.bodyAsText())!!.groupValues[1]

            val listed = client.get("/v1/ops/email-templates") { header("Authorization", "Bearer $token") }.bodyAsText()
            assertTrue(
                "\"template\":\"WELCOME\",\"providerTemplateId\":\"tpl_welcome_from_env\",\"set\":true" in listed,
                listed,
            )
            assertTrue(
                "\"template\":\"MERCHANT_SIGN_IN_CODE\",\"providerTemplateId\":null,\"set\":false" in listed,
                listed,
            )

            val set =
                client.put("/v1/ops/email-templates/MERCHANT_SIGN_IN_CODE") {
                    header("Authorization", "Bearer $token")
                    header("Idempotency-Key", "set-code-template")
                    contentType(ContentType.Application.Json)
                    setBody("""{"providerTemplateId":"tpl_code","version":0,"reason":"Published in Resend"}""")
                }
            assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
            val audit = client.get("/v1/ops/audit") { header("Authorization", "Bearer $token") }.bodyAsText()
            assertTrue("email_template.set" in audit && "id (not set)→tpl_code" in audit, audit)
        }

        // the app again, its variable now saying something else: the rows win
        val changed = env(mapOf("INTEGRATIONS_EMAIL_TEMPLATES_WELCOME" to "tpl_changed_later"))
        testApplication {
            var koin: Koin? = null
            application {
                circasoServe(AppConfig(), EnvConfig(changed), EnvSecrets(changed))
                koin = getKoin()
            }
            client.get("/health/live")
            val ids = koin!!.get<EmailTemplateIds>()
            assertEquals(
                "tpl_welcome_from_env",
                ids.idOf(EmailTemplate.WELCOME),
                "imported once; the variable is ignored now",
            )
            assertEquals("tpl_code", ids.idOf(EmailTemplate.MERCHANT_SIGN_IN_CODE))
            assertNull(ids.idOf(EmailTemplate.RECOVERY_COMPLETED))
            val sent = koin!!.get<EmailGateway>().send("sam@example.com", Email.Welcome)
            val error = (sent as Resource.Error).error
            assertTrue(
                (error as IntegrationError.Unavailable).isTransient,
                "it has an id now: this time Resend was tried",
            )
        }
    }
}
