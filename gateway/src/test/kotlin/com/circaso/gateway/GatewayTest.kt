package com.circaso.gateway

import com.circaso.identity.api.Principal
import com.circaso.identity.api.SessionId
import com.circaso.identity.api.TokenVerifier
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebhookVendor
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.web.DefaultResponse
import com.circaso.web.authenticate
import com.circaso.web.installJson
import com.circaso.web.installRequestId
import com.circaso.web.installStatusPages
import com.circaso.web.respondDefault
import com.circaso.web.userId
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val REVOKED = "revoked"

class GatewayTest {
    private class MemoryRedis(override val prefix: String = "gw") : RedisScope {
        val values = ConcurrentHashMap<String, String>()

        override suspend fun get(key: String) = values[key]

        override suspend fun getPipelined(keys: List<String>) = keys.map { values[it] }

        override suspend fun set(
            key: String,
            value: String,
            ttl: Duration,
        ) {
            values[key] = value
        }

        override suspend fun setIfAbsent(
            key: String,
            value: String,
            ttl: Duration,
        ) = values.putIfAbsent(key, value) == null

        override suspend fun getAndDelete(key: String) = values.remove(key)

        override suspend fun delete(key: String) = values.remove(key) != null

        override suspend fun exists(key: String) = values.containsKey(key)

        override suspend fun increment(key: String, ttl: Duration): Long =
            values
                .merge(key, "1") { a, _ ->
                    (
                        a.toLong() +
                            1
                    ).toString()
                }!!
                .toLong()

        override suspend fun incrementBy(
            key: String,
            by: Long,
            ttl: Duration,
        ): Long =
            values
                .merge(key, by.toString()) { a, b ->
                    (
                        a.toLong() +
                            b.toLong()
                    ).toString()
                }!!
                .toLong()

        override suspend fun incrementRenewing(key: String, ttl: Duration): Long = increment(key, ttl)

        override suspend fun hashSet(
            key: String,
            fields: Map<String, String>,
            ttl: Duration,
        ) = Unit

        override suspend fun hashGetAll(key: String) = emptyMap<String, String>()

        override suspend fun hashTake(key: String) = emptyMap<String, String>()

        override suspend fun hashDelete(key: String, field: String) = false

        override suspend fun hashIncrement(
            key: String,
            field: String,
            by: Long,
        ): Long? = null

        override suspend fun ttl(key: String): Duration? = if (values.containsKey(key)) Duration.ofSeconds(42) else null

        override suspend fun publish(channel: String, message: String) = Unit

        override fun subscribe(channel: String): Flow<String> = emptyFlow()
    }

    private val user = UserId.new()

    /** `revoked` is a well-signed app token whose session is on the deny-list, found out only after [alongside] ran. */
    private val verifier =
        object : TokenVerifier {
            override suspend fun verify(accessToken: String): Resource<Principal> {
                if (accessToken == REVOKED) return Resource.error(SharedError.TokenInvalid)
                val scope = Scope.fromWire(accessToken) ?: return Resource.error(SharedError.TokenInvalid)
                return Resource.success(Principal(user, DeviceId.new(), SessionId.new(), scope))
            }

            override suspend fun <T> verifyAlongside(
                accessToken: String,
                alongside: suspend (Principal) -> T,
            ): Pair<Resource<Principal>, T?> {
                if (accessToken != REVOKED) return super.verifyAlongside(accessToken, alongside)
                val done = alongside(Principal(user, DeviceId.new(), SessionId.new(), Scope.APP))
                return Resource.error(SharedError.TokenInvalid) to done
            }
        }

    private val hits = AtomicInteger()

    private val translated = AtomicInteger()
    private val translatedAtOnce = AtomicInteger()

    /** How the next translations go wrong, one entry each: the vendor's error, an exception or a cancelled call. */
    private enum class Trouble { ERROR, THROW, CANCEL }

    private val trouble = ConcurrentLinkedQueue<Trouble>()

    private abstract class TestVendor(override val name: String) : WebhookVendor {
        override suspend fun verifySignature(headers: Map<String, String>, body: ByteArray) =
            headers["x-signature"] == "good"

        override fun sentAt(headers: Map<String, String>, body: ByteArray): Instant? = null

        override fun deliveryId(headers: Map<String, String>, body: ByteArray) = headers["x-delivery"]
    }

    /** A vendor like Circle: the answer waits for the translation, and a failure must reach the vendor. */
    private val waitingVendor =
        object : TestVendor("waiting") {
            override val answersAfterTranslation = true

