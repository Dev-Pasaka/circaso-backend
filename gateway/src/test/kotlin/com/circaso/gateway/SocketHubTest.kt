package com.circaso.gateway

import com.circaso.gateway.realtime.GatewayRealtime
import com.circaso.gateway.realtime.NodeMessage
import com.circaso.gateway.realtime.SocketHub
import com.circaso.identity.api.Principal
import com.circaso.identity.api.PushRegistration
import com.circaso.identity.api.PushRegistrations
import com.circaso.identity.api.SessionId
import com.circaso.identity.api.TokenVerifier
import com.circaso.infrastructure.api.RedisScope
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushReceipt
import com.circaso.integrations.api.PushTarget
import com.circaso.shared.CircasoJson
import com.circaso.shared.ClientFrame
import com.circaso.shared.DeviceId
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeInbound
import com.circaso.shared.RealtimeInboundModule
import com.circaso.shared.RealtimeModule
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class SocketHubTest {
    private class MemoryRedis : RedisScope {
        override val prefix = "rt"
        val values = ConcurrentHashMap<String, String>()
        val published = CopyOnWriteArrayList<Pair<String, String>>()
        val singleReads = AtomicInteger()
        val pipelinedReads = AtomicInteger()
        private val channels = ConcurrentHashMap<String, MutableSharedFlow<String>>()

        private fun channel(name: String) =
            channels.computeIfAbsent(name) { MutableSharedFlow(extraBufferCapacity = 64) }

        override suspend fun get(key: String): String? {
            singleReads.incrementAndGet()
            return values[key]
        }

        override suspend fun getPipelined(keys: List<String>): List<String?> {
            pipelinedReads.incrementAndGet()
            return keys.map { values[it] }
        }

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

        override suspend fun increment(key: String, ttl: Duration): Long = incrementBy(key, 1, ttl)

        override suspend fun incrementBy(
            key: String,
            by: Long,
            ttl: Duration,
        ): Long = values.merge(key, by.toString()) { a, b -> (a.toLong() + b.toLong()).toString() }!!.toLong()

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

        override suspend fun publish(channel: String, message: String) {
            published += channel to message
            channel(channel).emit(message)
        }

        override fun subscribe(channel: String): Flow<String> = channel(channel)
    }

    /** What a module's [RealtimeInbound] was handed, in order. */
    private class RecordingInbound : RealtimeInbound {
        val received = Channel<Triple<UserId, DeviceId, ClientFrame>>(Channel.UNLIMITED)

        override suspend fun receive(
            userId: UserId,
            deviceId: DeviceId,
            frame: ClientFrame,
        ): Resource<Unit> {
            if (frame.type == "boom") error("handler failed")
            received.send(Triple(userId, deviceId, frame))
            return Resource.success(Unit)
        }
    }

    private class RecordingPush : PushGateway {
        val sent = CopyOnWriteArrayList<Pair<PushTarget, PushMessage>>()

        override suspend fun send(target: PushTarget, message: PushMessage): Resource<PushReceipt> {
            sent += target to message
            return Resource.success(PushReceipt("receipt", "test"))
        }
    }

    private val registrations =
        object : PushRegistrations {
            override suspend fun pushTarget(userId: UserId) = PushRegistration("android", "token-$userId")
        }

    private val user = UserId.new()
    private val device = DeviceId.new()
    private val otherUser = UserId.new()

    private val verifier =
        object : TokenVerifier {
            override suspend fun verify(accessToken: String): Resource<Principal> =
                when (accessToken) {
                    "good" -> Resource.success(Principal(user, device, SessionId.new(), Scope.APP))
                    "other" -> Resource.success(Principal(otherUser, DeviceId.new(), SessionId.new(), Scope.APP))
                    else -> Resource.error(SharedError.TokenInvalid)
                }
        }

    private fun frame(
        seq: Long,
        module: String = "users",
        type: String = "contact.joined",
        data: Map<String, Any> = emptyMap(),
    ) = RealtimeEnvelope(
        module,
        type,
        "id-$seq",
        seq,
        Instant.now(),
        JsonObject(
            data.mapValues { (_, value) ->
                if (value is Boolean) JsonPrimitive(value) else JsonPrimitive(value.toString())
            },
        ),
    )

    private fun hub(
        redis: RedisScope = MemoryRedis(),
        replays: List<RealtimeModule> = emptyList(),
        inbound: List<RealtimeInboundModule> = emptyList(),
        nodeId: String = "node-1",
    ) = SocketHub(
        redis,
        verifier,
        { replays },
        { inbound },
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        nodeId,
    )

    private fun ApplicationTestBuilder.serve(hub: SocketHub) {
        application {
            install(WebSockets)
            routing { route("/v1") { hub.routes(this) } }
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextFrame(): RealtimeEnvelope =
        CircasoJson.decodeFromString(
            RealtimeEnvelope.serializer(),
            withTimeout(3000) { (incoming.receive() as Frame.Text).readText() },
        )

    private suspend fun DefaultClientWebSocketSession.authenticate(token: String = "good") {
        send(Frame.Text("""{"type":"auth","accessToken":"$token"}"""))
        // The socket is registered once auth is read; give the server a moment before frames are sent its way.
        delay(200)
    }

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
            val hub = hub(replays = listOf(replay))
            serve(hub)
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                send(Frame.Text("""{"type":"auth","accessToken":"good","last":{"users":41}}"""))
                assertEquals(42, nextFrame().seq)

                assertTrue(hub.deliver(user, frame(43)))
                assertEquals(43, nextFrame().seq)
            }
        }

    @Test
    fun `a bad token closes the socket and nothing is delivered to it`() =
        testApplication {
            val hub = hub()
            serve(hub)
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                send(Frame.Text("""{"type":"auth","accessToken":"bad"}"""))
                withTimeout(3000) { incoming.receiveCatching() }
            }
            assertEquals(false, hub.deliver(user, frame(1)))
        }

    @Test
    fun `a phone frame goes to the module it names, with the user and phone of the token`() =
        testApplication {
            val messaging = RecordingInbound()
            serve(hub(inbound = listOf(RealtimeInboundModule("messaging", messaging))))
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                authenticate()
                send(Frame.Text("""{"module":"messaging","type":"ack","data":{"messageIds":["m1"]}}"""))

                val (userId, deviceId, received) = withTimeout(3000) { messaging.received.receive() }
                assertEquals(user, userId)
                assertEquals(device, deviceId)
                assertEquals("ack", received.type)
                assertEquals("""{"messageIds":["m1"]}""", received.data.toString())
            }
        }

    @Test
    fun `frames for unknown modules, unreadable frames and a failing handler leave the socket open`() =
        testApplication {
            val messaging = RecordingInbound()
            serve(hub(inbound = listOf(RealtimeInboundModule("messaging", messaging))))
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                authenticate()
                send(Frame.Text("""{"module":"wallet","type":"ack"}"""))
                send(Frame.Text("""{"type":"pong"}"""))
                send(Frame.Text("not json"))
                send(Frame.Text("""{"module":"messaging","type":"boom"}"""))
                send(Frame.Text("""{"module":"messaging","type":"typing","data":{"chatId":"c1","on":true}}"""))

                val received = withTimeout(3000) { messaging.received.receive() }.third
                assertEquals("typing", received.type, "only the messaging frame that worked reached messaging")
                assertNull(withTimeoutOrNull(300) { messaging.received.receive() })
            }
        }

    @Test
    fun `a phone frame sent instead of auth closes the socket and reaches no module`() =
        testApplication {
            val messaging = RecordingInbound()
            serve(hub(inbound = listOf(RealtimeInboundModule("messaging", messaging))))
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                send(Frame.Text("""{"module":"messaging","type":"ack","data":{"messageIds":["m1"]}}"""))
                withTimeout(3000) { incoming.receiveCatching() }
            }
            assertNull(withTimeoutOrNull(300) { messaging.received.receive() })
        }

    @Test
    fun `a typing frame to a group is one publish on rt fanout and no Redis read`() =
        runBlocking {
            val redis = MemoryRedis()
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(redis), push, registrations)
            val members = List(50) { UserId.new() }

            realtime.deliverMany(members, frame(1, "messaging", "typing", mapOf("chatId" to "c1", "on" to true)))

            assertEquals(listOf("fanout"), redis.published.map { it.first })
            val message = CircasoJson.decodeFromString(NodeMessage.serializer(), redis.published.single().second)
            assertEquals(members, (message as NodeMessage.Frames).userIds)
            assertEquals(0, redis.singleReads.get(), "no GET")
            assertEquals(0, redis.pipelinedReads.get(), "no lookup of who is offline")
            assertTrue(push.sent.isEmpty())
        }

    @Test
    fun `every node writes a fan-out frame to its own sockets only`() =
        testApplication {
            val redis = MemoryRedis()
            val nodeOne = hub(redis, nodeId = "node-1")
            val nodeTwo = hub(redis, nodeId = "node-2")
            nodeOne.start()
            nodeTwo.start()
            application {
                install(WebSockets)
                routing {
                    route("/one") { nodeOne.routes(this) }
                    route("/two") { nodeTwo.routes(this) }
                }
            }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/one/ws") {
                val first = this
                authenticate("good")
                client.webSocket("/two/ws") {
                    authenticate("other")
                    val readsBefore = redis.singleReads.get()

                    nodeOne.deliverMany(listOf(user, otherUser, UserId.new()), frame(7, "messaging", "receipt"))

                    assertEquals(7, first.nextFrame().seq, "node one wrote to its socket")
                    assertEquals(7, nextFrame().seq, "node two wrote to its socket")
                    assertNull(withTimeoutOrNull(300) { first.incoming.receive() }, "and only once")
                    assertNull(withTimeoutOrNull(300) { incoming.receive() }, "and only once")
                    assertEquals(readsBefore, redis.singleReads.get(), "from memory, with no Redis read")
                    assertEquals(1, redis.published.size)
                }
            }
        }

    @Test
    fun `deliverMany with nobody to tell touches nothing`() =
        runBlocking {
            val redis = MemoryRedis()

            hub(redis).deliverMany(emptyList(), frame(1))

            assertTrue(redis.published.isEmpty())
        }

    @Test
    fun `offline members of a group get an ids-only new-message push, online ones none`() =
        runBlocking {
            val redis = MemoryRedis()
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(redis), push, registrations)
            val (online, offline, alsoOffline) = List(3) { UserId.new() }
            redis.values["conn:$online"] = "node-2"

            realtime.deliverMany(
                listOf(online, offline, alsoOffline),
                frame(1, "messaging", "message.new", mapOf("chatId" to "c1", "messageId" to "m1", "pushable" to true)),
            )

            assertEquals(setOf("token-$offline", "token-$alsoOffline"), push.sent.map { it.first.token }.toSet())
            assertEquals(1, redis.pipelinedReads.get(), "who is offline, in one pipelined lookup")
            assertEquals(0, redis.singleReads.get())
            push.sent.forEach { (_, message) ->
                assertEquals(mapOf("chatId" to "c1", "messageId" to "m1"), message.data)
                assertEquals("New message", message.body, "no text from the chat")
            }
        }

    @Test
    fun `a message that is not pushable wakes nobody and looks nobody up`() =
        runBlocking {
            val redis = MemoryRedis()
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(redis), push, registrations)

            realtime.deliverMany(
                List(3) { UserId.new() },
                frame(1, "messaging", "message.new", mapOf("chatId" to "c1", "messageId" to "m1", "pushable" to false)),
            )

            assertTrue(push.sent.isEmpty())
            assertEquals(0, redis.pipelinedReads.get())
        }

    @Test
    fun `a new-message frame without pushable wakes nobody`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)

            realtime.deliverMany(
                List(3) { UserId.new() },
                frame(1, "messaging", "message.new", mapOf("chatId" to "c1", "messageId" to "m1")),
            )
            realtime.deliver(UserId.new(), frame(2, "messaging", "message.new", mapOf("chatId" to "c1")))

            assertTrue(push.sent.isEmpty())
        }

    @Test
    fun `only the person added to a group is pushed for member added`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)
            val (added, member) = List(2) { UserId.new() }

            realtime.deliverMany(
                listOf(added, member),
                frame(1, "messaging", "member.added", mapOf("chatId" to "c1", "userId" to added)),
            )

            assertEquals(listOf("token-$added"), push.sent.map { it.first.token })
            assertEquals(
                mapOf("chatId" to "c1"),
                push.sent
                    .single()
                    .second.data,
            )
        }

    @Test
    fun `frames that are not push-worthy are not pushed to offline users`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)

            realtime.deliverMany(List(3) { UserId.new() }, frame(1, "messaging", "receipt"))
            realtime.deliver(UserId.new(), frame(2, "messaging", "typing"))

            assertTrue(push.sent.isEmpty())
        }
}
