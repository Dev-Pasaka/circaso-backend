package com.circaso.app

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.impl.dev.FakeSms
import com.circaso.shared.Uuid7
import io.kandra.test.KandraTestUtils
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.runTestApplication
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.koinApplication
import org.koin.ktor.ext.getKoin
import org.testcontainers.containers.GenericContainer
import org.testcontainers.scylladb.ScyllaDBContainer
import org.testcontainers.utility.DockerImageName
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `serve` booted for real: Koin, infrastructure, the gateway and identity, against a real Redis, ScyllaDB and
 * PostgreSQL (infrastructure's test container, started with the server's own init scripts, and migrated first).
 */
class ServeTest {
    private companion object {
        val redis: GenericContainer<*> =
            GenericContainer(
                DockerImageName.parse("valkey/valkey:8-alpine"),
            ).withExposedPorts(6379).also {
                it.start()
            }
        val scylla: ScyllaDBContainer =
            ScyllaDBContainer(
                DockerImageName.parse("scylladb/scylla:2026.1"),
            ).also { it.start() }

        init {
            // serve never migrates: as the deploy's Migrate step does, every module's Flyway scripts run first
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
            "SCYLLA_CONTACT_POINTS" to "${scylla.contactPoint.hostString}:${scylla.contactPoint.port}",
            "SCYLLA_DATACENTER" to "datacenter1",
            "SCYLLA_TLS" to "false",
            "SCYLLA_CREATE_SCHEMA" to "true",
            "INTEGRATIONS_SMS_PROVIDER" to "fake",
        ) + TestPostgres.env().filterKeys { it.startsWith("DATABASE_") } + extra

    @AfterTest
    fun resetKandra() =
        KandraTestUtils
            .reset()

    @Test
    fun `serve starts and reports PostgreSQL, Redis, ScyllaDB and the stream consumers ready`() =
        testApplication {
            application { circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env())) }