            override suspend fun translate(body: ByteArray): Resource<Unit> {
                when (trouble.poll()) {
                    Trouble.ERROR -> return Resource.error(IntegrationError.Unavailable("Wallet"))
                    Trouble.THROW -> throw IllegalStateException("handler broke")
                    Trouble.CANCEL -> throw CancellationException("call cancelled")
                    null -> Unit
                }
                translated.incrementAndGet()
                return Resource.success(Unit)
            }
        }

    /** Every other vendor: `200` at once, translated afterwards; its failures stay on our side. */
    private val atOnceVendor =
        object : TestVendor("at-once") {
            override suspend fun translate(body: ByteArray): Resource<Unit> {
                translatedAtOnce.incrementAndGet()
                return Resource.error(IntegrationError.Unavailable("Somebody"))
            }
        }

    private fun gatewayApp(
        config: GatewayConfig = GatewayConfig(),
        redis: MemoryRedis = MemoryRedis(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            installRequestId()
            installJson()
            installStatusPages()
            installGatewayEdge(
                GatewayDeps(
                    config,
                    verifier,
                    object : RedisScopes {
                        override fun scope(prefix: String) = redis
                    },
                ),
            )
            routing {
                route("/v1") {
                    authenticate(Scope.APP) {
                        get(
                            "/me",
                        ) { call.respondDefault(DefaultResponse(status = true, data = call.userId().toString())) }
                        post("/things") {
                            hits.incrementAndGet()
                            call.respondDefault(DefaultResponse(status = true, data = "made"))
                        }
                    }
                    authenticate(Scope.AGENT) {
                        get(
                            "/agent/contacts",
                        ) { call.respondDefault(DefaultResponse(status = true, data = "list")) }
                    }
                    post("/auth/nonce") { call.respondDefault(DefaultResponse(status = true, data = "n")) }
                    webhookRoutes(
                        listOf(waitingVendor, atOnceVendor),
                        config,
                        redis,
                        CoroutineScope(Dispatchers.Unconfined),
                    )
                }
            }
        }
        block()
    }

