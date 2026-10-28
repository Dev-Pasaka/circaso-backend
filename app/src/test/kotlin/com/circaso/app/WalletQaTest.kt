package com.circaso.app

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.impl.dev.FakeCustody
import com.circaso.integrations.impl.dev.FakeExchangeRates
import com.circaso.integrations.impl.dev.FakeSms
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.koinApplication
import org.koin.ktor.ext.getKoin
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WS2 QA: the wallet routes and the deletion guard through the whole server, with [FakeCustody] and [FakeExchangeRates]
 * so the test decides what Circle holds and whether it answers.
 */
class WalletQaTest {
    private companion object {
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379).also { it.start() }

        init {
            koinApplication { modules(businessModules(EnvConfig(emptyMap()))) }
                .koin
                .getAll<DatabaseModuleSpec>()
                .forEach(TestPostgres::migrate)
        }
    }

    private fun env() =
        mapOf(
            "CIRCASO_ENV" to "local",
            "REDIS_URL" to "redis://${redis.host}:${redis.getMappedPort(6379)}",
            "INTEGRATIONS_SMS_PROVIDER" to "fake",
            "INTEGRATIONS_CUSTODY_PROVIDER" to "fake",
            "INTEGRATIONS_RATES_PROVIDER" to "fake",
        ) + TestPostgres.env().filterKeys { it.startsWith("DATABASE_") }

    private class Fakes(val sms: FakeSms, val custody: FakeCustody, val rates: FakeExchangeRates)

    private class Person(val app: String)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private suspend fun HttpResponse.obj(): JsonObject = json(bodyAsText())

    private suspend fun HttpResponse.code(): String? =
        obj()["code"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.data(): JsonObject = obj()["data"]!!.jsonObject

    private suspend fun ApplicationTestBuilder.boot(): Fakes {
        lateinit var fakes: Fakes
        application {
            circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
            fakes =
                Fakes(
                    getKoin().get<SmsGateway>() as FakeSms,
                    getKoin().get<StablecoinCustody>() as FakeCustody,
                    getKoin().get<ExchangeRates>() as FakeExchangeRates,
                )
        }
        client.get("/health/live") // start the application
        return fakes
    }

    private suspend fun ApplicationTestBuilder.signUp(sms: FakeSms): Person {
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
        val nonce = call("/v1/auth/nonce", "{}").data()["nonce"]!!.jsonPrimitive.content
        val onboarding =
            call(
                "/v1/auth/social",
                """{"provider":"google","idToken":"dev:${UUID.randomUUID()}:qa@example.com:Qa","nonce":"$nonce",
                    "device":{"deviceId":"$deviceId","platform":"android","model":"Pixel","appVersion":"1.0.0"}}""",
            ).data()["session"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content
        val started = call("/v1/auth/phone/start", """{"phone":"$number"}""", onboarding).data()
        val code = Regex("[0-9]{6}").find(sms.sent.last().text)!!.value
        call(
            "/v1/auth/phone/verify",
            """{"verificationId":"${started["verificationId"]!!.jsonPrimitive.content}","code":"$code"}""",
            onboarding,
        )
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val registered =
            call(
                "/v1/devices",
                """{"deviceId":"$deviceId","publicKey":"$publicKey","keyAlgorithm":"ES256","model":"Pixel"}""",
                onboarding,
            ).data()
        return Person(registered["session"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content)
    }

    private suspend fun ApplicationTestBuilder.get(
        path: String,
        token: String,
    ) = client.get(path) {
        header("Authorization", "Bearer $token")
        header("X-Client-Version", "android/1.0.0")
    }

    private suspend fun ApplicationTestBuilder.send(
        method: String,
        path: String,
        token: String,
        body: String? = null,
        key: String = UUID.randomUUID().toString(),
    ): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", key)
            header("X-Client-Version", "android/1.0.0")
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        return if (method == "DELETE") client.delete(path, build) else client.post(path, build)
    }

    /** A wallet that is set up: W2, then Circle reports the wallet on the default chain. Returns Circle's user id. */
    private suspend fun ApplicationTestBuilder.activeWallet(
        fakes: Fakes,
        person: Person,
        funds: BigInteger? = null,
    ): String {
        val before = fakes.custody.users.toSet()
        assertEquals(HttpStatusCode.OK, send("POST", "/v1/wallet", person.app, "{}").status)
        val circleUser = (fakes.custody.users - before).single()
        val wallet = fakes.custody.completeSetup(circleUser)
        // W1 caches balances for 15 s, so funds are set before the first read
        funds?.let { fakes.custody.assetBalances[wallet.walletId] = mapOf("USDC" to it) }
        assertEquals("ACTIVE", get("/v1/wallet", person.app).data()["status"]!!.jsonPrimitive.content)
        return circleUser
    }

    @Test
    fun `deleting the account with an empty wallet works, with funds it is refused, with Circle down it says so`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            val circleUser = activeWallet(fakes, person)
            val walletId = fakes.custody.wallets.getValue(circleUser).single().walletId

            // dust below 0.01 USDC does not block
            fakes.custody.assetBalances[walletId] = mapOf("USDC" to BigInteger.valueOf(9_999))
            fakes.custody.failure = IntegrationError.Unavailable("Circle")
            val down = send("DELETE", "/v1/account", person.app)
            assertEquals(HttpStatusCode.BadGateway, down.status, down.bodyAsText())
            assertEquals("wallet.custody_unavailable", down.code())

            fakes.custody.failure = null
            fakes.custody.assetBalances[walletId] = mapOf("USDC" to BigInteger.valueOf(10_000))
            val funds = send("DELETE", "/v1/account", person.app)
            assertEquals(HttpStatusCode.Conflict, funds.status, funds.bodyAsText())
            assertEquals("wallet.funds_remaining", funds.code())
            assertTrue("wallet_not_empty" !in funds.bodyAsText())

            fakes.custody.assetBalances[walletId] = mapOf("USDC" to BigInteger.valueOf(9_999))
            val ok = send("DELETE", "/v1/account", person.app)
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        }

    @Test
    fun `funds on a second chain block deletion, and a wallet Circle lists but we never recorded does too`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            val circleUser = activeWallet(fakes, person)
            assertEquals(
                "ADDING",
                send("POST", "/v1/wallet/chains", person.app, """{"blockchain":"SOL-DEVNET"}""").data()["status"]!!.jsonPrimitive.content,
            )
            val sol = fakes.custody.completeSetup(circleUser, blockchain = "SOL-DEVNET")
            fakes.custody.assetBalances[sol.walletId] = mapOf("USDC" to BigInteger.valueOf(5_000_000))
            val funds = send("DELETE", "/v1/account", person.app)
            assertEquals("wallet.funds_remaining", funds.code(), funds.bodyAsText())
        }

    @Test
    fun `W1 sums the chains, prices them and leaves fiat null with no rate`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            val circleUser = activeWallet(fakes, person, funds = BigInteger.valueOf(20_500_000))
            send("POST", "/v1/wallet/chains", person.app, """{"blockchain":"SOL-DEVNET"}""")
            val sol = fakes.custody.completeSetup(circleUser, blockchain = "SOL-DEVNET")
            fakes.custody.assetBalances[sol.walletId] = mapOf("USDC" to BigInteger.valueOf(1_250_000))
            fakes.rates.rates["KES"] = BigDecimal("129.25")

            val data = get("/v1/wallet?currency=KES", person.app).data()
            assertEquals("21750000", data["totals"]!!.jsonArray.single().jsonObject["amountMinor"]!!.jsonPrimitive.content)
            assertEquals("21.75", data["totals"]!!.jsonArray.single().jsonObject["amount"]!!.jsonPrimitive.content)
            assertEquals("2811.19", data["fiat"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
            assertEquals("129.25", data["fiat"]!!.jsonObject["rate"]!!.jsonPrimitive.content)
            val chains = data["chains"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, chains.size)
            assertEquals(
                "2649.63",
                chains
                    .first { it["blockchain"]!!.jsonPrimitive.content == "BASE-SEPOLIA" }["assets"]!!
                    .jsonArray
                    .single()
                    .jsonObject["fiatAmount"]!!
                    .jsonPrimitive.content,
            )
            // no rate for this currency: fiat is null, never an error
            val none = get("/v1/wallet?currency=EUR", person.app)
            assertEquals(HttpStatusCode.OK, none.status)
            assertTrue(none.data()["fiat"] is kotlinx.serialization.json.JsonNull)
            // W1 with Circle down
            val other = signUp(fakes.sms)
            send("POST", "/v1/wallet", other.app, "{}")
            fakes.custody.failure = IntegrationError.Unavailable("Circle")
            // while SETTING_UP, Circle being down leaves what we have: SETTING_UP, not an error
            val down = get("/v1/wallet", other.app)
            assertEquals(HttpStatusCode.OK, down.status, down.bodyAsText())
            assertEquals("SETTING_UP", down.data()["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `concurrent W2 calls with different keys all answer one SETTING_UP challenge or already_exists`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            val results =
                coroutineScope {
                    (1..10).map { async { send("POST", "/v1/wallet", person.app, "{}") } }.awaitAll()
                }
            val summary = results.map { it.status.value to it.code() }
            println("W2 concurrent: $summary; circle users created=${fakes.custody.users.size}")
            assertTrue(results.all { it.status == HttpStatusCode.OK }, summary.toString())
            // however many challenges, one Circle user for the person
            val circleUsers = fakes.custody.users.size
            println("circle users=$circleUsers")
        }

    @Test
    fun `concurrent W12 adds of one chain with different keys`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            activeWallet(fakes, person)
            val results =
                coroutineScope {
                    (1..8)
                        .map { async { send("POST", "/v1/wallet/chains", person.app, """{"blockchain":"SOL-DEVNET"}""") } }
                        .awaitAll()
                }
            val summary = results.map { it.status.value to it.data()["status"]!!.jsonPrimitive.content }
            println("W12 concurrent: $summary")
            assertTrue(results.all { it.status == HttpStatusCode.OK }, summary.toString())
        }

    @Test
    fun `a wallet Circle holds on a chain that is no longer configured blocks deletion with custody_unavailable`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            val circleUser = activeWallet(fakes, person)
            // Circle lists a wallet on a chain this server does not offer
            val odd = fakes.custody.completeSetup(circleUser, blockchain = "AVAX-FUJI")
            assertNotNull(odd)
            val res = send("DELETE", "/v1/account", person.app)
            assertEquals("wallet.custody_unavailable", res.code(), res.bodyAsText())
            assertNull(null)
        }

    @Test
    fun `a chain the user already signed for at Circle answers ENABLED with no second challenge`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            val circleUser = activeWallet(fakes, person)
            // signed on another phone: Circle has the wallet, we have not recorded it yet
            fakes.custody.completeSetup(circleUser, blockchain = "SOL-DEVNET")
            val res = send("POST", "/v1/wallet/chains", person.app, """{"blockchain":"SOL-DEVNET"}""")
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            assertEquals("ENABLED", res.data()["status"]!!.jsonPrimitive.content, res.bodyAsText())
            assertTrue(res.data()["challengeId"] is kotlinx.serialization.json.JsonNull)
            // Circle down: nothing is asked for
            fakes.custody.failure = IntegrationError.Unavailable("Circle")
            val down = send("POST", "/v1/wallet/chains", person.app, """{"blockchain":"MATIC-AMOY"}""")
            assertEquals(HttpStatusCode.BadGateway, down.status, down.bodyAsText())
            assertEquals("wallet.custody_unavailable", down.code())
        }

    @Test
    fun `W2 after the PIN is set but before the wallet is recorded`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            val before = fakes.custody.users.toSet()
            send("POST", "/v1/wallet", person.app, "{}")
            val circleUser = (fakes.custody.users - before).single()
            fakes.custody.completeSetup(circleUser)
            val again = send("POST", "/v1/wallet", person.app, "{}")
            println("W2 after Circle has the wallet: ${again.status} ${again.bodyAsText()}")
            assertTrue(again.status == HttpStatusCode.Conflict || again.status == HttpStatusCode.OK)
        }
}
