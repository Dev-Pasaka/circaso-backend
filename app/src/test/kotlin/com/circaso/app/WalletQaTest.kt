package com.circaso.app

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyNotifications
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.impl.dev.FakeCustody
import com.circaso.integrations.impl.dev.FakeExchangeRates
import com.circaso.integrations.impl.dev.FakePush
import com.circaso.integrations.impl.dev.FakeSms
import com.circaso.shared.Resource
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
import java.time.Instant
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

    private fun env(extra: Map<String, String> = emptyMap()) =
        mapOf(
            "CIRCASO_ENV" to "local",
            "REDIS_URL" to "redis://${redis.host}:${redis.getMappedPort(6379)}",
            "INTEGRATIONS_SMS_PROVIDER" to "fake",
            "INTEGRATIONS_CUSTODY_PROVIDER" to "fake",
            "INTEGRATIONS_RATES_PROVIDER" to "fake",
            "INTEGRATIONS_PUSH_PROVIDER" to "fake",
        ) + TestPostgres.env().filterKeys { it.startsWith("DATABASE_") } + extra

    private class Fakes(
        val sms: FakeSms,
        val custody: FakeCustody,
        val rates: FakeExchangeRates,
        val notifications: CustodyNotifications,
        val push: FakePush,
    )

    private class Person(
        val app: String,
        val userId: String,
        val phone: String,
        val push: String,
        val agent: String,
    )

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private suspend fun HttpResponse.obj(): JsonObject = json(bodyAsText())

    private suspend fun HttpResponse.code(): String? =
        obj()["code"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.data(): JsonObject = obj()["data"]!!.jsonObject

    private suspend fun ApplicationTestBuilder.boot(extra: Map<String, String> = emptyMap()): Fakes {
        lateinit var fakes: Fakes
        application {
            circasoServe(AppConfig(), EnvConfig(env(extra)), EnvSecrets(env(extra)))
            fakes =
                Fakes(
                    getKoin().get<SmsGateway>() as FakeSms,
                    getKoin().get<StablecoinCustody>() as FakeCustody,
                    getKoin().get<ExchangeRates>() as FakeExchangeRates,
                    getKoin().get<CustodyNotifications>(),
                    getKoin().get<PushGateway>() as FakePush,
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
        val pushToken = "fcm:${UUID.randomUUID()}"

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
                """{"deviceId":"$deviceId","publicKey":"$publicKey","keyAlgorithm":"ES256","pushToken":"$pushToken","model":"Pixel"}""",
                onboarding,
            ).data()
        return Person(
            registered["session"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content,
            registered["user"]!!.jsonObject["userId"]!!.jsonPrimitive.content,
            number,
            pushToken,
            registered["agentSession"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content,
        )
    }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String) =
        client.get(path) {
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
        return when (method) {
            "DELETE" -> client.delete(path, build)
            "PUT" -> client.put(path, build)
            else -> client.post(path, build)
        }
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
            val walletId =
                fakes.custody.wallets
                    .getValue(circleUser)
                    .single()
                    .walletId

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
                send(
                    "POST",
                    "/v1/wallet/chains",
                    person.app,
                    """{"blockchain":"SOL-DEVNET"}""",
                ).data()["status"]!!.jsonPrimitive.content,
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
            assertEquals(
                "21750000",
                data["totals"]!!
                    .jsonArray
                    .single()
                    .jsonObject["amountMinor"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "21.75",
                data["totals"]!!
                    .jsonArray
                    .single()
                    .jsonObject["amount"]!!
                    .jsonPrimitive.content,
            )
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
                        .map {
                            async {
                                send(
                                    "POST",
                                    "/v1/wallet/chains",
                                    person.app,
                                    """{"blockchain":"SOL-DEVNET"}""",
                                )
                            }
                        }.awaitAll()
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

    @Test
    fun `W11, W4 to an outside address, Circle's update through CustodyNotifications, then W5`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            val address = "0xAbCdEf0123456789aBcDeF0123456789AbCdEf01"
            val validate =
                send(
                    "POST",
                    "/v1/wallet/addresses/validate",
                    person.app,
                    """{"blockchain":"BASE-SEPOLIA","address":"$address"}""",
                ).data()
            assertEquals("true", validate["valid"]!!.jsonPrimitive.content)
            val body =
                """{"blockchain":"BASE-SEPOLIA","to":{"address":"$address"},"amount":{"asset":"USDC","amount":"2.5"}}"""
            assertEquals("wallet.not_found", send("POST", "/v1/wallet/transfers", person.app, body).code())

            activeWallet(fakes, person, funds = BigInteger.valueOf(10_000_000))
            val key = UUID.randomUUID().toString()
            val prepared = send("POST", "/v1/wallet/transfers", person.app, body, key)
            assertEquals(HttpStatusCode.OK, prepared.status, prepared.bodyAsText())
            val data = prepared.data()
            assertEquals("AWAITING_SIGNATURE", data["state"]!!.jsonPrimitive.content)
            assertEquals("2500000", data["amount"]!!.jsonObject["amountMinor"]!!.jsonPrimitive.content)
            assertEquals("2.5", data["amount"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
            assertEquals("0", data["fee"]!!.jsonObject["amountMinor"]!!.jsonPrimitive.content)
            val transferId = data["transferId"]!!.jsonPrimitive.content
            val (circleUser, request) = fakes.custody.transfers.single()
            assertEquals(transferId, request.refId)

            val notifications = fakes.notifications
            val update =
                CustodyTransaction(
                    circleTxId = "tx-qa",
                    circleUserId = circleUser,
                    walletId = request.walletId,
                    blockchain = "BASE-SEPOLIA",
                    direction = CustodyDirection.OUT,
                    state = CustodyTransactionState.CONFIRMED,
                    amount = request.amount,
                    sourceAddress = "",
                    destinationAddress = request.destinationAddress,
                    refId = transferId,
                    txHash = "0xqa",
                    failureReason = "",
                    updatedAt = Instant.now(),
                )
            assertTrue(notifications.onTransaction(update) is Resource.Success)

            val shown = get("/v1/wallet/transfers/$transferId", person.app).data()
            assertEquals("CONFIRMED", shown["state"]!!.jsonPrimitive.content)
            assertEquals("OUT", shown["direction"]!!.jsonPrimitive.content)
            assertEquals("0xqa", shown["txHash"]!!.jsonPrimitive.content)
            assertEquals(address.lowercase(), shown["to"]!!.jsonObject["address"]!!.jsonPrimitive.content)
            val again = send("POST", "/v1/wallet/transfers", person.app, body, UUID.randomUUID().toString())
            assertEquals(HttpStatusCode.OK, again.status, "a new key is a new transfer")
        }

    // ---------------------------------------------------------------------------------------------------------
    // WS3: transfers (W4, W5), recipients (W9, W10), addresses (W11), Circle's updates, frames, pushes, outbox
    // ---------------------------------------------------------------------------------------------------------

    private val base = "BASE-SEPOLIA"

    private fun usdc(minor: Long) = BigInteger.valueOf(minor)

    private fun toUser(person: Person) = """{"userId":"${person.userId}"}"""

    private fun amountOf(amount: String) = """{"asset":"USDC","amount":"$amount"}"""

    private fun transferBody(
        to: String,
        amount: String,
        chain: String = base,
        extra: String = "",
    ) = """{"blockchain":"$chain","to":$to,"amount":$amount$extra}"""

    private suspend fun ApplicationTestBuilder.w4(
        person: Person,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ) = send("POST", "/v1/wallet/transfers", person.app, body, key)

    private suspend fun expect(
        label: String,
        response: HttpResponse,
        status: Int,
        code: String?,
    ) {
        val text = response.bodyAsText()
        assertEquals(status, response.status.value, "$label: $text")
        assertEquals(code, response.code(), "$label: $text")
    }

    /** Both people save the other's number, so each can reach the other. */
    private suspend fun ApplicationTestBuilder.meet(a: Person, b: Person) {
        for ((from, to) in listOf(a to b, b to a)) {
            val body =
                """{"syncId":"${UUID.randomUUID()}","batch":1,"final":true,"phones":["${to.phone}"]}"""
            val synced = send("POST", "/v1/contacts/sync", from.app, body)
            assertEquals(HttpStatusCode.OK, synced.status, synced.bodyAsText())
        }
    }

    private fun walletOf(
        fakes: Fakes,
        circleUser: String,
        chain: String = base,
    ) = fakes.custody.wallets
        .getValue(circleUser)
        .first { it.blockchain == chain }

    /** A person with an active wallet on BASE-SEPOLIA holding [funds]; returns the person and Circle's user id. */
    private suspend fun ApplicationTestBuilder.walletOwner(
        fakes: Fakes,
        funds: BigInteger? = null,
    ): Pair<Person, String> {
        val person = signUp(fakes.sms)
        return person to activeWallet(fakes, person, funds)
    }

    private fun update(
        circleUser: String,
        wallet: com.circaso.integrations.api.CustodyWallet,
        direction: CustodyDirection,
        state: CustodyTransactionState,
        amount: com.circaso.shared.Money,
        circleTxId: String,
        refId: String = "",
        source: String = "",
        destination: String = "",
        hash: String = "",
        reason: String = "",
    ) = CustodyTransaction(
        circleTxId = circleTxId,
        circleUserId = circleUser,
        walletId = wallet.walletId,
        blockchain = wallet.blockchain,
        direction = direction,
        state = state,
        amount = amount,
        sourceAddress = source,
        destinationAddress = destination,
        refId = refId,
        txHash = hash,
        failureReason = reason,
        updatedAt = Instant.now(),
    )

    private fun count(sql: String, vararg args: String): Int =
        TestPostgres.superuser { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun outbox(type: String, transferId: String) =
        count("SELECT count(*) FROM wallet.outbox WHERE type = ? AND payload LIKE ?", type, "%$transferId%")

    private fun frames(userId: String): List<JsonObject> =
        TestPostgres.superuser { connection ->
            connection.prepareStatement("SELECT frame FROM wallet.realtime WHERE user_id = ?::uuid ORDER BY seq").use {
                it.setString(1, userId)
                it.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) json(rows.getString(1)) else null }.toList()
                }
            }
        }

    private fun JsonObject.type() = this["type"]!!.jsonPrimitive.content

    private fun JsonObject.field(name: String) =
        this["data"]!!
            .jsonObject[name]
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.jsonPrimitive
            ?.content

    @Test
    fun `W4 applies the rules in the order of the spec and answers each code`() =
        testApplication {
            val fakes = boot()
            val (payer, payerCircle) = walletOwner(fakes, usdc(50_000_000))
            val (payee, _) = walletOwner(fakes)
            val (stranger, _) = walletOwner(fakes)
            val noWallet = signUp(fakes.sms)
            val noWalletAtAll = signUp(fakes.sms)
            meet(payer, payee)
            meet(payer, noWallet)
            val payerWallet = walletOf(fakes, payerCircle)

            // chain rules come first, whatever else is wrong with the request
            expect(
                "unsupported chain",
                w4(payer, transferBody(toUser(payee), amountOf("0"), "DOGE")),
                400,
                "wallet.chain_unsupported",
            )
            expect(
                "no wallet at all",
                w4(noWalletAtAll, transferBody(toUser(payee), amountOf("1"))),
                404,
                "wallet.not_found",
            )
            expect(
                "not on that chain",
                w4(payer, transferBody(toUser(payer), amountOf("0"), "SOL-DEVNET")),
                409,
                "wallet.not_on_chain",
            )

            // then the amount, before the payee
            expect("zero", w4(payer, transferBody(toUser(payer), amountOf("0"))), 400, "wallet.amount_invalid")
            expect("negative", w4(payer, transferBody(toUser(stranger), amountOf("-1"))), 400, "wallet.amount_invalid")
            expect(
                "7 decimals",
                w4(payer, transferBody(toUser(stranger), amountOf("1.0000001"))),
                400,
                "wallet.amount_invalid",
            )
            expect(
                "minor and whole disagree",
                w4(payer, transferBody(toUser(payee), """{"asset":"USDC","amountMinor":"2000000","amount":"3"}""")),
                400,
                "wallet.amount_invalid",
            )
            expect(
                "a coin the wallet doesn't hold on the chain (any held coin can be paid since 2026-11-07)",
                w4(payer, transferBody(toUser(payee), """{"asset":"EURC","amount":"1"}""")),
                400,
                "wallet.asset_not_held",
            )
            expect(
                "zero minor",
                w4(payer, transferBody(toUser(payee), """{"asset":"USDC","amountMinor":"0"}""")),
                400,
                "wallet.amount_invalid",
            )
            val huge =
                w4(payer, transferBody(toUser(payee), """{"asset":"USDC","amountMinor":"99999999999999999999"}"""))
            println("W4 amountMinor over a Long: ${huge.status} ${huge.bodyAsText()}")
            assertEquals(400, huge.status.value)
            val junk = w4(payer, transferBody(toUser(payee), """{"asset":"USDC","amountMinor":"abc"}"""))
            println("W4 amountMinor abc: ${junk.status} ${junk.bodyAsText()}")
            assertEquals(400, junk.status.value)
            // per payment limit (500 USDC), checked before the payee and the balance
            expect(
                "per payment limit",
                w4(payer, transferBody(toUser(stranger), amountOf("500.000001"))),
                400,
                "wallet.limit_exceeded",
            )

            // then the payee, before the balance
            expect("yourself", w4(payer, transferBody(toUser(payer), amountOf("1"))), 400, "wallet.self_transfer")
            val unreachable = w4(payer, transferBody(toUser(stranger), amountOf("1")))
            println("W4 to someone not reachable: ${unreachable.status} ${unreachable.bodyAsText()}")
            assertEquals("users.not_reachable", unreachable.code())
            expect(
                "a limit comes before a stranger",
                w4(payer, transferBody(toUser(stranger), amountOf("600"))),
                400,
                "wallet.limit_exceeded",
            )
            expect(
                "unknown user",
                w4(payer, transferBody("""{"userId":"${UUID.randomUUID()}"}""", amountOf("1"))),
                403,
                "users.not_reachable",
            )
            expect(
                "contact without a wallet",
                w4(payer, transferBody(toUser(noWallet), amountOf("1"))),
                409,
                "wallet.recipient_no_wallet",
            )
            // the payee has a wallet on BASE only; the payer adds MATIC
            send("POST", "/v1/wallet/chains", payer.app, """{"blockchain":"MATIC-AMOY"}""")
            val matic = fakes.custody.completeSetup(payerCircle, blockchain = "MATIC-AMOY")
            fakes.custody.assetBalances[matic.walletId] = mapOf("USDC" to usdc(5_000_000))
            // W1 records the wallet Circle now lists (the chain is being added)
            get("/v1/wallet", payer.app)
            expect(
                "payee not on the chain",
                w4(payer, transferBody(toUser(payee), amountOf("1"), "MATIC-AMOY")),
                409,
                "wallet.recipient_not_on_chain",
            )
            // addresses
            expect(
                "address invalid",
                w4(payer, transferBody("""{"address":"0x123"}""", amountOf("1"))),
                400,
                "wallet.address_invalid",
            )
            expect(
                "an EVM address on Solana",
                w4(
                    payer,
                    transferBody(
                        """{"address":"0x0123456789abcdef0123456789abcdef01234567"}""",
                        amountOf("1"),
                        "SOL-DEVNET",
                    ),
                ),
                409,
                "wallet.not_on_chain",
            )
            expect(
                "own address",
                w4(payer, transferBody("""{"address":"${payerWallet.address}"}""", amountOf("1"))),
                400,
                "wallet.self_transfer",
            )
            expect(
                "own address, upper case",
                w4(
                    payer,
                    transferBody("""{"address":"0x${payerWallet.address.drop(2).uppercase()}"}""", amountOf("1")),
                ),
                400,
                "wallet.self_transfer",
            )
            // balance last
            expect("balance", w4(payer, transferBody(toUser(payee), amountOf("60"))), 400, "wallet.insufficient_funds")
            expect(
                "balance on the other chain",
                w4(
                    payer,
                    transferBody(
                        """{"address":"0x0123456789abcdef0123456789abcdef01234567"}""",
                        amountOf("6"),
                        "MATIC-AMOY",
                    ),
                ),
                400,
                "wallet.insufficient_funds",
            )

            // request shape
            expect("neither payee", w4(payer, transferBody("{}", amountOf("1"))), 400, "request.malformed")
            expect(
                "both payees",
                w4(
                    payer,
                    transferBody(
                        """{"userId":"${payee.userId}","address":"0x0123456789abcdef0123456789abcdef01234567"}""",
                        amountOf("1"),
                    ),
                ),
                400,
                "request.malformed",
            )
            expect(
                "userId not a uuid",
                w4(payer, transferBody("""{"userId":"abc"}""", amountOf("1"))),
                400,
                "request.malformed",
            )
            expect(
                "no amount",
                w4(payer, transferBody(toUser(payee), """{"asset":"USDC"}""")),
                400,
                "request.malformed",
            )
            val asha =
                w4(
                    payer,
                    transferBody(toUser(payee), amountOf("1"), extra = ""","proposalId":"${UUID.randomUUID()}""""),
                )
            expect("proposalId", asha, 400, "request.malformed")
            assertTrue("proposalId" in asha.bodyAsText(), asha.bodyAsText())
            expect(
                "chatId not a uuid",
                w4(payer, transferBody(toUser(payee), amountOf("1"), extra = ""","chatId":"x"""")),
                400,
                "request.malformed",
            )
            val noKey =
                client.post("/v1/wallet/transfers") {
                    header("Authorization", "Bearer ${payer.app}")
                    header("X-Client-Version", "android/1.0.0")
                    contentType(ContentType.Application.Json)
                    setBody(transferBody(toUser(payee), amountOf("1")))
                }
            expect("no Idempotency-Key", noKey, 400, "request.malformed")
            // nothing above wrote a transfer
            assertEquals(0, count("SELECT count(*) FROM wallet.transfers WHERE sender_id = ?::uuid", payer.userId))

            // the happy path, and the same key again
            val key = UUID.randomUUID().toString()
            val first = w4(payer, transferBody(toUser(payee), amountOf("20")), key)
            expect("prepared", first, 200, null)
            val data = first.data()
            assertEquals("AWAITING_SIGNATURE", data["state"]!!.jsonPrimitive.content)
            assertEquals("20000000", data["amount"]!!.jsonObject["amountMinor"]!!.jsonPrimitive.content)
            assertEquals(base, data["blockchain"]!!.jsonPrimitive.content)
            assertNotNull(data["challengeId"])
            val again = w4(payer, transferBody(toUser(payee), amountOf("20")), key)
            assertEquals(data["transferId"], again.data()["transferId"])
            assertEquals(data["challengeId"], again.data()["challengeId"])
            val differentBody = w4(payer, transferBody(toUser(payee), amountOf("21")), key)
            println("W4 same key, different body: ${differentBody.status} ${differentBody.bodyAsText()}")
            assertEquals(1, count("SELECT count(*) FROM wallet.transfers WHERE sender_id = ?::uuid", payer.userId))
            // upper and lower case chain names
            val lower = w4(payer, transferBody(toUser(payee), amountOf("1"), "base-sepolia"))
            println("W4 lower-case chain: ${lower.status} ${lower.bodyAsText()}")
            // amount given as minor units, and as both when they agree
            expect(
                "minor",
                w4(payer, transferBody(toUser(payee), """{"asset":"USDC","amountMinor":"1500000"}""")),
                200,
                null,
            )
            expect(
                "both agree",
                w4(payer, transferBody(toUser(payee), """{"asset":"USDC","amountMinor":"2500000","amount":"2.5"}""")),
                200,
                null,
            )
            expect(
                "both agree, long form",
                w4(
                    payer,
                    transferBody(toUser(payee), """{"asset":"USDC","amountMinor":"2500000","amount":"2.500000"}"""),
                ),
                200,
                null,
            )
            // an address, any case
            val mixed =
                w4(payer, transferBody("""{"address":"0xAbCdEf0123456789aBcDeF0123456789AbCdEf01"}""", amountOf("1")))
            expect("EVM address in mixed case", mixed, 200, null)
        }

    @Test
    fun `the daily limit counts prepared transfers and a failed one gives its amount back once`() =
        testApplication {
            val fakes = boot()
            val (payer, payerCircle) = walletOwner(fakes, usdc(5_000_000_000))
            val outside = """{"address":"0x0123456789abcdef0123456789abcdef01234567"}"""
            val a = w4(payer, transferBody(outside, amountOf("500"))).data()["transferId"]!!.jsonPrimitive.content
            expect("second", w4(payer, transferBody(outside, amountOf("400"))), 200, null)
            expect("over the day", w4(payer, transferBody(outside, amountOf("200"))), 400, "wallet.limit_exceeded")
            expect("exactly the day", w4(payer, transferBody(outside, amountOf("100"))), 200, null)
            expect(
                "one more minor unit",
                w4(payer, transferBody(outside, """{"asset":"USDC","amountMinor":"1"}""")),
                400,
                "wallet.limit_exceeded",
            )

            val wallet = walletOf(fakes, payerCircle)
            val failed =
                update(
                    payerCircle,
                    wallet,
                    CustodyDirection.OUT,
                    CustodyTransactionState.FAILED,
                    com.circaso.shared.Money(500_000_000, com.circaso.shared.Asset.USDC),
                    "tx-a",
                    refId = a,
                    reason = "DENIED",
                )
            assertTrue(fakes.notifications.onTransaction(failed) is Resource.Success)
            assertTrue(fakes.notifications.onTransaction(failed) is Resource.Success, "a duplicate is acknowledged")
            assertEquals("FAILED", get("/v1/wallet/transfers/$a", payer.app).data()["state"]!!.jsonPrimitive.content)
            assertEquals(
                "DENIED",
                get("/v1/wallet/transfers/$a", payer.app).data()["failureReason"]!!.jsonPrimitive.content,
            )
            assertEquals(1, outbox("wallet.TransferFailed", a))
            // 500 came back once: 500 fit again, a second 500 does not
            expect("given back", w4(payer, transferBody(outside, amountOf("500"))), 200, null)
            expect(
                "not twice",
                w4(payer, transferBody(outside, """{"asset":"USDC","amountMinor":"1"}""")),
                400,
                "wallet.limit_exceeded",
            )
            // the failed update goes to the sender as a frame, once, with a push on top
            val updated = frames(payer.userId).filter { it.type() == "transfer.updated" && it.field("transferId") == a }
            assertEquals(1, updated.size, updated.toString())
            assertEquals("FAILED", updated.single().field("state"))
            assertEquals("DENIED", updated.single().field("failureReason"))
        }

    @Test
    fun `W17 quotes the fee and the limit for exactly a payment, and nothing is prepared`() =
        testApplication {
            val fakes = boot()
            val (payer, _) = walletOwner(fakes, usdc(5_000_000_000))
            val outside = """{"address":"0x0123456789abcdef0123456789abcdef01234567"}"""
            w4(payer, transferBody(outside, amountOf("500"))).data()
            w4(payer, transferBody(outside, amountOf("100"))).data()
            val before = count("SELECT count(*) FROM wallet.transfers")

            val quote =
                send(
                    "POST",
                    "/v1/wallet/transfers/quote",
                    payer.app,
                    transferBody(outside, amountOf("450")),
                    UUID.randomUUID().toString(),
                )
            val data = quote.data()

            assertEquals(200, quote.status.value)
            // The fake custodian can't batch, so the 2 % rule for outside addresses takes nothing (as on Solana).
            assertEquals("0", data["fee"]!!.jsonObject["amountMinor"]!!.jsonPrimitive.content)
            assertEquals("200", data["fee"]!!.jsonObject["rateBps"]!!.jsonPrimitive.content)
            assertEquals("DAILY_LIMIT", data["problem"]!!.jsonPrimitive.content, "600 + 450 is over the 1 000 a day")
            assertEquals(
                "400",
                data["limit"]!!
                    .jsonObject["left"]!!
                    .jsonObject["amount"]!!
                    .jsonPrimitive.content,
            )
            assertEquals("400", data["maxAmount"]!!.jsonObject["amount"]!!.jsonPrimitive.content, "what is left today")
            assertEquals(before, count("SELECT count(*) FROM wallet.transfers"), "a quote prepares nothing")
        }

    @Test
    fun `an expired transfer that Circle then fails stops counting against the day, and nothing more comes back`() =
        testApplication {
            val fakes = boot()
            val (payer, payerCircle) = walletOwner(fakes, usdc(5_000_000_000))
            val outside = """{"address":"0x0123456789abcdef0123456789abcdef01234567"}"""
            val a = w4(payer, transferBody(outside, amountOf("500"))).data()["transferId"]!!.jsonPrimitive.content
            expect("fill the day", w4(payer, transferBody(outside, amountOf("500"))), 200, null)
            // ExpireUnsignedTransfers is WS4's; do what it does to the row
            TestPostgres.superuser {
                it.createStatement().use { s ->
                    s.execute("UPDATE wallet.transfers SET state = 'EXPIRED' WHERE transfer_id = '$a'")
                    s.execute("UPDATE wallet.transactions SET state = 'EXPIRED' WHERE transfer_id = '$a'")
                }
            }
            val wallet = walletOf(fakes, payerCircle)
            val failed =
                update(
                    payerCircle,
                    wallet,
                    CustodyDirection.OUT,
                    CustodyTransactionState.FAILED,
                    com.circaso.shared.Money(500_000_000, com.circaso.shared.Asset.USDC),
                    "tx-e",
                    refId = a,
                    reason = "FAILED",
                )
            assertTrue(fakes.notifications.onTransaction(failed) is Resource.Success)
            assertEquals("FAILED", get("/v1/wallet/transfers/$a", payer.app).data()["state"]!!.jsonPrimitive.content)
            assertEquals(1, outbox("wallet.TransferFailed", a), "TransferFailed once")
            // Phase 2F: the window counts transfers by state, so the failed one is out and only the other 500 counts.
            expect("the failed one is out of the window", w4(payer, transferBody(outside, amountOf("500"))), 200, null)
            expect(
                "nothing more came back",
                w4(payer, transferBody(outside, """{"asset":"USDC","amountMinor":"1"}""")),
                400,
                "wallet.limit_exceeded",
            )
        }

    @Test
    fun `Circle's updates move a transfer once, in order, and tell the sender and the recipient`() =
        testApplication {
            val fakes = boot()
            val (payer, payerCircle) = walletOwner(fakes, usdc(50_000_000))
            val (payee, payeeCircle) = walletOwner(fakes)
            val (third, _) = walletOwner(fakes)
            meet(payer, payee)
            val money = com.circaso.shared.Money(2_500_000, com.circaso.shared.Asset.USDC)
            val prepared = w4(payer, transferBody(toUser(payee), amountOf("2.5")))
            val id = prepared.data()["transferId"]!!.jsonPrimitive.content
            val payerWallet = walletOf(fakes, payerCircle)
            val payeeWallet = walletOf(fakes, payeeCircle)
            val notifications = fakes.notifications

            fun out(
                state: CustodyTransactionState,
                wallet: com.circaso.integrations.api.CustodyWallet = payerWallet,
                ref: String = id,
                hash: String = "",
            ) = update(
                payerCircle,
                wallet,
                CustodyDirection.OUT,
                state,
                money,
                "tx-1",
                ref,
                destination = payeeWallet.address,
                hash = hash,
            )

            // an update for another wallet's refId moves nothing
            val foreign = out(CustodyTransactionState.CONFIRMED, wallet = payeeWallet)
            assertTrue(notifications.onTransaction(foreign) is Resource.Success)
            assertEquals(
                "AWAITING_SIGNATURE",
                get("/v1/wallet/transfers/$id", payer.app).data()["state"]!!.jsonPrimitive.content,
            )

            assertTrue(notifications.onTransaction(out(CustodyTransactionState.SUBMITTED)) is Resource.Success)
            assertEquals(
                "SUBMITTED",
                get("/v1/wallet/transfers/$id", payer.app).data()["state"]!!.jsonPrimitive.content,
            )
            assertTrue(notifications.onTransaction(out(CustodyTransactionState.SUBMITTED)) is Resource.Success)
            assertTrue(
                notifications.onTransaction(out(CustodyTransactionState.CONFIRMED, hash = "0xabc")) is Resource.Success,
            )
            assertTrue(
                notifications.onTransaction(out(CustodyTransactionState.CONFIRMED, hash = "0xabc")) is Resource.Success,
            )
            // late: SUBMITTED, FAILED after CONFIRMED
            assertTrue(notifications.onTransaction(out(CustodyTransactionState.SUBMITTED)) is Resource.Success)
            assertTrue(notifications.onTransaction(out(CustodyTransactionState.FAILED)) is Resource.Success)
            val shown = get("/v1/wallet/transfers/$id", payer.app).data()
            assertEquals("CONFIRMED", shown["state"]!!.jsonPrimitive.content)
            assertEquals("0xabc", shown["txHash"]!!.jsonPrimitive.content)
            assertEquals("APP", shown["origin"]!!.jsonPrimitive.content)
            assertEquals("OUT", shown["direction"]!!.jsonPrimitive.content)
            assertEquals(1, outbox("wallet.TransferConfirmed", id))
            assertEquals(0, outbox("wallet.TransferFailed", id))
            val states = frames(payer.userId).filter { it.type() == "transfer.updated" }.map { it.field("state") }
            assertEquals(
                listOf("SUBMITTED", "CONFIRMED"),
                states,
                "one frame per move, none for duplicates or late ones",
            )

            // the pushes carry no amount
            val payerPushes = fakes.push.sent.filter { it.target.token == payer.push }
            println("payer pushes: ${payerPushes.map { it.message.title + " | " + it.message.body }}")
            assertTrue(payerPushes.isNotEmpty(), "Payment sent push")
            assertTrue(
                payerPushes.none {
                    "2.5" in it.message.body || "2500000" in it.message.body ||
                        "USDC" in it.message.body
                },
            )

            // W5: the sender, the payee, a third person
            val asPayee = get("/v1/wallet/transfers/$id", payee.app)
            expect("payee sees it", asPayee, 200, null)
            assertEquals("IN", asPayee.data()["direction"]!!.jsonPrimitive.content)
            val payeeJson = asPayee.data()
            assertTrue(
                payeeJson["origin"] == null || payeeJson["origin"] is kotlinx.serialization.json.JsonNull,
                payeeJson.toString(),
            )
            assertTrue(
                payeeJson["proposalId"] == null || payeeJson["proposalId"] is kotlinx.serialization.json.JsonNull,
            )
            expect("a third person", get("/v1/wallet/transfers/$id", third.app), 404, "wallet.transfer_not_found")
            expect(
                "an unknown id",
                get("/v1/wallet/transfers/${UUID.randomUUID()}", payer.app),
                404,
                "wallet.transfer_not_found",
            )
            val badId = get("/v1/wallet/transfers/abc", payer.app)
            println("W5 with an id that is not a uuid: ${badId.status} ${badId.bodyAsText()}")

            // the money arrives: submitted says nothing, confirmed says it once, naming the sender
            fun inbound(state: CustodyTransactionState, source: String = payerWallet.address) =
                update(
                    payeeCircle,
                    payeeWallet,
                    CustodyDirection.IN,
                    state,
                    money,
                    "tx-1",
                    source = source,
                    destination = payeeWallet.address,
                )
            assertTrue(notifications.onTransaction(inbound(CustodyTransactionState.SUBMITTED)) is Resource.Success)
            assertTrue(frames(payee.userId).none { it.type() == "payment.received" })
            assertTrue(notifications.onTransaction(inbound(CustodyTransactionState.CONFIRMED)) is Resource.Success)
            assertTrue(notifications.onTransaction(inbound(CustodyTransactionState.CONFIRMED)) is Resource.Success)
            val received = frames(payee.userId).filter { it.type() == "payment.received" }
            assertEquals(1, received.size, received.toString())
            assertEquals(payer.userId, received.single().field("fromUserId"))
            println("payment.received: ${received.single()}")
            assertNotNull(received.single().field("fromName"))
            val payeePushes = fakes.push.sent.filter { it.target.token == payee.push }
            println("payee pushes: ${payeePushes.map { it.message.title + " | " + it.message.body }}")
            assertTrue(
                payeePushes.none {
                    "2.5" in it.message.body || "2500000" in it.message.body ||
                        "USDC" in it.message.body
                },
            )
            // from an outside address
            val outsider =
                update(
                    payeeCircle,
                    payeeWallet,
                    CustodyDirection.IN,
                    CustodyTransactionState.CONFIRMED,
                    money,
                    "tx-2",
                    source = "0x0123456789abcdef0123456789abcdef01234567",
                )
            assertTrue(fakes.notifications.onTransaction(outsider) is Resource.Success)
            val last = frames(payee.userId).last { it.type() == "payment.received" }
            assertNull(last.field("fromUserId"))
            assertNull(last.field("fromName"))
            // an outgoing one with no refId of ours is only recorded
            val untracked =
                update(
                    payeeCircle,
                    payeeWallet,
                    CustodyDirection.OUT,
                    CustodyTransactionState.CONFIRMED,
                    money,
                    "tx-3",
                    destination = "0x0123456789abcdef0123456789abcdef01234567",
                )
            assertTrue(fakes.notifications.onTransaction(untracked) is Resource.Success)
            // an unknown wallet is acknowledged
            val unknownWallet = payeeWallet.copy(walletId = UUID.randomUUID().toString())
            val unknown =
                update(
                    "not-a-user",
                    unknownWallet,
                    CustodyDirection.IN,
                    CustodyTransactionState.CONFIRMED,
                    money,
                    "tx-4",
                )
            assertTrue(fakes.notifications.onTransaction(unknown) is Resource.Success)
        }

    @Test
    fun `a wallet first seen through a payment turns active with wallet ready once`() =
        testApplication {
            val fakes = boot()
            val payee = signUp(fakes.sms)
            val before = fakes.custody.users.toSet()
            send("POST", "/v1/wallet", payee.app, "{}")
            val circleUser = (fakes.custody.users - before).single()
            val wallet = fakes.custody.completeSetup(circleUser)
            val money = com.circaso.shared.Money(1_000_000, com.circaso.shared.Asset.USDC)
            val inbound =
                update(
                    circleUser,
                    wallet,
                    CustodyDirection.IN,
                    CustodyTransactionState.CONFIRMED,
                    money,
                    "tx-w",
                    source = "0x0123456789abcdef0123456789abcdef01234567",
                )
            assertTrue(fakes.notifications.onTransaction(inbound) is Resource.Success)
            assertTrue(fakes.notifications.onTransaction(inbound) is Resource.Success)
            assertEquals("ACTIVE", get("/v1/wallet", payee.app).data()["status"]!!.jsonPrimitive.content)
            val types = frames(payee.userId).map { it.type() }
            println("frames: $types")
            assertEquals(1, types.count { it == "wallet.ready" }, types.toString())
            assertEquals(1, types.count { it == "payment.received" }, types.toString())
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM wallet.outbox WHERE type = 'wallet.WalletCreated' AND payload LIKE ?",
                    "%${payee.userId}%",
                ),
            )
        }

    @Test
    fun `W9 W10 and W11 answer for people, chains and addresses`() =
        testApplication {
            val fakes = boot()
            val (payer, _) = walletOwner(fakes)
            val (payee, _) = walletOwner(fakes)
            val (stranger, _) = walletOwner(fakes)
            val (hub, _) = walletOwner(fakes)
            val noWallet = signUp(fakes.sms)
            val blocker = signUp(fakes.sms)
            meet(payer, payee)
            meet(payer, noWallet)
            meet(payer, blocker)
            meet(hub, payer)
            meet(hub, stranger)

            val ok = get("/v1/wallet/recipients/${payee.userId}", payer.app)
            expect("can receive", ok, 200, null)
            assertEquals("CAN_RECEIVE", ok.data()["status"]!!.jsonPrimitive.content)
            assertEquals("true", ok.data()["canReceive"]!!.jsonPrimitive.content)
            assertEquals(listOf(base), ok.data()["blockchains"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                "CAN_RECEIVE",
                get(
                    "/v1/wallet/recipients/${payee.userId}?blockchain=$base",
                    payer.app,
                ).data()["status"]!!.jsonPrimitive.content,
            )
            val other = get("/v1/wallet/recipients/${payee.userId}?blockchain=SOL-DEVNET", payer.app).data()
            assertEquals("NO_WALLET_ON_CHAIN", other["status"]!!.jsonPrimitive.content)
            assertEquals("false", other["canReceive"]!!.jsonPrimitive.content)
            assertEquals(listOf(base), other["blockchains"]!!.jsonArray.map { it.jsonPrimitive.content })
            val none = get("/v1/wallet/recipients/${noWallet.userId}", payer.app).data()
            assertEquals("NO_WALLET", none["status"]!!.jsonPrimitive.content)
            assertTrue(none["blockchains"]!!.jsonArray.isEmpty())
            expect(
                "bogus chain",
                get("/v1/wallet/recipients/${payee.userId}?blockchain=DOGE", payer.app),
                400,
                "wallet.chain_unsupported",
            )
            expect("yourself", get("/v1/wallet/recipients/${payer.userId}", payer.app), 400, "wallet.self_transfer")
            expect(
                "not reachable",
                get("/v1/wallet/recipients/${stranger.userId}", payer.app),
                403,
                "users.not_reachable",
            )
            expect("unknown", get("/v1/wallet/recipients/${UUID.randomUUID()}", payer.app), 403, "users.not_reachable")
            val badUser = get("/v1/wallet/recipients/abc", payer.app)
            println("W9 with a user id that is not a uuid: ${badUser.status} ${badUser.bodyAsText()}")
            val badChat = get("/v1/wallet/recipients/${payee.userId}?chatId=x", payer.app)
            println("W9 with a chatId that is not a uuid: ${badChat.status} ${badChat.bodyAsText()}")

            // a chat both are in stands in for reach, a block overrides the chat
            val group =
                send(
                    "POST",
                    "/v1/chats/groups",
                    hub.app,
                    """{"sealedMeta":"c2VhbGVk","memberIds":["${payer.userId}","${stranger.userId}"]}""",
                )
            expect("group", group, 200, null)
            val chatId =
                group
                    .data()
                    .let { it["chatId"] ?: it["id"] }!!
                    .jsonPrimitive.content
            val viaChat = get("/v1/wallet/recipients/${stranger.userId}?chatId=$chatId", payer.app)
            expect("reach through the chat", viaChat, 200, null)
            assertEquals("CAN_RECEIVE", viaChat.data()["status"]!!.jsonPrimitive.content)
            expect(
                "another chat id",
                get("/v1/wallet/recipients/${stranger.userId}?chatId=${UUID.randomUUID()}", payer.app),
                403,
                "users.not_reachable",
            )
            val viaChatW4 =
                w4(payer, transferBody(toUser(stranger), amountOf("0.5"), extra = ""","chatId":"$chatId""""))
            println("W4 through the shared chat with no balance: ${viaChatW4.status} ${viaChatW4.bodyAsText()}")
            // the payee blocks the payer
            send("PUT", "/v1/blocks/${payer.userId}", blocker.app, "{}")
            val blocked = get("/v1/wallet/recipients/${blocker.userId}", payer.app)
            println("W9 to someone who blocked me: ${blocked.status} ${blocked.bodyAsText()}")

            // W10
            val first = send("POST", "/v1/wallet/recipients/${noWallet.userId}/remind", payer.app, "{}")
            expect("remind", first, 200, null)
            assertEquals("SENT", first.data()["status"]!!.jsonPrimitive.content)
            val repeat = send("POST", "/v1/wallet/recipients/${noWallet.userId}/remind", payer.app, "{}")
            assertEquals("ALREADY_SENT", repeat.data()["status"]!!.jsonPrimitive.content)
            assertEquals(
                "HAS_WALLET",
                send(
                    "POST",
                    "/v1/wallet/recipients/${payee.userId}/remind",
                    payer.app,
                    "{}",
                ).data()["status"]!!.jsonPrimitive.content,
            )
            expect(
                "remind a stranger",
                send("POST", "/v1/wallet/recipients/${stranger.userId}/remind", payer.app, "{}"),
                403,
                "users.not_reachable",
            )
            val self = send("POST", "/v1/wallet/recipients/${payer.userId}/remind", payer.app, "{}")
            println("W10 to yourself: ${self.status} ${self.bodyAsText()}")
            val setup = frames(noWallet.userId).filter { it.type() == "setup.requested" }
            assertEquals(1, setup.size, setup.toString())
            assertEquals(payer.userId, setup.single().field("fromUserId"))
            val pushes = fakes.push.sent.filter { it.target.token == noWallet.push }
            println("setup pushes: ${pushes.map { it.message.title + " | " + it.message.body }}")
            assertEquals(1, pushes.size)
            // a second person reminding the same contact is not rate-limited by the first
            val blockerRemind = send("POST", "/v1/wallet/recipients/${noWallet.userId}/remind", hub.app, "{}")
            println("W10 by someone who cannot reach: ${blockerRemind.status} ${blockerRemind.code()}")

            // W11
            suspend fun validate(chain: String, address: String) =
                send(
                    "POST",
                    "/v1/wallet/addresses/validate",
                    payer.app,
                    """{"blockchain":"$chain","address":"$address"}""",
                )
            val evm = validate(base, "0xAbCdEf0123456789aBcDeF0123456789AbCdEf01").data()
            assertEquals("true", evm["valid"]!!.jsonPrimitive.content)
            assertEquals("0xabcdef0123456789abcdef0123456789abcdef01", evm["address"]!!.jsonPrimitive.content)
            assertEquals("false", validate(base, "0x123").data()["valid"]!!.jsonPrimitive.content)
            val empty = validate(base, "")
            println("W11 with an empty address: ${empty.status} ${empty.bodyAsText()}")
            assertEquals(
                "false",
                validate(base, "0xZZcdef0123456789aBcDeF0123456789AbCdEf01").data()["valid"]!!.jsonPrimitive.content,
            )
            val sol = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
            val solResponse = validate("SOL-DEVNET", sol).data()
            assertEquals("true", solResponse["valid"]!!.jsonPrimitive.content)
            assertEquals(sol, solResponse["address"]!!.jsonPrimitive.content, "Solana exactly as given")
            assertEquals(
                "false",
                validate(
                    "SOL-DEVNET",
                    "0xabcdef0123456789abcdef0123456789abcdef01",
                ).data()["valid"]!!.jsonPrimitive.content,
            )
            val short = validate("SOL-DEVNET", "0OIl")
            println("W11 short Solana address: ${short.status} ${short.bodyAsText()}")
            assertEquals("false", validate(base, sol).data()["valid"]!!.jsonPrimitive.content)
            expect("unsupported chain", validate("DOGE", sol), 400, "wallet.chain_unsupported")
            val padded = validate(base, " 0xabcdef0123456789abcdef0123456789abcdef01 ")
            println("W11 with spaces around: ${padded.status} ${padded.bodyAsText()}")
            val longAddress = validate(base, "0x" + "a".repeat(5000))
            println("W11 with a 5000 character address: ${longAddress.status}")
            fakes.custody.failure = IntegrationError.Unavailable("Circle")
            expect("Circle down", validate(base, sol), 502, "wallet.custody_unavailable")
        }

    @Test
    fun `the new routes need an app token`() =
        testApplication {
            val fakes = boot()
            val person = signUp(fakes.sms)
            val id = UUID.randomUUID()
            val routes =
                listOf(
                    "GET" to "/v1/wallet/transfers/$id",
                    "GET" to "/v1/wallet/recipients/$id",
                    "POST" to "/v1/wallet/recipients/$id/remind",
                    "POST" to "/v1/wallet/addresses/validate",
                    "POST" to "/v1/wallet/transfers",
                )
            val agent = person.agent
            for ((method, path) in routes) {
                for ((label, token, status) in listOf(
                    Triple("none", "", 401),
                    Triple("agent", agent, 403),
                    Triple("garbage", "abc", 401),
                )) {
                    val response =
                        if (method == "GET") {
                            client.get(path) {
                                if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                                header("X-Client-Version", "android/1.0.0")
                            }
                        } else {
                            client.post(path) {
                                if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                                header("Idempotency-Key", UUID.randomUUID().toString())
                                header("X-Client-Version", "android/1.0.0")
                                contentType(ContentType.Application.Json)
                                setBody("{}")
                            }
                        }
                    assertEquals(
                        status,
                        response.status.value,
                        "$method $path with $label token: ${response.bodyAsText()}",
                    )
                }
            }
            assertNotNull(person)
        }

    @Test
    fun `0_009 USDC on each of four chains blocks deletion, on one chain it passes`() =
        testApplication {
            val fakes = boot()
            val (person, circleUser) = walletOwner(fakes, usdc(9_000))
            for (chain in listOf("MATIC-AMOY", "ETH-SEPOLIA", "SOL-DEVNET")) {
                assertEquals(
                    "ADDING",
                    send(
                        "POST",
                        "/v1/wallet/chains",
                        person.app,
                        """{"blockchain":"$chain"}""",
                    ).data()["status"]!!.jsonPrimitive.content,
                )
                val wallet = fakes.custody.completeSetup(circleUser, blockchain = chain)
                fakes.custody.assetBalances[wallet.walletId] = mapOf("USDC" to usdc(9_000))
            }
            val refused = send("DELETE", "/v1/account", person.app)
            expect("0.036 over four chains", refused, 409, "wallet.funds_remaining")
            // three chains emptied: 0.009 left on one
            for (chain in listOf("MATIC-AMOY", "ETH-SEPOLIA")) {
                fakes.custody.assetBalances[walletOf(fakes, circleUser, chain).walletId] = mapOf("USDC" to usdc(0))
            }
            expect("0.018 over two chains", send("DELETE", "/v1/account", person.app), 409, "wallet.funds_remaining")
            fakes.custody.assetBalances[walletOf(fakes, circleUser, "SOL-DEVNET").walletId] = mapOf("USDC" to usdc(0))
            expect("0.009 on one chain", send("DELETE", "/v1/account", person.app), 200, null)
        }

    @Test
    fun `parallel W4 with one key makes one transfer and parallel W12 for one chain all answer ADDING`() =
        testApplication {
            val fakes = boot()
            val (payer, _) = walletOwner(fakes, usdc(50_000_000))
            val body = transferBody("""{"address":"0x0123456789abcdef0123456789abcdef01234567"}""", amountOf("10"))
            val key = UUID.randomUUID().toString()
            val results = coroutineScope { (1..12).map { async { w4(payer, body, key) } }.awaitAll() }
            val summary =
                results.map {
                    it.status.value to
                        (it.code() ?: it.data()["transferId"]!!.jsonPrimitive.content)
                }
            println("parallel W4: $summary")
            assertEquals(1, results.count { it.status == HttpStatusCode.OK }, summary.toString())
            assertTrue(
                results.filter { it.status != HttpStatusCode.OK }.all { it.code() == "request.in_progress" },
                summary.toString(),
            )
            val replay = w4(payer, body, key)
            assertEquals(
                results.first { it.status == HttpStatusCode.OK }.data()["transferId"],
                replay.data()["transferId"],
            )
            assertEquals(1, count("SELECT count(*) FROM wallet.transfers WHERE sender_id = ?::uuid", payer.userId))
            // the day's limit took the amount once
            val outside = """{"address":"0x0123456789abcdef0123456789abcdef01234567"}"""
            expect(
                "490 fits, 491 does not",
                w4(payer, transferBody(outside, amountOf("490.000001"))),
                400,
                "wallet.insufficient_funds",
            )
            val chains =
                coroutineScope {
                    (1..8)
                        .map {
                            async {
                                send(
                                    "POST",
                                    "/v1/wallet/chains",
                                    payer.app,
                                    """{"blockchain":"SOL-DEVNET"}""",
                                )
                            }
                        }.awaitAll()
                }
            val ids = chains.map { it.data()["challengeId"]?.jsonPrimitive?.content }
            println("parallel W12: $ids")
            // FakeCustody ignores Circle's idempotency key, so the shared challenge is checked in AddChain's code only
        }

    @Test
    fun `a chain retired from receiving pays nobody inside Circaso but can still send out`() =
        testApplication {
            val fakes = boot(mapOf("INTEGRATIONS_CUSTODY_CHAIN_ETH_SEPOLIA_RECEIVE_DISABLED" to "true"))
            val (payer, payerCircle) = walletOwner(fakes)
            val (payee, payeeCircle) = walletOwner(fakes)
            meet(payer, payee)
            val money = com.circaso.shared.Money(1_000_000, com.circaso.shared.Asset.USDC)
            val chain = "ETH-SEPOLIA"
            val payerEth = fakes.custody.completeSetup(payerCircle, blockchain = chain)
            val payeeEth = fakes.custody.completeSetup(payeeCircle, blockchain = chain)
            fakes.custody.assetBalances[payerEth.walletId] = mapOf("USDC" to usdc(20_000_000))
            // Circle tells us about both wallets
            for ((user, wallet) in listOf(payerCircle to payerEth, payeeCircle to payeeEth)) {
                fakes.notifications.onTransaction(
                    update(
                        user,
                        wallet,
                        CustodyDirection.IN,
                        CustodyTransactionState.CONFIRMED,
                        money,
                        "tx-$user-$chain",
                        source = "0x0123456789abcdef0123456789abcdef01234567",
                    ),
                )
            }
            val w1 = get("/v1/wallet", payer.app).data()["chains"]!!.jsonArray.map { it.jsonObject }
            println("W1 chains with a retired one: $w1")
            expect("W12 add", send("POST", "/v1/wallet/chains", payee.app, """{"blockchain":"$chain"}"""), 200, null)
            val (newcomer, _) = walletOwner(fakes)
            expect(
                "W12 add a retired chain to a wallet that is not on it",
                send("POST", "/v1/wallet/chains", newcomer.app, """{"blockchain":"$chain"}"""),
                400,
                "wallet.chain_unsupported",
            )
            println("W12 list of a wallet not on the retired chain: ${get("/v1/wallet/chains", newcomer.app).data()}")
            val list = get("/v1/wallet/chains", payer.app).data()
            println("W12 list: $list")
            val w9 = get("/v1/wallet/recipients/${payee.userId}?blockchain=$chain", payer.app).data()
            assertEquals("NO_WALLET_ON_CHAIN", w9["status"]!!.jsonPrimitive.content, w9.toString())
            expect(
                "W4 to a person on it",
                w4(payer, transferBody(toUser(payee), amountOf("1"), chain)),
                409,
                "wallet.recipient_not_on_chain",
            )
            expect(
                "W4 to their address on it",
                w4(payer, transferBody("""{"address":"${payeeEth.address}"}""", amountOf("1"), chain)),
                409,
                "wallet.recipient_not_on_chain",
            )
            expect(
                "W4 out to an outside address",
                w4(
                    payer,
                    transferBody("""{"address":"0x0123456789abcdef0123456789abcdef01234567"}""", amountOf("1"), chain),
                ),
                200,
                null,
            )
        }

    @Test
    fun `a suspended account cannot prepare a transfer`() =
        testApplication {
            val fakes = boot()
            val (payer, _) = walletOwner(fakes, usdc(50_000_000))
            TestPostgres.superuser {
                it.createStatement().use { statement ->
                    statement.execute(
                        "UPDATE identity.accounts SET status = 'SUSPENDED' WHERE user_id = '${payer.userId}'",
                    )
                }
            }
            val response =
                w4(payer, transferBody("""{"address":"0x0123456789abcdef0123456789abcdef01234567"}""", amountOf("1")))
            println("W4 from a suspended account: ${response.status} ${response.bodyAsText()}")
            assertTrue(
                response.code() in
                    setOf("wallet.account_restricted", "auth.token_invalid", "identity.account_suspended"),
            )
        }

    @Test
    fun `a block overrides the shared chat for W9 and W4`() =
        testApplication {
            val fakes = boot()
            val (payer, _) = walletOwner(fakes, usdc(50_000_000))
            val (stranger, _) = walletOwner(fakes)
            val (hub, _) = walletOwner(fakes)
            meet(hub, payer)
            meet(hub, stranger)
            val group =
                send(
                    "POST",
                    "/v1/chats/groups",
                    hub.app,
                    """{"sealedMeta":"c2VhbGVk","memberIds":["${payer.userId}","${stranger.userId}"]}""",
                )
            val chatId =
                group
                    .data()
                    .let { it["chatId"] ?: it["id"] }!!
                    .jsonPrimitive.content
            val withChat = ""","chatId":"$chatId""""
            expect(
                "through the chat",
                w4(payer, transferBody(toUser(stranger), amountOf("1"), extra = withChat)),
                200,
                null,
            )
            expect(
                "without the chat",
                w4(payer, transferBody(toUser(stranger), amountOf("1"))),
                403,
                "users.not_reachable",
            )
            expect(
                "W9 through the chat",
                get("/v1/wallet/recipients/${stranger.userId}?chatId=$chatId", payer.app),
                200,
                null,
            )
            // the stranger blocks the payer
            expect("block", send("PUT", "/v1/blocks/${payer.userId}", stranger.app, "{}"), 200, null)
            expect(
                "W9 blocked",
                get("/v1/wallet/recipients/${stranger.userId}?chatId=$chatId", payer.app),
                403,
                "users.not_reachable",
            )
            expect(
                "W4 blocked",
                w4(payer, transferBody(toUser(stranger), amountOf("1"), extra = withChat)),
                403,
                "users.not_reachable",
            )
            expect(
                "W10 blocked",
                send("POST", "/v1/wallet/recipients/${stranger.userId}/remind", payer.app, "{}"),
                403,
                "users.not_reachable",
            )
            // and the other way
            expect(
                "W9 by the blocker",
                get("/v1/wallet/recipients/${payer.userId}?chatId=$chatId", stranger.app),
                403,
                "users.not_reachable",
            )
        }

    @Test
    fun `wallet frames arrive live, are replayed after a reconnect and are not pushed to an open socket`() =
        testApplication {
            val fakes = boot()
            val (payer, payerCircle) = walletOwner(fakes, usdc(50_000_000))
            val wsClient = createClient { install(WebSockets) }
            val money = com.circaso.shared.Money(1_000_000, com.circaso.shared.Asset.USDC)
            val outside = """{"address":"0x0123456789abcdef0123456789abcdef01234567"}"""
            val wallet = walletOf(fakes, payerCircle)

            fun out(id: String, state: CustodyTransactionState) =
                update(payerCircle, wallet, CustodyDirection.OUT, state, money, "tx-$id", refId = id)

            val first = w4(payer, transferBody(outside, amountOf("1"))).data()["transferId"]!!.jsonPrimitive.content
            fakes.notifications.onTransaction(out(first, CustodyTransactionState.SUBMITTED))
            fakes.notifications.onTransaction(out(first, CustodyTransactionState.CONFIRMED))

            suspend fun connect(last: String, whileOpen: suspend () -> Unit = {}): List<JsonObject> {
                val seen = mutableListOf<JsonObject>()
                wsClient.webSocket("/v1/ws") {
                    send(Frame.Text("""{"type":"auth","accessToken":"${payer.app}","last":$last}"""))
                    withTimeoutOrNull(1500) {
                        for (frame in incoming) if (frame is Frame.Text) seen += json(frame.readText())
                    }
                    whileOpen()
                    withTimeoutOrNull(1500) {
                        for (frame in incoming) if (frame is Frame.Text) seen += json(frame.readText())
                    }
                }
                return seen
            }

            val replayed =
                connect("""[{"module":"wallet","seq":0}]""").filter {
                    it["module"]?.jsonPrimitive?.content ==
                        "wallet"
                }
            println("replayed: ${replayed.map { it["type"]!!.jsonPrimitive.content + "#" + it["seq"] }}")
            assertEquals(listOf("wallet.ready", "transfer.updated", "transfer.updated"), replayed.map { it.type() })
            val seqs = replayed.map { it["seq"]!!.jsonPrimitive.content.toLong() }
            assertEquals(seqs.sorted(), seqs)
            assertEquals(seqs.size, seqs.toSet().size)
            // from the first frame's seq on, only the later one comes
            val afterFirst =
                connect("""[{"module":"wallet","seq":${seqs[1]}}]""").filter {
                    it["module"]?.jsonPrimitive?.content ==
                        "wallet"
                }
            assertEquals(1, afterFirst.size, afterFirst.toString())
            assertEquals("CONFIRMED", afterFirst.single().field("state"))
            // nothing after the last
            assertTrue(
                connect("""[{"module":"wallet","seq":${seqs.last()}}]""").none {
                    it["module"]?.jsonPrimitive?.content ==
                        "wallet"
                },
            )

            // live, with the socket open: the frame arrives and no push goes out
            val second = w4(payer, transferBody(outside, amountOf("1"))).data()["transferId"]!!.jsonPrimitive.content
            val pushesBefore = fakes.push.sent.count { it.target.token == payer.push }
            val live =
                connect("""[{"module":"wallet","seq":${seqs.last()}}]""") {
                    fakes.notifications.onTransaction(out(second, CustodyTransactionState.CONFIRMED))
                }.filter { it["module"]?.jsonPrimitive?.content == "wallet" }
            println("live: $live")
            assertEquals(1, live.size)
            assertEquals(
                pushesBefore,
                fakes.push.sent.count { it.target.token == payer.push },
                "no push to an open socket",
            )
            // and the same frame is in the replay for whoever was away
            assertEquals(
                1,
                connect("""[{"module":"wallet","seq":${seqs.last()}}]""").count {
                    it["module"]?.jsonPrimitive?.content ==
                        "wallet"
                },
            )
        }
}