    @Test
    fun `a request with no token reaches a public route but not a scoped one`() =
        gatewayApp {
            assertEquals(HttpStatusCode.OK, client.post("/v1/auth/nonce") { header("Idempotency-Key", "a") }.status)
            val response = client.get("/v1/me")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("auth.token_missing"))
        }

    @Test
    fun `an onboarding token cannot use an app route`() =
        gatewayApp {
            val response = client.get("/v1/me") { header("Authorization", "Bearer onboarding") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("auth.scope_insufficient"))
        }

    @Test
    fun `a bad token is refused before the route runs`() =
        gatewayApp {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/v1/me") { header("Authorization", "Bearer nonsense") }.status,
            )
        }

    @Test
    fun `the agent token reaches only GET under agent and the agent route takes only the agent token`() =
        gatewayApp {
            val agent = "Bearer agent"
            assertEquals(HttpStatusCode.OK, client.get("/v1/agent/contacts") { header("Authorization", agent) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/v1/me") { header("Authorization", agent) }.status)
            hits.set(0)
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .post("/v1/things") {
                        header("Authorization", agent)
                        header("Idempotency-Key", "k")
                    }.status,
            )
            assertEquals(0, hits.get(), "a refused request never reaches the route")
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .get("/v1/agent/contacts") {
                        header("Authorization", "Bearer app")
                    }.status,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/agent/contacts").status)
        }

    @Test
    fun `a state change without an idempotency key is refused`() =
        gatewayApp {
            val response = client.post("/v1/things") { header("Authorization", "Bearer app") }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `a repeated idempotency key replays the first response without running the route again`() =
        gatewayApp {
            hits.set(0)

            fun post() =
                suspend {
                    client.post("/v1/things") {
                        header("Authorization", "Bearer app")
                        header("Idempotency-Key", "same")
                    }
                }
            val first = post()()
            val second = post()()
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(first.bodyAsText(), second.bodyAsText())
            assertEquals("true", second.headers["Idempotent-Replayed"])
            assertEquals(1, hits.get())

            client.post("/v1/things") {
                header("Authorization", "Bearer app")
                header("Idempotency-Key", "other")
            }
            assertEquals(2, hits.get())
        }

    @Test
    fun `too many requests answer 429 with Retry-After`() =
        gatewayApp {
            repeat(20) { client.post("/v1/auth/nonce") { header("Idempotency-Key", "k$it") } }
            val response = client.post("/v1/auth/nonce") { header("Idempotency-Key", "last") }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("42", response.headers["Retry-After"])
            assertTrue(response.bodyAsText().contains("request.rate_limited"))
        }

    @Test
    fun `a revoked session is refused, and its request neither counts against the limit nor holds its key`() {
        val redis = MemoryRedis()
        gatewayApp(redis = redis) {
            hits.set(0)
            val response =
                client.post("/v1/things") {
                    header("Authorization", "Bearer $REVOKED")
                    header("Idempotency-Key", "r")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(0, hits.get())
            assertEquals("0", redis.values["rl:user:$user"], "the count sent alongside is taken back")
            assertTrue(redis.values.keys.none { it.startsWith("idem:") }, "the claim sent alongside is given back")

            // The same key with a good token runs, as if the revoked attempt had never been.
            val again =
                client.post("/v1/things") {
                    header("Authorization", "Bearer app")
                    header("Idempotency-Key", "r")
                }
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(1, hits.get())
        }
    }

    @Test
    fun `a request refused for its rate does not hold its idempotency key`() {
        val redis = MemoryRedis()
        gatewayApp(redis = redis) {
            repeat(20) { client.post("/v1/auth/nonce") { header("Idempotency-Key", "k$it") } }
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.post("/v1/auth/nonce") { header("Idempotency-Key", "last") }.status,
            )
            assertEquals(20, redis.values.keys.count { it.startsWith("idem:") })
            // Over the limit, a missing key is still answered with the limit first.
            assertEquals(HttpStatusCode.TooManyRequests, client.post("/v1/auth/nonce").status)
        }
    }

    @Test
    fun `an app older than the minimum is told to update with 426`() =
        gatewayApp(
            GatewayConfig(minClientVersions = mapOf("android" to ClientVersion.parseVersion("1.2.0")!!)),
        ) {
            val old = client.get("/v1/me") { header("X-Client-Version", "android/1.1.9") }
            assertEquals(HttpStatusCode.UpgradeRequired, old.status)
            assertTrue(old.bodyAsText().contains("request.client_outdated"))
            val current =
                client.get("/v1/me") {
                    header("X-Client-Version", "android/1.2.0")
                    header("Authorization", "Bearer app")
                }
            assertEquals(HttpStatusCode.OK, current.status)
        }

    private suspend fun ApplicationTestBuilder.deliver(
        vendor: String = "waiting",
        signature: String = "good",
        delivery: String = "notification-1",
    ) = client.post("/v1/hooks/$vendor") {
        header("x-signature", signature)
        header("x-delivery", delivery)
        setBody("{}")
    }

    @Test
    fun `a translation that throws leaves the delivery retryable`() =
        gatewayApp {
            trouble += Trouble.THROW
            assertEquals(HttpStatusCode.InternalServerError, deliver().status)
            assertEquals(0, translated.get())

            assertEquals(HttpStatusCode.OK, deliver().status)
            assertEquals(1, translated.get(), "the redelivery ran")
        }

    @Test
    fun `a cancelled translation leaves the delivery retryable`() =
        gatewayApp {
            trouble += Trouble.CANCEL
            runCatching { deliver() }.onSuccess { assertTrue(it.status != HttpStatusCode.OK, "${it.status}") }
            assertEquals(0, translated.get())

            assertEquals(HttpStatusCode.OK, deliver().status)
            assertEquals(1, translated.get(), "the redelivery ran")
        }

    @Test
    fun `other vendors get 200 at once even when the translation fails, and a duplicate isn't translated twice`() =
        gatewayApp {
            assertEquals(HttpStatusCode.OK, deliver(vendor = "at-once").status)
            assertEquals(1, translatedAtOnce.get())
            assertEquals(HttpStatusCode.OK, deliver(vendor = "at-once").status)
            assertEquals(1, translatedAtOnce.get(), "the id was claimed before translating, so a repeat is skipped")
            assertEquals(HttpStatusCode.OK, deliver(vendor = "at-once", delivery = "notification-2").status)
            assertEquals(2, translatedAtOnce.get())
        }

    @Test
    fun `a vendor that waits gets the failure, delivers again, and is processed once`() =
        gatewayApp {
            assertEquals(HttpStatusCode.Unauthorized, deliver(signature = "bad").status)

            trouble += Trouble.ERROR
            val failed = deliver()
            assertEquals(HttpStatusCode.BadGateway, failed.status)
            assertTrue("integrations.unavailable" in failed.bodyAsText())
            assertEquals(0, translated.get())

            assertEquals(HttpStatusCode.OK, deliver().status, "the failed delivery wasn't marked as seen")
            assertEquals(1, translated.get())
            assertEquals(HttpStatusCode.OK, deliver().status)
            assertEquals(1, translated.get(), "a repeat after success is acknowledged, not processed")
        }

    @Test
    fun `HEAD on a hook answers 200 for a registered vendor and 404 otherwise, with no body and no signature`() =
        gatewayApp {
            val known = client.head("/v1/hooks/waiting")
            assertEquals(HttpStatusCode.OK, known.status)
            assertEquals("", known.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.head("/v1/hooks/nobody").status)
            assertEquals(0, translated.get(), "a HEAD never reaches the vendor")
        }
}
