package com.circaso.app

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.OpsJob
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.impl.dev.FakeEmail
import com.circaso.integrations.impl.dev.FakeSms
import com.circaso.ramp.api.OpsRoutingQuery
import com.circaso.ramp.api.RampAdmin
import com.circaso.ramp.api.RampEngineAdmin
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.ktor.ext.getKoin
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.security.KeyPairGenerator
import java.util.Base64
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

    private class Buyer(val token: String, val userId: String, val phone: String)

    /** A person signs up the way the app does: social sign-in, their phone verified by SMS, a device registered. */
    private suspend fun ApplicationTestBuilder.signUp(sms: FakeSms): Buyer {
        val deviceId = UUID.randomUUID().toString()
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val address = "198.51.100.${(1..254).random()}"
        val number = "+254712${(100000..999999).random()}"

        suspend fun call(
            path: String,
            body: String,
            token: String? = null,
        ) = client.post(path) {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", UUID.randomUUID().toString())
            header("X-Client-Version", "android/1.0.0")
            header("CF-Connecting-IP", address)
            token?.let { header("Authorization", "Bearer $it") }
            setBody(body)
        }
        val nonce = field(call("/v1/auth/nonce", "{}").bodyAsText(), "nonce")
        val social =
            """{"provider":"google","idToken":"dev:${UUID.randomUUID()}:buyer@example.com:Buyer","nonce":"$nonce",""" +
                """"device":{"deviceId":"$deviceId","platform":"android","model":"Pixel","appVersion":"1.0.0"}}"""
        val onboarding = field(call("/v1/auth/social", social).bodyAsText(), "accessToken")
        val started = call("/v1/auth/phone/start", """{"phone":"$number"}""", onboarding).bodyAsText()
        val code = Regex("[0-9]{6}").find(sms.sent.last().text)!!.value
        val verify = """{"verificationId":"${field(started, "verificationId")}","code":"$code"}"""
        call("/v1/auth/phone/verify", verify, onboarding)
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val device =
            """{"deviceId":"$deviceId","publicKey":"$publicKey","keyAlgorithm":"ES256",""" +
                """"pushToken":"fcm:${UUID.randomUUID()}","model":"Pixel"}"""
        val registered = call("/v1/devices", device, onboarding).bodyAsText()
        return Buyer(field(registered, "accessToken"), field(registered, "userId"), number)
    }

    private fun HttpRequestBuilder.asBuyer(buyer: Buyer, body: String? = null) {
        header("Authorization", "Bearer ${buyer.token}")
        header("X-Client-Version", "android/1.0.0")
        header("X-Wallet-Network", "TESTNET")
        body?.let {
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody(it)
        }
    }

    /**
     * The whole path, booted for real in both processes, with identity verification required on the testnet and the
     * `log` KYC provider a local server seeds (it approves at once; no test ever calls Didit): a merchant signs up
     * and verifies, staff can't approve them before that and can after, the ops process makes their wallet, they go
     * online; then a user goes from unverified (no quote, no order) to verified, and buys.
     */
    @Test
    fun `with KYC required, a merchant and a user each verify with the log provider, and the user then buys`() {
        var session = ""
        var merchantId = ""

        // the app's process: the merchant signs in, applies and verifies; staff can't approve before that
        testApplication {
            var koin: Koin? = null
            application {
                circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
                koin = getKoin()
            }
            client.get("/health/live")
            val admin = koin!!.get<RampAdmin>()
            val seeded = admin.settings(Network.TESTNET)
            assertEquals("log" to "SANDBOX", seeded.kycProvider to seeded.kycEnvironment, "the local seed")
            assertEquals(listOf("didit", "log"), seeded.kycProviders, "what a local server offers")
            assertEquals("a3cb5782-d5c1-4c35-832d-ed373d80a0ea", seeded.kycWorkflowUser)
            assertEquals("dabdb52a-b4bc-435a-b2bf-a636bb17bad6", seeded.kycWorkflowMerchant)
            val opened = admin.updateSettings(seeded.copy(state = "ON", kycRequired = true), UUID.randomUUID())
            assertIs<Resource.Success<*>>(opened)

            val asked = client.post("$BASE/auth/code") { json("""{"email":"$email"}""") }
            assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
            val code = ((koin!!.get<EmailGateway>() as FakeEmail).sent.last().email as Email.MerchantSignInCode).code
            val verified = client.post("$BASE/auth/verify") { json("""{"email":"$email","code":"$code"}""") }
            session = field(verified.bodyAsText(), "sessionToken")
            merchantId = field(verified.bodyAsText(), "merchantId")
            client.put("$BASE/profile") { json("""{"displayName":"Crynce","contactPhone":"+254712000111"}""", session) }

            // The change was made in this process, so it is in force here at once.
            val before = client.get(BASE) { header("X-Merchant-Session", session) }.bodyAsText()
            assertTrue("\"missing\":[\"kyc\"" in before, before)
            assertTrue("\"kyc\":{\"status\":\"NONE\",\"required\":true,\"canStart\":true" in before, before)
            val listed = admin.merchants(null, 200).first { it.merchantId.toString() == merchantId }
            val refused = admin.approve(listed.merchantId, UUID.randomUUID())
            assertEquals("ramp.merchant_kyc_required", (refused as Resource.Error).error.code)

            val kyc = client.post("$BASE/kyc") { json("{}", session) }
            assertEquals(HttpStatusCode.OK, kyc.status, kyc.bodyAsText())
            assertTrue("\"status\":\"VERIFIED\"" in kyc.bodyAsText(), "log approves at once: ${kyc.bodyAsText()}")
            assertTrue("\"session\":null" in kyc.bodyAsText(), kyc.bodyAsText())
        }

        // the ops process: staff approve the verified merchant, and its job makes the float wallet
        testApplication {
            var koin: Koin? = null
            application {
                circasoOpsServe(EnvConfig(env()), EnvSecrets(env()))
                koin = getKoin()
            }
            client.get("/health/live")
            val admin = koin!!.get<RampAdmin>()
            val listed = admin.merchants(null, 200).first { it.merchantId.toString() == merchantId }
            assertEquals("VERIFIED", listed.verifications.single { it.environment == "SANDBOX" }.status)
            assertIs<Resource.Success<*>>(admin.approve(listed.merchantId, UUID.randomUUID()))
            koin!!.getAll<OpsJob>().first { it.name == "ramp.release" }.job()
            val approved = admin.merchants(null, 200).first { it.merchantId.toString() == merchantId }
            assertTrue((Network.TESTNET to "BASE-SEPOLIA") in approved.wallets.map { it.network to it.blockchain })
        }

        // the app's process again: the merchant goes online; a user verifies and buys
        testApplication {
            var koin: Koin? = null
            application {
                circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
                koin = getKoin()
            }
            val account = """{"username":"api-user","password":"api-pass","channelId":"911","pin":"482913"}"""
            assertEquals(HttpStatusCode.OK, client.put("$BASE/payments") { json(account, session) }.status)
            val online = client.post("$BASE/session") { json("""{"pin":"482913","minutes":60}""", session) }
            assertEquals(HttpStatusCode.OK, online.status, online.bodyAsText())

            val buyer = signUp(koin!!.get<SmsGateway>() as FakeSms)
            val wallet = client.post("/v1/wallet") { asBuyer(buyer, "{}") }
            assertEquals(HttpStatusCode.OK, wallet.status, wallet.bodyAsText())
            // The log custody's wallets appear as soon as they are asked for: reading the wallet picks them up.
            val ready = client.get("/v1/wallet") { asBuyer(buyer) }.bodyAsText()
            assertTrue("\"status\":\"ACTIVE\"" in ready, ready)

            // Unverified: R1 says so in full, and neither a quote nor an order is given.
            val unverified = client.get("/v1/ramp") { asBuyer(buyer) }.bodyAsText()
            assertTrue(
                "\"verification\":{\"status\":\"NONE\",\"required\":true,\"canStart\":true," +
                    "\"reason\":null,\"retryAt\":null,\"submitted\":false}" in unverified,
                unverified,
            )
            assertTrue("\"kyc\":\"NONE\"" in unverified && "\"canTrade\":false" in unverified, unverified)
            val quoteBody = """{"direction":"ON_RAMP","blockchain":"BASE-SEPOLIA","fiat":{"amount":"1300"}}"""
            val early = client.post("/v1/ramp/quotes") { asBuyer(buyer, quoteBody) }
            assertEquals(HttpStatusCode.Forbidden, early.status, early.bodyAsText())
            assertTrue("ramp.kyc_required" in early.bodyAsText(), early.bodyAsText())
            val noKey = client.post("/v1/ramp/kyc") { asBuyer(buyer) }
            assertEquals(HttpStatusCode.BadRequest, noKey.status, "a state change needs an Idempotency-Key")

            // R0: the log provider needs nothing from the person and approves at once.
            val started = client.post("/v1/ramp/kyc") { asBuyer(buyer, "{}") }
            assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
            assertTrue("\"status\":\"VERIFIED\"" in started.bodyAsText(), started.bodyAsText())
            val read = client.get("/v1/ramp/kyc") { asBuyer(buyer) }.bodyAsText()
            assertTrue("\"status\":\"VERIFIED\",\"required\":true,\"canStart\":false" in read, read)

            // Verified: R1 says so, the merchant is online, and the purchase starts.
            val verified = client.get("/v1/ramp") { asBuyer(buyer) }.bodyAsText()
            assertTrue("\"verification\":{\"status\":\"VERIFIED\"" in verified, verified)
            assertTrue("\"kyc\":\"VERIFIED\"" in verified && "\"blocked\":false" in verified, verified)
            assertTrue("\"merchantOnline\":true" in verified && "\"canTrade\":true" in verified, verified)
            val quote = client.post("/v1/ramp/quotes") { asBuyer(buyer, quoteBody) }
            assertEquals(HttpStatusCode.OK, quote.status, quote.bodyAsText())
            val order = """{"quoteId":"${field(quote.bodyAsText(), "quoteId")}","phone":"${buyer.phone}"}"""
            val bought = client.post("/v1/ramp/onramp/orders") { asBuyer(buyer, order) }
            assertEquals(HttpStatusCode.OK, bought.status, bought.bodyAsText())
            assertTrue("\"state\":\"PAYMENT_REQUESTED\"" in bought.bodyAsText(), bought.bodyAsText())
            assertTrue("\"merchantName\":\"Crynce\"" in bought.bodyAsText(), bought.bodyAsText())

            // Staff see the user's verification, in the sandbox, with no identity kept there.
            val seen = koin!!.get<RampAdmin>().userVerification(UserId(UUID.fromString(buyer.userId)))
            val row = seen.verifications.single()
            assertEquals(Triple("SANDBOX", "VERIFIED", false), Triple(row.environment, row.status, row.hasIdentity))
            assertEquals("log", row.provider)

            // The database outlives this test: leave the testnet as the other test expects to find it.
            val admin = koin!!.get<RampAdmin>()
            val left = admin.settings(Network.TESTNET).copy(state = "OFF", kycRequired = false)
            assertIs<Resource.Success<*>>(admin.updateSettings(left, UUID.randomUUID()))

            // The merchant engine is wired: the purchase's routing was logged, under the testnet's seeded settings.
            val engine = koin!!.get<RampEngineAdmin>()
            val settings = (engine.settings(Network.TESTNET, "ON_RAMP") as Resource.Success).data
            assertEquals("ACTIVE" to 1L, settings.settings.mode to settings.version)
            val routed = (engine.decisions(OpsRoutingQuery(Network.TESTNET), null, 10) as Resource.Success).data
            val decision = routed.first()
            assertEquals(
                Triple("ROUTED", "ACTIVE", 1L),
                Triple(decision.outcome, decision.mode, decision.settingsVersion),
            )
            assertEquals(listOf("prompted"), decision.steps.map { it.result })
            assertTrue(koin!!.getAll<ScheduledJob>().any { it.name == "ramp.prune-routing" }, "and its log is pruned")

            // Verifying is not a trade (decision 2026-11-23): with buying switched off again and KYC not required,
            // the profile still reads the person's verification, and R0 still answers.
            val closed = client.get("/v1/ramp") { asBuyer(buyer) }
            assertEquals(HttpStatusCode.Forbidden, closed.status, closed.bodyAsText())
            assertTrue("ramp.unavailable" in closed.bodyAsText(), closed.bodyAsText())
            val profile = client.get("/v1/ramp/kyc") { asBuyer(buyer) }
            assertEquals(HttpStatusCode.OK, profile.status, profile.bodyAsText())
            assertTrue("\"status\":\"VERIFIED\",\"required\":false" in profile.bodyAsText(), profile.bodyAsText())
            val again = client.post("/v1/ramp/kyc") { asBuyer(buyer, "{}") }
            assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
            assertTrue("\"status\":\"VERIFIED\"" in again.bodyAsText(), again.bodyAsText())
        }
    }

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
            // This test is the ramp before identity checks are switched on, whatever ran before it.
            client.get("/health/live")
            val staff = koin!!.get<RampAdmin>()
            staff.updateSettings(staff.settings(Network.TESTNET).copy(kycRequired = false), UUID.randomUUID())

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
            // One wallet per network, in no promised order: the testnet's is the one this test goes on to use.
            assertTrue(
                (Network.TESTNET to "BASE-SEPOLIA") in approved.wallets.map { it.network to it.blockchain },
                "the testnet float wallet was made",
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
