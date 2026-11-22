package com.circaso.app

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.OpsJob
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.impl.dev.FakeEmail
import com.circaso.ramp.api.RampAdmin
import com.circaso.shared.Network
import com.circaso.shared.Resource
import io.ktor.client.request.HttpRequestBuilder
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The ramp across its two processes, booted for real (Koin, every module, the gateway, Redis and PostgreSQL), with
 * the `log` custody and the fake email (decision 2026-11-22). No line of the environment names a collections provider:
 * it is a row of `ramp.settings`, seeded `log` on a developer's machine, which is what this test is. The app serves the merchant portal; only the ops process
 * can make a wallet. A merchant signs up on the portal, staff approve them, the ops process's job makes their float
 * wallet, and the merchant then connects a payment account under a PIN and goes online, with no money anywhere.
 */
class RampWiringTest {
    private companion object {
        const val MASTER_KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val BASE = "/v1/ramp/merchant"

        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379).also { it.start() }

        init {
            koinApplication { modules(businessModules(EnvConfig(emptyMap()))) }
                .koin
                .getAll<DatabaseModuleSpec>()
                .forEach(TestPostgres::migrate)
        }
    }

    private val email = "pay-${UUID.randomUUID()}@crynce.co.ke"

    private fun env(extra: Map<String, String> = emptyMap()) =
        mapOf(
            "CIRCASO_ENV" to "local",
            "REDIS_URL" to "redis://${redis.host}:${redis.getMappedPort(6379)}",
            "INTEGRATIONS_SMS_PROVIDER" to "fake",
            "INTEGRATIONS_EMAIL_PROVIDER" to "fake",
            "RAMP_CREDENTIALS_MASTER_KEY" to MASTER_KEY,
            "WARMUP_WORKERS" to "1",
            "WARMUP_ROUNDS" to "1",
        ) + TestPostgres.env().filterKeys { it.startsWith("DATABASE_") } + extra

    private fun HttpRequestBuilder.json(body: String, session: String? = null) {
        header("Idempotency-Key", UUID.randomUUID().toString())
        session?.let { header("X-Merchant-Session", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun field(body: String, name: String): String = Regex("\"$name\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    @Test
    fun `a merchant signs up on the portal, the ops process makes their wallet, and they go online`() {
        var session = ""
        var merchantId = ""

        // the app's process: sign in with the emailed code, and apply
        testApplication {
            var koin: Koin? = null
            application {
                circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
                koin = getKoin()
            }
            val asked = client.post("$BASE/auth/code") { json("""{"email":"$email"}""") }
            assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
            val sent = (koin!!.get<EmailGateway>() as FakeEmail).sent.last()
            assertEquals(email, sent.to)
            val code = (sent.email as Email.MerchantSignInCode).code

            val verified = client.post("$BASE/auth/verify") { json("""{"email":"$email","code":"$code"}""") }
            assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
            session = field(verified.bodyAsText(), "sessionToken")
            merchantId = field(verified.bodyAsText(), "merchantId")

            val applied =
                client.put(
                    "$BASE/profile",
                ) { json("""{"displayName":"Crynce","contactPhone":"+254712000111"}""", session) }
            assertTrue("\"state\":\"APPLIED\"" in applied.bodyAsText(), applied.bodyAsText())
            val noKey = client.put("$BASE/profile") { header("X-Merchant-Session", session) }
            assertEquals(HttpStatusCode.BadRequest, noKey.status, "a state change needs an Idempotency-Key")
            assertEquals(HttpStatusCode.Unauthorized, client.get(BASE).status)
            assertEquals(0, koin!!.getAll<OpsJob>().first { it.name == "ramp.release" }.job(), "the app can't sign")
        }

        // the ops process: staff approve, and its job makes the float wallet
        testApplication {
            var koin: Koin? = null
            application {
                circasoOpsServe(EnvConfig(env()), EnvSecrets(env()))
                koin = getKoin()
            }
            client.get("/health/live")
            val admin = koin!!.get<RampAdmin>()
            val listed = admin.merchants(null, 200).first { it.merchantId.toString() == merchantId }
            assertEquals("APPLIED", listed.state)
            assertIs<Resource.Success<*>>(admin.approve(listed.merchantId, UUID.randomUUID()))

            val jobs = koin!!.getAll<OpsJob>().associateBy { it.name }
            // The process's own scheduler may already have run the job since the approval (it does on a slow
            // machine), so what is asserted is the wallet below, not how much this particular run found to do.
            jobs.getValue("ramp.release").job()
            jobs.getValue("ramp.settle-fees").job()

            val approved = admin.merchants(null, 200).first { it.merchantId.toString() == merchantId }
            assertEquals("APPROVED", approved.state)
            assertEquals(
                listOf(Network.TESTNET to "BASE-SEPOLIA"),
                approved.wallets.map { it.network to it.blockchain }.take(1),
            )
            val settings = admin.settings(Network.TESTNET)
            assertTrue(settings.walletSetId != null, "the ramp's wallet set was made")
            assertEquals("log", settings.collectionsProvider, "the local seed")
            assertEquals(listOf("payhero", "log"), settings.collectionsProviders, "what a local server offers")
        }

        // the app's process again (a new node): the wallet shows, and the merchant connects payments and goes online
        testApplication {
            application { circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env())) }

            val before = client.get(BASE) { header("X-Merchant-Session", session) }.bodyAsText()
            assertTrue("\"state\":\"APPROVED\"" in before && "\"blockchain\":\"BASE-SEPOLIA\"" in before, before)
            assertTrue("\"missing\":[\"payments\"]" in before, before)
            assertTrue("\"amountMinor\":\"1000000000\"" in before, "the log custody's float: $before")

            val account = """{"username":"api-user","password":"api-pass","channelId":"911","pin":"482913"}"""
            val payments = client.put("$BASE/payments") { json(account, session) }
            assertEquals(HttpStatusCode.OK, payments.status, payments.bodyAsText())
            assertTrue("/v1/hooks/payhero/crynce-" in payments.bodyAsText())
            assertTrue(
                "\"provider\":\"log\"" in client.get(BASE) { header("X-Merchant-Session", session) }.bodyAsText(),
            )

            val wrong = client.post("$BASE/session") { json("""{"pin":"000000","minutes":60}""", session) }
            assertTrue("ramp.pin_invalid" in wrong.bodyAsText(), wrong.bodyAsText())
            val online = client.post("$BASE/session") { json("""{"pin":"482913","minutes":60}""", session) }
            assertEquals(HttpStatusCode.OK, online.status, online.bodyAsText())

            val after = client.get(BASE) { header("X-Merchant-Session", session) }.bodyAsText()
            assertTrue("\"online\":{\"online\":true" in after && "\"canGoOnline\":true" in after, after)
            assertTrue("\"pinSet\":true" in after && "\"missing\":[]" in after, after)
            assertTrue("api-pass" !in after && "api-user" !in after)
            val earnings = client.get("$BASE/earnings") { header("X-Merchant-Session", session) }
            assertTrue("\"orders\":0" in earnings.bodyAsText(), earnings.bodyAsText())
            val statement = client.get("$BASE/statement") { header("X-Merchant-Session", session) }
            assertTrue("\"lines\":[]" in statement.bodyAsText(), statement.bodyAsText())
        }
    }
}
