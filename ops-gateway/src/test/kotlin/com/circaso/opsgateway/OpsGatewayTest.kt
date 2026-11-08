package com.circaso.opsgateway

import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.ops.api.StaffDirectory
import com.circaso.ops.api.StaffMember
import com.circaso.opsgateway.auth.AccessKeys
import com.circaso.opsgateway.auth.AccessVerifier
import com.circaso.opsgateway.auth.DevLogin
import com.circaso.shared.Resource
import com.circaso.shared.StaffRole
import com.circaso.web.OpsRouteContributor
import com.circaso.web.installJson
import com.circaso.web.installRequestId
import com.circaso.web.installStatusPages
import com.circaso.web.respondDefault
import com.circaso.web.staff
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PORTAL = "https://admin.circaso.com"
private const val SECRET = "a-sandbox-only-secret-of-32-chars!"

class OpsGatewayTest {
    private val access = AccessTokens()
    private val staffList =
        mapOf(
            "admin@circaso.com" to StaffMember(UUID.randomUUID(), "admin@circaso.com", StaffRole.ADMIN, true, false),
            "support@circaso.com" to
                StaffMember(UUID.randomUUID(), "support@circaso.com", StaffRole.SUPPORT, true, false),
            "finance@circaso.com" to
                StaffMember(UUID.randomUUID(), "finance@circaso.com", StaffRole.FINANCE, true, false),
        )
    private val devLogins = AtomicInteger()
    private val directory =
        object : StaffDirectory {
            override suspend fun signIn(email: String): StaffMember? = staffList[email.lowercase()]

            override suspend fun recordDevLogin(staff: StaffMember, requestId: String?) {
                devLogins.incrementAndGet()
            }
        }
    private val redis = ConcurrentHashMap<String, MemoryRedis>()
    private val scopes =
        object : RedisScopes {
            override fun scope(prefix: String): RedisScope = redis.getOrPut(prefix) { MemoryRedis(prefix) }
        }
    private val writes = AtomicInteger()

    /** Two staff routes like the modules' own: one any role reads, one only FINANCE and ADMIN, and a change. */
    private val routes =
        OpsRouteContributor { route ->
            route.route("/ops/probe") {
                staffOnly(StaffRole.entries.toSet()) {
                    get { call.respondDefault(Resource.success(call.staff().role.name).toDefaultResponse { it }) }
                }
                staffOnly(setOf(StaffRole.FINANCE, StaffRole.ADMIN)) {
                    get("money") { call.respondDefault(Resource.success("money").toDefaultResponse { it }) }
                    post("write") {
                        call.respondDefault(Resource.success(writes.incrementAndGet()).toDefaultResponse { it })
                    }
                }
            }
        }

    private fun ApplicationTestBuilder.ops(
        environment: Environment = Environment.STAGING,
        withAccess: Boolean = true,
        devLogin: Boolean = true,
    ) {
        val config =
            OpsGatewayConfig(
                environment = environment,
                access = if (withAccess) AccessSettings(access.team, access.audience) else null,
                allowedOrigins = setOf(PORTAL),
                devLoginSecret = if (devLogin) SECRET else null,
            )
        val deps =
            OpsGatewayDeps(
                config = config,
                access = config.access?.let { AccessVerifier(it, AccessKeys { listOf(access.jwk) }) },
                devLogin = if (devLogin) DevLogin(SECRET, Duration.ofHours(8)) else null,
                directory = directory,
                redis = scopes,
            )
        application {
            installRequestId()
            installJson()
            installStatusPages()
            installOpsGateway(deps, listOf(routes))
        }
    }

