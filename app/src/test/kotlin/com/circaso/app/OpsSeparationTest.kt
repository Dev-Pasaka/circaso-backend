package com.circaso.app

import com.circaso.gateway.template
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.web.OpsRouteContributor
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.dsl.koinApplication
import org.koin.ktor.ext.getKoin
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decision 2026-11-08 (Pasaka): "separate endpoint, separate management, don't mix them together". Boots both
 * processes for real (Koin, every module, the gateways, Redis and PostgreSQL) and checks that no staff route can be
 * reached on the app gateway, and that the ops process serves the staff routes and none of the app's.
 */
class OpsSeparationTest {
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
            "WARMUP_WORKERS" to "1",
            "WARMUP_ROUNDS" to "1",
        ) + TestPostgres.env().filterKeys { it.startsWith("DATABASE_") } + extra

    /** Every staff route the modules contribute, as `METHOD /path` with each `{parameter}` filled with an id. */
    private fun staffRoutes(application: Application): List<Pair<HttpMethod, String>> {
        val contributors = application.getKoin().getAll<OpsRouteContributor>()
        assertTrue(contributors.isNotEmpty())
        val routes = mutableListOf<Pair<HttpMethod, String>>()
        testApplication {
            application {
                routing { route("/v1") { contributors.forEach { it.install(this) } } }
                val root = pluginOrNull(RoutingRoot)!!

                fun walk(node: RoutingNode) {
                    (node.selector as? HttpMethodRouteSelector)?.let {
                        routes += it.method to node.template().replace(Regex("\\{[^}]+}"), UUID.randomUUID().toString())
                    }
                    node.children.forEach(::walk)
                }
                walk(root)
            }
            startApplication()
        }
        return routes
    }

    @Test
    fun `no staff route is reachable on the app gateway, and the app's spec lists none`() =
        testApplication {
            lateinit var routes: List<Pair<HttpMethod, String>>
            application {
                circasoServe(AppConfig(), EnvConfig(env()), EnvSecrets(env()))
                routes = staffRoutes(this)
            }
            startApplication()

            assertTrue(routes.size >= 25, "found ${routes.size} staff routes")
            for ((method, path) in routes) {
                val response =
                    client.request(path) {
                        this.method = method
                        header("Idempotency-Key", UUID.randomUUID().toString())
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.NotFound, response.status, "$method $path on the app gateway")
                assertTrue(response.bodyAsText().contains("request.not_found"), "$method $path")
            }
            val spec = client.get("/docs/openapi.json").bodyAsText()
            assertFalse(spec.contains("\"/v1/ops/"), "the app's reference must not list staff routes")
        }

    @Test
    fun `the ops process serves the staff routes behind sign-in and none of the app's`() =
        testApplication {
            val ops =
                env(
                    mapOf(
                        "OPS_ALLOWED_ORIGINS" to "http://localhost:5173",
                        "OPS_DEV_LOGIN_SECRET" to "a-sandbox-only-secret-of-32-chars!",
                        "OPS_BOOTSTRAP_ADMINS" to "founder@circaso.com",
                    ),
                )
            application { circasoOpsServe(EnvConfig(ops), EnvSecrets(ops)) }

            val config = client.get("/v1/ops/auth/config").bodyAsText()
            val unsigned = client.get("/v1/ops/dashboard")
            val appRoute = client.get("/v1/me/profile")
            val login =
                client.post("/v1/ops/auth/dev-login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"founder@circaso.com","secret":"a-sandbox-only-secret-of-32-chars!"}""")
                }
            val token = Regex("\"token\":\"([^\"]+)\"").find(login.bodyAsText())!!.groupValues[1]
            val dashboard = client.get("/v1/ops/dashboard") { header("Authorization", "Bearer $token") }
            val audit = client.get("/v1/ops/audit") { header("Authorization", "Bearer $token") }
            val appRouteSignedIn = client.get("/v1/me/profile") { header("Authorization", "Bearer $token") }

            assertTrue(config.contains("\"devLoginEnabled\":true"))
            assertEquals(HttpStatusCode.Unauthorized, unsigned.status)
            assertEquals(HttpStatusCode.Unauthorized, appRoute.status, "an app route is just another unknown path here")
            assertEquals(HttpStatusCode.OK, login.status)
            assertEquals(HttpStatusCode.OK, dashboard.status, dashboard.bodyAsText())
            assertTrue(audit.bodyAsText().contains("staff.dev_login"))
            assertEquals(HttpStatusCode.NotFound, appRouteSignedIn.status, "no app route is mounted on the ops gateway")
        }
}
