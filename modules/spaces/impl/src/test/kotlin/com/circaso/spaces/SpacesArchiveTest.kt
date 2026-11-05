package com.circaso.spaces

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.ScheduledJob
import com.circaso.infrastructure.api.WarmUp
import com.circaso.messaging.api.SpacePrivateChats
import com.circaso.shared.EventSubscription
import com.circaso.shared.RealtimeModule
import com.circaso.spaces.di.spacesModule
import com.circaso.wallet.api.SpaceWalletPolicy
import com.circaso.web.RouteContributor
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.Koin
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spaces is archived (decision 2026-11-05): with `SPACES_ENABLED` unset (or false) its routes answer
 * `spaces.unavailable` and nothing of it runs; with it true the full module is bound again, so the archive stays
 * restorable.
 */
class SpacesArchiveTest {
    private fun koin(env: Map<String, String> = emptyMap()): Koin =
        koinApplication { modules(spacesModule(MapConfig(env))) }.koin

    /** What the module declares, without building it (the live module needs infrastructure to build). */
    @OptIn(KoinInternalApi::class)
    private fun Koin.declared(type: KClass<*>): List<String> =
        instanceRegistry.instances.values
            .map { it.beanDefinition }
            .filter { it.primaryType == type || type in it.secondaryTypes }
            .map { it.qualifier?.value ?: it.primaryType.simpleName.orEmpty() }
            .distinct()
            .sorted()

    @Test
    fun `off by default, nothing of Spaces is scheduled, consumed, replayed or offered to other modules`() {
        val koin = koin()

        assertEquals(emptyList(), koin.declared(ScheduledJob::class))
        assertEquals(emptyList(), koin.declared(EventSubscription::class))
        assertEquals(emptyList(), koin.declared(RealtimeModule::class))
        assertEquals(emptyList(), koin.declared(WarmUp::class))
        assertEquals(emptyList(), koin.declared(SpaceWalletPolicy::class))
        assertEquals(emptyList(), koin.declared(SpacePrivateChats::class))
        // the data stays: the schema is still migrated, nothing is dropped
        assertEquals(listOf("spaces"), koin.getAll<DatabaseModuleSpec>().map { it.schema })
    }

    @Test
    fun `off, every Spaces route answers 404 spaces unavailable in the envelope`() =
        testApplication {
            val routes = koin(mapOf("spaces.enabled" to "false")).get<RouteContributor>(named("spaces.routes"))
            application {
                installJson()
                installStatusPages()
                routing { route("/v1") { routes.install(this) } }
            }

            val answers =
                listOf(
                    client.get("/v1/spaces"),
                    client.get("/v1/spaces/mine"),
                    client.get("/v1/spaces/suggested"),
                    client.post("/v1/spaces/0190a1b2-0000-7000-8000-000000000000/follow"),
                    client.delete("/v1/spaces/0190a1b2-0000-7000-8000-000000000000/membership"),
                    client.post("/v1/spaces/invites/0190a1b2-0000-7000-8000-000000000000/accept"),
                    client.get("/v1/ops/spaces/reports"),
                )

            answers.forEach { assertUnavailable(it) }
        }

    @Test
    fun `on, the full module is bound again, its jobs, consumers, replay and guards included`() {
        val koin = koin(mapOf("spaces.enabled" to "true"))

        assertEquals(listOf("spaces.memberships", "spaces.purge-expired"), koin.declared(ScheduledJob::class))
        assertEquals(
            listOf("spaces.identity-events", "spaces.wallet-events"),
            koin.declared(EventSubscription::class),
        )
        assertEquals(listOf("spaces.replay"), koin.declared(RealtimeModule::class))
        assertEquals(1, koin.declared(SpaceWalletPolicy::class).size)
        assertEquals(1, koin.declared(SpacePrivateChats::class).size)
        assertEquals(listOf("spaces.routes"), koin.declared(RouteContributor::class))
    }

    private suspend fun assertUnavailable(response: HttpResponse) {
        assertEquals(
            HttpStatusCode.NotFound,
            response.status,
            response.call.request.url
                .toString(),
        )
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("spaces.unavailable", body["code"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank())
    }

    private class MapConfig(private val values: Map<String, String>, private val prefix: String = "") : Config {
        override val environment = Environment.LOCAL

        override fun string(key: String): String? = values[prefix + key]

        override fun section(prefix: String): Config = MapConfig(values, "${this.prefix}$prefix.")
    }
}