    private suspend fun HttpResponse.code(): String? =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject["code"]
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it != "null" }

    @Test
    fun `without a sign-in every staff route answers ops sign_in_required`() =
        testApplication {
            ops()
            val response = client.get("/v1/ops/probe")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("ops.sign_in_required", response.code())
        }

    @Test
    fun `an Access token from the header or the cookie signs a staff member in`() =
        testApplication {
            ops()
            val byHeader =
                client.get(
                    "/v1/ops/probe",
                ) { header(ACCESS_HEADER, access.token(email = "support@circaso.com")) }
            val byCookie =
                client.get("/v1/ops/probe") {
                    header(HttpHeaders.Cookie, "$ACCESS_COOKIE=${access.token(email = "finance@circaso.com")}")
                }

            assertEquals(HttpStatusCode.OK, byHeader.status)
            assertTrue(byHeader.bodyAsText().contains("SUPPORT"))
            assertEquals(HttpStatusCode.OK, byCookie.status)
            assertTrue(byCookie.bodyAsText().contains("FINANCE"))
        }

    @Test
    fun `a bad Access token is refused, and a good one for someone not on the staff list is not staff`() =
        testApplication {
            ops()
            val expired =
                client.get("/v1/ops/probe") {
                    header(
                        ACCESS_HEADER,
                        access.token(
                            expiresAt =
                                java.time.Instant
                                    .now()
                                    .minusSeconds(5),
                        ),
                    )
                }
            val stranger =
                client.get(
                    "/v1/ops/probe",
                ) { header(ACCESS_HEADER, access.token(email = "someone@gmail.com")) }

            assertEquals(HttpStatusCode.Unauthorized, expired.status)
            assertEquals("ops.access_token_invalid", expired.code())
            assertEquals(HttpStatusCode.Forbidden, stranger.status)
            assertEquals("ops.not_staff", stranger.code())
        }

    @Test
    fun `a role is refused what it may not do`() =
        testApplication {
            ops()
            val support =
                client.get(
                    "/v1/ops/probe/money",
                ) { header(ACCESS_HEADER, access.token(email = "support@circaso.com")) }
            val finance =
                client.get(
                    "/v1/ops/probe/money",
                ) { header(ACCESS_HEADER, access.token(email = "finance@circaso.com")) }

            assertEquals(HttpStatusCode.Forbidden, support.status)
            assertEquals("ops.role_forbidden", support.code())
            assertEquals(HttpStatusCode.OK, finance.status)
        }

    @Test
    fun `a change needs an Idempotency-Key, and a repeat gets the first answer without running again`() =
        testApplication {
            ops()
            val token = access.token(email = "admin@circaso.com")
            val missing = client.post("/v1/ops/probe/write") { header(ACCESS_HEADER, token) }
            val first =
                client.post("/v1/ops/probe/write") {
                    header(ACCESS_HEADER, token)
                    header("Idempotency-Key", "k-1")
                }
            val again =
                client.post("/v1/ops/probe/write") {
                    header(ACCESS_HEADER, token)
                    header("Idempotency-Key", "k-1")
                }

            assertEquals(HttpStatusCode.BadRequest, missing.status)
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("true", again.headers["Idempotent-Replayed"])
            assertEquals(1, writes.get())
        }

    @Test
    fun `the dev login gives a bearer token for a staff member who knows the secret, and is audited`() =
        testApplication {
            ops(withAccess = false)
            val wrong = devLogin("support@circaso.com", "not-the-secret")
            val stranger = devLogin("someone@gmail.com", SECRET)
            val good = devLogin("support@circaso.com", SECRET)
            val token =
                Json
                    .parseToJsonElement(
                        good.bodyAsText(),
                    ).jsonObject["data"]!!
                    .jsonObject["token"]!!
                    .jsonPrimitive.content
            val probe = client.get("/v1/ops/probe") { header(HttpHeaders.Authorization, "Bearer $token") }

            assertEquals("ops.dev_login_refused", wrong.code())
            assertEquals("ops.dev_login_refused", stranger.code())
            assertEquals(HttpStatusCode.OK, good.status)
            assertEquals(1, devLogins.get())
            assertEquals(HttpStatusCode.OK, probe.status)
        }

    @Test
    fun `with the dev login off its route answers dev_login_disabled and a bearer token signs nobody in`() =
        testApplication {
            ops(devLogin = false)
            val response = devLogin("support@circaso.com", SECRET)
            val bearer = client.get("/v1/ops/probe") { header(HttpHeaders.Authorization, "Bearer whatever") }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("ops.dev_login_disabled", response.code())
            assertEquals("ops.sign_in_required", bearer.code())
        }

    @Test
    fun `the sign-in config says what is on`() =
        testApplication {
            ops(withAccess = true, devLogin = false)
            val body = client.get("/v1/ops/auth/config").bodyAsText()

            assertTrue(body.contains("\"accessEnabled\":true"))
            assertTrue(body.contains("\"devLoginEnabled\":false"))
            assertTrue(body.contains("\"environment\":\"staging\""))
        }

    @Test
    fun `CORS answers the portal's preflight with credentials and refuses any other origin`() =
        testApplication {
            ops()
            val portal =
                client.options("/v1/ops/probe") {
                    header(HttpHeaders.Origin, PORTAL)
                    header(HttpHeaders.AccessControlRequestMethod, "POST")
                }
            val other =
                client.options("/v1/ops/probe") {
                    header(HttpHeaders.Origin, "https://evil.example")
                    header(HttpHeaders.AccessControlRequestMethod, "POST")
                }

            assertEquals(HttpStatusCode.NoContent, portal.status)
            assertEquals(PORTAL, portal.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("true", portal.headers[HttpHeaders.AccessControlAllowCredentials])
            assertEquals(HttpStatusCode.Forbidden, other.status)
            assertNull(other.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `the hand-off returns only to the portal`() =
        testApplication {
            ops()
            val noRedirects = createClient { followRedirects = false }
            val good = noRedirects.get("/v1/ops/auth/handoff?return=$PORTAL/users")
            val evil = noRedirects.get("/v1/ops/auth/handoff?return=https://evil.example/")

            assertEquals(HttpStatusCode.Found, good.status)
            assertEquals("$PORTAL/users", good.headers[HttpHeaders.Location])
            assertEquals(HttpStatusCode.BadRequest, evil.status)
            assertEquals("ops.return_url_invalid", evil.code())
        }

    @Test
    fun `every answer is marked never to be cached or framed`() =
        testApplication {
            ops()
            val response = client.get("/v1/ops/auth/config")

            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("DENY", response.headers["X-Frame-Options"])
        }

    @Test
    fun `every answer names the environment, and the portal may read it`() =
        testApplication {
            ops(environment = Environment.PRODUCTION, devLogin = false)
            val response = client.get("/v1/ops/auth/config") { header(HttpHeaders.Origin, PORTAL) }

            assertEquals("production", response.headers[ENVIRONMENT_HEADER])
            assertTrue(response.headers[HttpHeaders.AccessControlExposeHeaders]!!.contains(ENVIRONMENT_HEADER))
        }

    private suspend fun ApplicationTestBuilder.devLogin(email: String, secret: String): HttpResponse =
        client.post("/v1/ops/auth/dev-login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","secret":"$secret"}""")
        }
}
