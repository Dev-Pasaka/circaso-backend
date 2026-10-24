package com.circaso.gateway

import com.circaso.gateway.realtime.SocketHub
import com.circaso.identity.api.Principal
import com.circaso.identity.api.SessionId
import com.circaso.identity.api.TokenVerifier
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.DeviceId
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeModule
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class SocketHubTest {
    private class MemoryRedis : RedisScope {
        override val prefix = "rt"
        val values = ConcurrentHashMap<String, String>()

        override suspend fun get(key: String) = values[key]

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

        override suspend fun hashIncrement(
            key: String,
            field: String,
            by: Long,
        ): Long? = null

        override suspend fun ttl(key: String): Duration? = null

        override suspend fun publish(channel: String, message: String) = Unit

        override fun subscribe(channel: String): Flow<String> = emptyFlow()
    }

    private val user = UserId.new()

    private val verifier =
        object : TokenVerifier {
            override suspend fun verify(accessToken: String): Resource<Principal> =
                if (accessToken == "good") {
                    Resource.success(Principal(user, DeviceId.new(), SessionId.new(), Scope.APP))
                } else {
                    Resource.error(SharedError.TokenInvalid)
                }
        }

    private fun frame(seq: Long) =
        RealtimeEnvelope(
            "users",
            "contact.joined",
            "id-$seq",
            seq,
            Instant.now(),
            JsonObject(emptyMap()),
        )

    @Test
    fun `a phone that authenticates catches up and then receives delivered frames`() =
        testApplication {
            val replay =
                RealtimeModule(
                    "users",
                    object : RealtimeReplay {
                        override suspend fun since(userId: UserId, seq: Long) = Resource.success(listOf(frame(seq + 1)))
                    },
                )
            val hub =
                SocketHub(
                    MemoryRedis(),
                    verifier,
                    { listOf(replay) },
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    "node-1",
                )
            application {
                install(WebSockets)
                routing { route("/v1") { hub.routes(this) } }
            }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                send(Frame.Text("""{"type":"auth","accessToken":"good","last":{"users":41}}"""))
                val missed =
                    CircasoJson.decodeFromString(
                        RealtimeEnvelope.serializer(),
                        withTimeout(3000) {
                            (incoming.receive() as Frame.Text).readText()
                        },
                    )
                assertEquals(42, missed.seq)

                assertTrue(hub.deliver(user, frame(43)))
                val live =
                    CircasoJson.decodeFromString(
                        RealtimeEnvelope.serializer(),
                        withTimeout(3000) {
                            (incoming.receive() as Frame.Text).readText()
                        },
                    )
                assertEquals(43, live.seq)
            }
        }

    @Test
    fun `a bad token closes the socket and nothing is delivered to it`() =
        testApplication {
            val hub =
                SocketHub(
                    MemoryRedis(),
                    verifier,
                    { emptyList() },
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    "node-1",
                )
            application {
                install(WebSockets)
                routing { route("/v1") { hub.routes(this) } }
            }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                send(Frame.Text("""{"type":"auth","accessToken":"bad"}"""))
                withTimeout(3000) { incoming.receiveCatching() }
            }
            assertEquals(false, hub.deliver(user, frame(1)))
        }
}