            val response = client.get("/health/ready")
            val checks = Json.decodeFromString<HealthReport>(response.bodyAsText()).checks

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                listOf("postgres", "redis", "scylla", "streams").map { HealthCheckStatus(it, "up") },
                checks.sortedBy { it.name },
            )
        }

    @Test
    fun `serve answers in the envelope, with a request id`() =
        testApplication {
            application { circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env())) }

            val body = Json.parseToJsonElement(client.get("/nope").bodyAsText()).jsonObject

            assertEquals("request.not_found", body["code"]!!.jsonPrimitive.content)
            assertEquals(32, body["requestId"]!!.jsonPrimitive.content.length)
        }

    @Test
    fun `migrate applies each module's migrations once and a second run applies none`() {
        val keyspaceEnv = env(mapOf("SCYLLA_CONTACT_POINTS" to env()["SCYLLA_CONTACT_POINTS"]!!))
        val first = migrate(EnvConfig(keyspaceEnv), EnvSecrets(keyspaceEnv))
        assertTrue(first >= 0)
        assertEquals(
            0,
            migrate(EnvConfig(keyspaceEnv), EnvSecrets(keyspaceEnv)),
            "migrations already applied are not run again",
        )
    }

    @Test
    fun `identity's routes sit under v1 behind the gateway's rules`() =
        testApplication {
            application { circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env())) }

            // a scoped route with no token
            val noToken = client.get("/v1/account/security")
            assertEquals(HttpStatusCode.Unauthorized, noToken.status)
            assertEquals("auth.token_missing", json(noToken)["code"]!!.jsonPrimitive.content)

            // a state change with no idempotency key
            val noKey = client.post("/v1/auth/nonce")
            assertEquals(HttpStatusCode.BadRequest, noKey.status)

            // the public keys
            val jwks = client.get("/v1/.well-known/jwks.json")
            assertEquals(HttpStatusCode.OK, jwks.status)
            assertEquals(
                "Ed25519",
                json(jwks)["data"]!!.jsonObject["keys"]!!.toString().let {
                    if ("Ed25519" in
                        it
                    ) {
                        "Ed25519"
                    } else {
                        it
                    }
                },
            )
        }

    @Test
    fun `sign up over HTTP from Google to an active session, then the security overview`() =
        testApplication {
            var sms: FakeSms? = null
            application {
                circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
                sms = getKoin().get<SmsGateway>() as FakeSms
            }
            val deviceId =
                UUID.randomUUID().let {
                    Uuid7
                        .generate()
                        .toString()
                }
            val number = "+254712${(100000..999999).random()}"
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

            suspend fun HttpClient.call(
                path: String,
                body: String,
                token: String? = null,
            ): HttpResponse =
                post(path) {
                    contentType(ContentType.Application.Json)
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    header("X-Client-Version", "android/1.0.0")
                    token?.let { header("Authorization", "Bearer $it") }
                    setBody(body)
                }

            val nonce = json(client.call("/v1/auth/nonce", "{}"))["data"]!!.jsonObject["nonce"]!!.jsonPrimitive.content

            val signIn =
                json(
                    client.call(
                        "/v1/auth/social",
                        """{"provider":"google","idToken":"dev:amina-${UUID.randomUUID()}:amina@example.com:Amina Otieno","nonce":"$nonce",
                    "device":{"deviceId":"$deviceId","platform":"android","model":"Pixel","appVersion":"1.0.0"}}""",
                    ),
                )["data"]!!.jsonObject
            assertEquals("VERIFY_PHONE", signIn["next"]!!.jsonPrimitive.content)
            val onboarding = signIn["session"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content

            // identity.md's own code for a number that isn't E.164, not a generic request.malformed
            val badNumber = client.call("/v1/auth/phone/start", """{"phone":"0712 345 678"}""", onboarding)
            assertEquals(HttpStatusCode.BadRequest, badNumber.status)
            assertEquals("identity.phone_invalid", json(badNumber)["code"]!!.jsonPrimitive.content)

            val started =
                json(
                    client.call("/v1/auth/phone/start", """{"phone":"$number"}""", onboarding),
                )["data"]!!.jsonObject
            val code = Regex("[0-9]{6}").find(sms!!.sent.last().text)!!.value
            val verified =
                json(
                    client.call(
                        "/v1/auth/phone/verify",
                        """{"verificationId":"${started["verificationId"]!!.jsonPrimitive.content}","code":"$code"}""",
                        onboarding,
                    ),
                )["data"]!!.jsonObject
            assertEquals("REGISTER_DEVICE", verified["next"]!!.jsonPrimitive.content)

            val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
            val registered =
                client.call(
                    "/v1/devices",
                    """{"deviceId":"$deviceId","publicKey":"$publicKey","keyAlgorithm":"ES256","pushToken":"fcm:abc","model":"Pixel"}""",
                    onboarding,
                )
            assertEquals(HttpStatusCode.OK, registered.status, registered.bodyAsText())
            val data = json(registered)["data"]!!.jsonObject
            assertEquals("ACTIVE", data["user"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            val app = data["session"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content
            val agent = data["agentSession"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content
            assertNotNull(data["session"]!!.jsonObject["refreshToken"])

            val overview = client.get("/v1/account/security") { header("Authorization", "Bearer $app") }
            assertEquals(HttpStatusCode.OK, overview.status, overview.bodyAsText())
            assertEquals(
                "google",
                json(overview)["data"]!!.jsonObject["providers"]!!.toString().let {
                    if ("google" in
                        it
                    ) {
                        "google"
                    } else {
                        it
                    }
                },
            )

            // the onboarding token is not enough for the app routes, and the agent token reaches nothing outside /v1/agent
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .get("/v1/account/security") {
                        header("Authorization", "Bearer $onboarding")
                    }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .get("/v1/account/security") {
                        header("Authorization", "Bearer $agent")
                    }.status,
            )

            // a repeated idempotency key replays the first answer
            val key = UUID.randomUUID().toString()

            suspend fun pushTokenOnce() =
                client.put("/v1/devices/current/push-token") {
                    header("Authorization", "Bearer $app")
                    header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json)
                    setBody("""{"pushToken":"fcm:new"}""")
                }
            val first = pushTokenOnce()
            val second = pushTokenOnce()
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("true", second.headers["Idempotent-Replayed"])

            val signOut =
                client.post("/v1/auth/signout") {
                    header("Authorization", "Bearer $app")
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, signOut.status)

            // and the session is gone for the gateway
            val after = client.get("/v1/account/security") { header("Authorization", "Bearer $app") }
            assertEquals(HttpStatusCode.Unauthorized, after.status)
            assertEquals("identity.session_revoked", json(after)["code"]!!.jsonPrimitive.content)
        }

    @Test
    fun `the spec is organised for the person using it, sent gzipped and never stale`() =
        testApplication {
            application { circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env())) }

            // ---- the spec: sent gzipped, and never stale ----
            val gzipped = client.get("/docs/openapi.json") { header("Accept-Encoding", "gzip") }
            assertEquals("gzip", gzipped.headers["Content-Encoding"], "the spec is compressed")
            val spec = client.get("/docs/openapi.json")
            assertEquals("no-cache", spec.headers["Cache-Control"])
            assertEquals(
                HttpStatusCode.NotModified,
                client
                    .get("/docs/openapi.json") {
                        header("If-None-Match", spec.headers["ETag"]!!)
                    }.status,
                "the reader re-renders only when the spec changed",
            )
            assertEquals(HttpStatusCode.OK, spec.status)
            val doc = Json.parseToJsonElement(spec.bodyAsText()).jsonObject
            val paths = doc["paths"]!!.jsonObject

            // sections: Infrastructure first, then the order a person uses the API
            val groups = doc["x-tagGroups"]!!.jsonArray.map { it.jsonObject }
            assertEquals(
                listOf(
                    "Infrastructure",
                    "Authentication",
                    "Your profile",
                    "People",
                    "Chats",
                    "Asha",
                    "Account",
                    "Assistant tools",
                ),
                groups.map { it["name"]!!.jsonPrimitive.content },
            )
            assertEquals(
                listOf("Health", "Webhooks"),
                groups.first()["tags"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("Sign in", "Phone verification", "Register device"),
                groups[1]["tags"]!!.jsonArray.map { it.jsonPrimitive.content },
                "authentication reads as sign in, then phone, then the device",
            )
            val declared = doc["tags"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(groups.flatMap { g -> g["tags"]!!.jsonArray.map { it.jsonPrimitive.content } }, declared)
            assertTrue(
                doc["tags"]!!.jsonArray.all {
                    it.jsonObject["description"]!!
                        .jsonPrimitive.content.length > 60
                },
                "each section is explained",
            )

            // every route is in a known section, has a summary and a description, and the docs' own routes are not listed
            val operations =
                paths.flatMap { (path, item) ->
                    item.jsonObject.filterValues { it is JsonObject }.map { (method, op) ->
                        Triple(path, method, op.jsonObject)
                    }
                }
            operations.forEach { (path, method, op) ->
                val tag =
                    (
                        op["tags"]
                            ?: error("$method $path is in the spec but has no section: it needs .docs(...) or .hide()")
                    ).jsonArray.single().jsonPrimitive.content
                assertTrue(tag in declared, "$method $path is in a section that exists (was '$tag')")
                assertTrue(op["summary"]!!.jsonPrimitive.content.isNotBlank(), "$method $path has a summary")
            }
            assertTrue(paths.keys.none { it.startsWith("/docs") }, "the reader's own routes are hidden")
            listOf(
                "/health/live",
                "/health/ready",
                "/v1/hooks/{vendor}",
                "/v1/auth/nonce",
                "/v1/auth/social",
                "/v1/auth/phone/start",
                "/v1/auth/phone/verify",
                "/v1/devices",
                "/v1/auth/refresh",
                "/v1/me/profile",
                "/v1/me/handle",
                "/v1/contacts/sync",
                "/v1/connections",
                "/v1/blocks/{userId}",
                "/v1/assistant/chat/messages",
                "/v1/assistant/usage",
                "/v1/account/security",
                "/v1/recovery/requests",
                "/v1/agent/users/contacts",
                "/v1/keys",
                "/v1/keys/one-time",
                "/v1/keys/me",
                "/v1/keys/{userId}",
                "/v1/chats",
                "/v1/chats/direct",
                "/v1/chats/groups",
                "/v1/chats/{chatId}",
                "/v1/chats/{chatId}/members",
                "/v1/chats/{chatId}/members/{userId}",
                "/v1/chats/{chatId}/members/{userId}/role",
            ).forEach { assertTrue(it in paths.keys, "$it is documented") }

            // the journey order: infrastructure, then nonce before social sign-in before the phone before the device
            val order = paths.keys.toList()
            assertTrue(order.indexOf("/health/live") < order.indexOf("/v1/auth/nonce"), "Infrastructure comes first")
            assertTrue(order.indexOf("/v1/auth/nonce") < order.indexOf("/v1/auth/social"))
            assertTrue(order.indexOf("/v1/auth/social") < order.indexOf("/v1/auth/phone/start"))
            assertTrue(order.indexOf("/v1/auth/phone/verify") < order.indexOf("/v1/devices"))
            assertTrue(
                order.indexOf("/v1/devices") < order.indexOf("/v1/me/profile"),
                "authentication before the profile",
            )

            // the guide, security and servers
            val info = doc["info"]!!.jsonObject["description"]!!.jsonPrimitive.content
            assertTrue(
                "Quick start" in info && "POST /v1/auth/nonce" in info && "identity.phone_taken" in info &&
                    "Idempotency" in info,
                "the introduction explains how to use the API",
            )
            assertTrue("generator" in info, "and points to the UUID generator")
            assertEquals(
                "bearer",
                doc["components"]!!
                    .jsonObject["securitySchemes"]!!
                    .jsonObject["bearerAuth"]!!
                    .jsonObject["scheme"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(
                operations
                    .first {
                        it.first == "/v1/account/security"
                    }.third["security"]!!
                    .jsonArray
                    .isNotEmpty(),
                "a route that needs a token says so",
            )
            assertTrue(
                operations
                    .first {
                        it.first == "/v1/auth/nonce"
                    }.third["security"]!!
                    .jsonArray
                    .isEmpty(),
                "and one that does not, says that",
            )
            assertTrue(doc["servers"]!!.jsonArray.size >= 2)

            // each state change lists the idempotency header and the errors it can return
            val body = spec.bodyAsText()
            assertTrue(
                "Idempotency-Key" in body && "identity.phone_taken" in body && "users.handle_taken" in body &&
                    "DefaultResponse" in body,
            )
        }

    private class Person(
        val userId: String,
        val app: String,
        val agent: String,
        val pair: KeyPair,
    )

    /** Sign-up over HTTP (Google, SMS code, device key) to an active session; returns the user id and the app token. */
    private suspend fun ApplicationTestBuilder.signUp(
        sms: FakeSms,
        name: String,
        number: String,
    ): Person {
        val deviceId =
            Uuid7
                .generate()
                .toString()
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        suspend fun call(
            path: String,
            body: String,
            token: String? = null,
        ) = client.post(path) {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", UUID.randomUUID().toString())
            header("X-Client-Version", "android/1.0.0")
            token?.let { header("Authorization", "Bearer $it") }
            setBody(body)
        }
        val nonce = json(call("/v1/auth/nonce", "{}"))["data"]!!.jsonObject["nonce"]!!.jsonPrimitive.content
        val onboarding =
            json(
                call(
                    "/v1/auth/social",
                    """{"provider":"google","idToken":"dev:${UUID.randomUUID()}:$name@example.com:$name","nonce":"$nonce",
                    "device":{"deviceId":"$deviceId","platform":"android","model":"Pixel","appVersion":"1.0.0"}}""",
                ),
            )["data"]!!.jsonObject["session"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content
        val started = json(call("/v1/auth/phone/start", """{"phone":"$number"}""", onboarding))["data"]!!.jsonObject
        val code = Regex("[0-9]{6}").find(sms.sent.last().text)!!.value
        call(
            "/v1/auth/phone/verify",
            """{"verificationId":"${started["verificationId"]!!.jsonPrimitive.content}","code":"$code"}""",
            onboarding,
        )
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val registered =
            json(
                call(
                    "/v1/devices",
                    """{"deviceId":"$deviceId","publicKey":"$publicKey","keyAlgorithm":"ES256","model":"Pixel"}""",
                    onboarding,
                ),
            )["data"]!!.jsonObject
        return Person(
            registered["user"]!!.jsonObject["userId"]!!.jsonPrimitive.content,
            registered["session"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content,
            registered["agentSession"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content,
            pair,
        )
    }

    @Test
    fun `two people meet through contact sync, handles and the activation event`() =
        testApplication {
            var sms: FakeSms? = null
            application {
                circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
                sms = getKoin().get<SmsGateway>() as FakeSms
            }
            client.get("/health/live") // start the application
            val brianNumber = "+254733${(100000..999999).random()}"
            val amina = signUp(sms!!, "Amina Otieno", "+254712${(100000..999999).random()}")
            val brian = signUp(sms!!, "Brian Mwangi", brianNumber)

            suspend fun get(path: String, token: String) = client.get(path) { header("Authorization", "Bearer $token") }

            suspend fun send(
                method: HttpMethod,
                path: String,
                token: String,
                body: String = "{}",
            ) = client.request(path) {
                this.method = method
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("Idempotency-Key", UUID.randomUUID().toString())
                setBody(body)
            }

            // The AccountActivated event reaches users through Redis Streams and names the profile from the Google hint.
            var name = ""
            repeat(50) {
                name =
                    json(get("/v1/me/profile", amina.app))["data"]!!.jsonObject["displayName"]!!.jsonPrimitive.content
                if (name != "Circaso user") return@repeat
                kotlinx.coroutines.delay(200)
            }
            assertEquals("Amina Otieno", name, "the profile was created from the activation event")

            // Amina syncs her phone book: Brian is on Circaso, someone else is not.
            val sync =
                send(
                    HttpMethod.Post,
                    "/v1/contacts/sync",
                    amina.app,
                    """{"syncId":"${UUID.randomUUID()}","batch":1,"final":true,"phones":["$brianNumber","+254799000999"]}""",
                )
            assertEquals(HttpStatusCode.OK, sync.status, sync.bodyAsText())
            val matches = json(sync)["data"]!!.jsonObject["matches"]!!.toString()
            assertTrue(brian.userId in matches && "Brian Mwangi" in matches, "Brian is matched with his public profile")
            assertTrue("+254712" !in matches.replace(brianNumber, ""), "only the numbers she sent come back")

            val contacts = json(get("/v1/contacts", amina.app))["data"]!!.jsonObject["contacts"]!!.toString()
            assertTrue(brian.userId in contacts)
            assertEquals(
                "true",
                json(
                    get("/v1/me/reach/${brian.userId}", amina.app),
                )["data"]!!.jsonObject["reachable"]!!.jsonPrimitive.content,
            )
            assertEquals(
                "false",
                json(
                    get("/v1/me/reach/${amina.userId}", brian.app),
                )["data"]!!.jsonObject["reachable"]!!.jsonPrimitive.content,
                "Brian has not saved Amina",
            )
            assertEquals(HttpStatusCode.OK, get("/v1/users/${brian.userId}", amina.app).status)
            assertEquals(HttpStatusCode.Forbidden, get("/v1/users/${amina.userId}", brian.app).status)

            // A handle, and a lookup by it.
            val handle = "amina_${(1000..9999).random()}"
            assertEquals(
                HttpStatusCode.OK,
                send(HttpMethod.Put, "/v1/me/handle", amina.app, """{"handle":"$handle"}""").status,
            )
            val found = get("/v1/users/by-handle/$handle", brian.app)
            assertEquals(HttpStatusCode.OK, found.status)
            assertEquals(amina.userId, json(found)["data"]!!.jsonObject["userId"]!!.jsonPrimitive.content)

            // Brian connects through the handle, then blocks her: the connection no longer reaches.
            assertEquals(
                HttpStatusCode.OK,
                send(
                    HttpMethod.Post,
                    "/v1/connections",
                    brian.app,
                    """{"via":"handle","value":"$handle"}""",
                ).status,
            )
            assertEquals(
                "true",
                json(
                    get("/v1/me/reach/${amina.userId}", brian.app),
                )["data"]!!.jsonObject["reachable"]!!.jsonPrimitive.content,
            )
            send(HttpMethod.Put, "/v1/blocks/${amina.userId}", brian.app)
            assertEquals(
                "false",
                json(
                    get("/v1/me/reach/${amina.userId}", brian.app),
                )["data"]!!.jsonObject["reachable"]!!.jsonPrimitive.content,
            )
            assertEquals(
                "false",
                json(
                    get("/v1/me/reach/${brian.userId}", amina.app),
                )["data"]!!.jsonObject["reachable"]!!.jsonPrimitive.content,
                "a block works both ways",
            )

            // The agent token reads her contacts through its own routes, and cannot write or read anything else.
            val agentContacts = get("/v1/agent/users/contacts", amina.agent)
            assertEquals(HttpStatusCode.OK, agentContacts.status, agentContacts.bodyAsText())
            assertTrue(brian.userId in agentContacts.bodyAsText())
            assertEquals(HttpStatusCode.OK, get("/v1/agent/users/contacts/search?q=brian", amina.agent).status)
            assertEquals(HttpStatusCode.Forbidden, get("/v1/contacts", amina.agent).status)
            assertEquals(
                HttpStatusCode.Forbidden,
                send(HttpMethod.Put, "/v1/me/handle", amina.agent, """{"handle":"x_hack"}""").status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                get("/v1/agent/users/contacts", amina.app).status,
                "the user token does not open the agent routes",
            )
        }

    /** M1 proof headers as the phone sends them: the device key over `timestamp + "\n" + hex(SHA-256(body))`. */
    private fun keyProof(pair: KeyPair, body: String): Map<String, String> {
        val timestamp = Instant.now().toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
        val payload = timestamp + "\n" + digest.joinToString("") { "%02x".format(it) }
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(payload.toByteArray())
                sign()
            }
        return mapOf(
            "X-Device-Timestamp" to timestamp,
            "X-Device-Signature" to Base64.getEncoder().encodeToString(signature),
        )
    }

    @Test
    fun `keys are published device-signed and fetched only by someone who can reach the person`() =
        testApplication {
            var sms: FakeSms? = null
            application {
                circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
                sms = getKoin().get<SmsGateway>() as FakeSms
            }
            client.get("/health/live")
            val amina = signUp(sms!!, "Amina Otieno", "+254712${(100000..999999).random()}")
            val brian = signUp(sms!!, "Brian Mwangi", "+254733${(100000..999999).random()}")

            suspend fun get(path: String, token: String) = client.get(path) { header("Authorization", "Bearer $token") }

            suspend fun send(
                method: HttpMethod,
                path: String,
                token: String,
                body: String = "{}",
                proof: Map<String, String> = emptyMap(),
            ) = client.request(path) {
                this.method = method
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("Idempotency-Key", UUID.randomUUID().toString())
                proof.forEach { (name, value) -> header(name, value) }
                setBody(body)
            }

            suspend fun code(response: HttpResponse) = json(response)["code"]!!.jsonPrimitive.content

            val key = Base64.getEncoder().encodeToString(ByteArray(33) { 5 })
            val oneTime = (1..3).joinToString(",") { """{"keyId":$it,"publicKey":"$key"}""" }
            val keys =
                """{"identityKey":"$key","signedPreKey":{"keyId":7,"publicKey":"$key","signature":"$key"},""" +
                    """"oneTimePreKeys":[$oneTime]}"""

            // an unsigned upload or one signed by another key is refused; one signed by Amina's own phone is taken
            val unsigned = send(HttpMethod.Put, "/v1/keys", amina.app, keys)
            assertEquals(HttpStatusCode.Forbidden, unsigned.status)
            assertEquals("messaging.keys_proof_invalid", code(unsigned))
            // a body that claims to be gzip but isn't is the caller's mistake, not a server error
            val garbled =
                client.request("/v1/keys") {
                    method = HttpMethod.Put
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer ${amina.app}")
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    header("Content-Encoding", "gzip")
                    keyProof(amina.pair, keys).forEach { (name, value) -> header(name, value) }
                    setBody(keys.toByteArray())
                }
            assertEquals(HttpStatusCode.BadRequest, garbled.status, garbled.bodyAsText())
            assertEquals("request.malformed", code(garbled))
            val otherKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val forged = send(HttpMethod.Put, "/v1/keys", amina.app, keys, keyProof(otherKey, keys))
            assertEquals(HttpStatusCode.Forbidden, forged.status)
            assertEquals("messaging.keys_proof_invalid", code(forged))
            val published = send(HttpMethod.Put, "/v1/keys", amina.app, keys, keyProof(amina.pair, keys))
            assertEquals(HttpStatusCode.OK, published.status, published.bodyAsText())
            assertEquals("3", json(published)["data"]!!.jsonObject["oneTimePreKeysLeft"]!!.jsonPrimitive.content)
            val status = get("/v1/keys/me", amina.app)
            assertEquals("3", json(status)["data"]!!.jsonObject["oneTimePreKeysLeft"]!!.jsonPrimitive.content)

            // the agent token can't publish or read keys
            assertEquals(HttpStatusCode.Forbidden, get("/v1/keys/me", amina.agent).status)
            assertEquals(HttpStatusCode.Forbidden, get("/v1/keys/${brian.userId}", amina.agent).status)
            val asAgent = send(HttpMethod.Put, "/v1/keys", amina.agent, keys, keyProof(amina.pair, keys))
            assertEquals(HttpStatusCode.Forbidden, asAgent.status)

            // Brian can't reach Amina yet
            val unreachable = get("/v1/keys/${amina.userId}", brian.app)
            assertEquals(HttpStatusCode.Forbidden, unreachable.status)
            assertEquals("users.not_reachable", code(unreachable))

            // an invite connects them both ways; each fetch takes one prekey until none are left
            val invite = json(send(HttpMethod.Post, "/v1/invites", amina.app))["data"]!!.jsonObject
            val token = invite["token"]!!.jsonPrimitive.content
            val connected =
                send(HttpMethod.Post, "/v1/connections", brian.app, """{"via":"invite","value":"$token"}""")
            assertEquals(HttpStatusCode.OK, connected.status, connected.bodyAsText())
            val fetched =
                (1..4).map {
                    val bundle = get("/v1/keys/${amina.userId}", brian.app)
                    assertEquals(HttpStatusCode.OK, bundle.status, bundle.bodyAsText())
                    json(bundle)["data"]!!.jsonObject
                }
            assertEquals(
                listOf("1", "2", "3", null),
                fetched.map { (it["oneTimePreKey"] as? JsonObject)?.get("keyId")?.jsonPrimitive?.content },
            )
            assertEquals(amina.userId, fetched.first()["userId"]!!.jsonPrimitive.content)

            // Brian has published nothing yet
            assertEquals("messaging.keys_not_found", code(get("/v1/keys/${brian.userId}", amina.app)))
        }

    @Test
    fun `direct chats and groups need reach, keep names sealed, and follow admin, handover and leave rules`() =
        // No 1-minute test timeout: whichever test runs first creates every table in a fresh container.
        runBlocking<Unit> {
            runTestApplication { chatsJourney() }
        }

    private suspend fun ApplicationTestBuilder.chatsJourney() {
        var sms: FakeSms? = null
        application {
            circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
            sms = getKoin().get<SmsGateway>() as FakeSms
        }
        client.get("/health/live")
        val amina = signUp(sms!!, "Amina Otieno", "+254712${(100000..999999).random()}")
        val brian = signUp(sms!!, "Brian Mwangi", "+254733${(100000..999999).random()}")
        val chidi = signUp(sms!!, "Chidi Okafor", "+254744${(100000..999999).random()}")

        suspend fun get(path: String, token: String) = client.get(path) { header("Authorization", "Bearer $token") }

        suspend fun send(
            method: HttpMethod,
            path: String,
            token: String,
            body: String = "{}",
        ) = client.request(path) {
            this.method = method
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody(body)
        }

        suspend fun code(response: HttpResponse) = json(response)["code"]!!.jsonPrimitive.content

        suspend fun data(response: HttpResponse): JsonObject {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json(response)["data"]!!.jsonObject
        }

        // a direct chat needs reach; once connected, opening it twice gives the same chat
        val refused = send(HttpMethod.Post, "/v1/chats/direct", amina.app, """{"userId":"${brian.userId}"}""")
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("users.not_reachable", code(refused))
        val token = data(send(HttpMethod.Post, "/v1/invites", amina.app))["token"]!!.jsonPrimitive.content
        data(send(HttpMethod.Post, "/v1/connections", brian.app, """{"via":"invite","value":"$token"}"""))
        val direct = data(send(HttpMethod.Post, "/v1/chats/direct", amina.app, """{"userId":"${brian.userId}"}"""))
        assertEquals("true", direct["created"]!!.jsonPrimitive.content)
        val again = data(send(HttpMethod.Post, "/v1/chats/direct", brian.app, """{"userId":"${amina.userId}"}"""))
        assertEquals(direct["chatId"], again["chatId"])
        assertEquals("false", again["created"]!!.jsonPrimitive.content)

        // a group: Chidi can't be reached by Amina, so he is listed in notAdded
        val sealed = Base64.getEncoder().encodeToString(ByteArray(48) { 1 })
        val group =
            data(
                send(
                    HttpMethod.Post,
                    "/v1/chats/groups",
                    amina.app,
                    """{"sealedMeta":"$sealed","memberIds":["${brian.userId}","${chidi.userId}"]}""",
                ),
            )
        val chatId = group["chatId"]!!.jsonPrimitive.content
        assertEquals(listOf(chidi.userId), group["notAdded"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(sealed, group["sealedMeta"]!!.jsonPrimitive.content)
        assertTrue("OWNER" in group["members"].toString() && "Brian Mwangi" in group["members"].toString())
        val oversized = Base64.getEncoder().encodeToString(ByteArray(5_000))
        val invalid = send(HttpMethod.Post, "/v1/chats/groups", amina.app, """{"sealedMeta":"$oversized"}""")
        assertEquals("messaging.group_invalid", code(invalid))

        // the agent token reaches none of it
        assertEquals(HttpStatusCode.Forbidden, get("/v1/chats", amina.agent).status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/chats/$chatId", amina.agent).status)
        val asAgent = send(HttpMethod.Post, "/v1/chats/groups", amina.agent, """{"sealedMeta":"$sealed"}""")
        assertEquals(HttpStatusCode.Forbidden, asAgent.status)

        // Brian is a member, not an admin; Chidi isn't in it at all
        val version = group["version"]!!.jsonPrimitive.content
        val notAdmin = send(HttpMethod.Patch, "/v1/chats/$chatId", brian.app, """{"version":$version}""")
        assertEquals(HttpStatusCode.Forbidden, notAdmin.status)
        assertEquals("messaging.not_admin", code(notAdmin))
        assertEquals("messaging.chat_not_found", code(get("/v1/chats/$chatId", chidi.app)))

        // Amina re-seals the name; an old version is refused
        val resealed = Base64.getEncoder().encodeToString(ByteArray(48) { 2 })
        val updated =
            data(
                send(
                    HttpMethod.Patch,
                    "/v1/chats/$chatId",
                    amina.app,
                    """{"sealedMeta":"$resealed","version":$version}""",
                ),
            )
        assertEquals("2", updated["metaVersion"]!!.jsonPrimitive.content)
        val stale = send(HttpMethod.Patch, "/v1/chats/$chatId", amina.app, """{"version":$version}""")
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("messaging.version_conflict", code(stale))

        // she makes Brian an admin, then leaves: ownership passes to him
        data(
            send(
                HttpMethod.Put,
                "/v1/chats/$chatId/members/${brian.userId}/role",
                amina.app,
                """{"role":"ADMIN"}""",
            ),
        )
        data(send(HttpMethod.Delete, "/v1/chats/$chatId/members/${amina.userId}", amina.app))
        assertEquals("messaging.chat_not_found", code(get("/v1/chats/$chatId", amina.app)))
        val members = data(get("/v1/chats/$chatId", brian.app))["members"]!!.jsonArray
        assertEquals(
            "OWNER",
            members
                .single()
                .jsonObject["role"]!!
                .jsonPrimitive.content,
        )
        val brianChats = data(get("/v1/chats", brian.app))["chats"]!!.jsonArray
        assertEquals(2, brianChats.size, "the direct chat and the group")
    }

    @Test
    fun `Asha answers over server-sent events, asks the phone for a chat, and forgets on request`() =
        testApplication {
            var sms: FakeSms? = null
            application {
                circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
                sms = getKoin().get<SmsGateway>() as FakeSms
            }
            client.get("/health/live")
            val me = signUp(sms!!, "Amina Otieno", "+254712${(100000..999999).random()}")

            suspend fun post(path: String, body: String) =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer ${me.app}")
                    header("X-Agent-Token", me.agent)
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    setBody(body)
                }

            fun events(sse: String) =
                sse.trim().split("\n\n").filter { it.isNotBlank() }.associate {
                    val lines = it.lines()
                    lines.first().removePrefix("event: ") to lines.last().removePrefix("data: ")
                }

            // a plain question streams as message events and ends with done
            val plain = post("/v1/assistant/chat/messages", """{"text":"habari Asha"}""")
            assertEquals(HttpStatusCode.OK, plain.status, plain.bodyAsText())
            assertTrue(plain.headers["Content-Type"]!!.startsWith("text/event-stream"))
            val plainEvents = events(plain.bodyAsText())
            assertTrue("You said: habari Asha" in plainEvents["message"]!! || "habari" in plainEvents["message"]!!)
            assertTrue("\"role\":\"ASHA\"" in plainEvents["done"]!!)

            // a question about chats pauses for the phone, which answers with a snippet
            val paused =
                events(
                    post(
                        "/v1/assistant/chat/messages",
                        """{"text":"chat december contribution","askedFrom":{"chatId":"c1","chatName":"Family"}}""",
                    ).bodyAsText(),
                )
            val request = Json.parseToJsonElement(paused["tool_request"]!!).jsonObject
            assertEquals("searchChats", request["name"]!!.jsonPrimitive.content)
            val turnId = request["turnId"]!!.jsonPrimitive.content
            val resumed =
                post(
                    "/v1/assistant/chat/messages/$turnId/tool-results",
                    """{"callId":"${request["callId"]!!.jsonPrimitive.content}","snippets":[{"chatId":"c1","chatName":"Family","sender":"Brian","at":"2026-10-20T09:00:00Z","text":"KES 2,000 each by the 15th"}]}""",
                )
            assertEquals(HttpStatusCode.OK, resumed.status, resumed.bodyAsText())
            val done =
                Json
                    .parseToJsonElement(
                        events(resumed.bodyAsText())["done"]!!,
                    ).jsonObject["message"]!!
                    .jsonObject
            assertEquals(
                1,
                done["readNote"]!!
                    .jsonObject["messageCount"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
            // the same answer cannot be replayed
            assertEquals(
                HttpStatusCode.NotFound,
                post(
                    "/v1/assistant/chat/messages/$turnId/tool-results",
                    """{"callId":"${request["callId"]!!.jsonPrimitive.content}","snippets":[]}""",
                ).status,
            )

            // the chat holds both exchanges, newest first, and the snippet is nowhere in it
            val chat =
                json(
                    client.get("/v1/assistant/chat") {
                        header("Authorization", "Bearer ${me.app}")
                    },
                )["data"]!!.jsonObject["messages"]!!.toString()
            assertTrue("habari Asha" in chat && "chat december contribution" in chat)
            assertTrue("KES 2,000 each by the 15th" !in chat, "device snippets are never stored")

            val usage =
                json(
                    client.get("/v1/assistant/usage") { header("Authorization", "Bearer ${me.app}") },
                )["data"]!!.jsonObject
            assertEquals("FREE", usage["plan"]!!.jsonPrimitive.content)
            assertEquals(2, usage["textTurnsUsed"]!!.jsonPrimitive.content.toInt())

            // voice and speech
            val voice =
                client.post("/v1/assistant/voice") {
                    header("Authorization", "Bearer ${me.app}")
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    header("X-Duration-Seconds", "4")
                    contentType(ContentType.parse("audio/mp4"))
                    setBody("what is my balance".toByteArray())
                }
            assertEquals("what is my balance", json(voice)["data"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, post("/v1/assistant/speech", """{"text":"Habari yako"}""").status)

            // the agent token cannot talk to Asha: she is for the user token
            val asAgent = client.get("/v1/assistant/chat") { header("Authorization", "Bearer ${me.agent}") }
            assertEquals(HttpStatusCode.Forbidden, asAgent.status)

            // forgetting
            val deleted =
                client.delete("/v1/assistant/chat") {
                    header("Authorization", "Bearer ${me.app}")
                    header("Idempotency-Key", UUID.randomUUID().toString())
                }
            assertEquals(HttpStatusCode.OK, deleted.status)
            val after =
                json(
                    client.get("/v1/assistant/chat") {
                        header("Authorization", "Bearer ${me.app}")
                    },
                )["data"]!!.jsonObject["messages"]!!.toString()
            assertEquals("[]", after)
        }

    private suspend fun json(response: HttpResponse): JsonObject =
        Json
            .parseToJsonElement(
                response.bodyAsText(),
            ).jsonObject

    @Suppress("unused")
    private fun ApplicationTestBuilder.unused() = Unit
}
