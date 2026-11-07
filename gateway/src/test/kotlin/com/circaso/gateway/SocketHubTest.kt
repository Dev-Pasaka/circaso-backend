package com.circaso.gateway

import com.circaso.assistant.api.AssistantFrames
import com.circaso.assistant.api.AssistantStatusFrame
import com.circaso.calls.api.CallFrames
import com.circaso.calls.api.CallId
import com.circaso.calls.api.CallParticipantSnapshot
import com.circaso.calls.api.CallRingingFrame
import com.circaso.calls.api.CallSnapshot
import com.circaso.gateway.realtime.GatewayRealtime
import com.circaso.gateway.realtime.NodeMessage
import com.circaso.gateway.realtime.SessionRevokedFrame
import com.circaso.gateway.realtime.SocketHub
import com.circaso.gateway.realtime.gatewayIdentitySubscription
import com.circaso.identity.api.AccountRelinked
import com.circaso.identity.api.DeviceReplaced
import com.circaso.identity.api.Principal
import com.circaso.identity.api.PushRegistration
import com.circaso.identity.api.PushRegistrations
import com.circaso.identity.api.SessionId
import com.circaso.identity.api.SignedOut
import com.circaso.identity.api.TokenVerifier
import com.circaso.infrastructure.api.RedisScope
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushPayload
import com.circaso.integrations.api.PushReceipt
import com.circaso.integrations.api.PushTarget
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.api.MessageNewFrame
import com.circaso.observability.api.Metrics
import com.circaso.observability.api.NoopTelemetry
import com.circaso.observability.api.Telemetry
import com.circaso.shared.CircasoJson
import com.circaso.shared.ClientFrame
import com.circaso.shared.DeviceId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeInbound
import com.circaso.shared.RealtimeInboundModule
import com.circaso.shared.RealtimeModule
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.decodeData
import com.circaso.status.api.MomentId
import com.circaso.status.api.MomentViewedFrame
import com.circaso.status.api.StatusFrames
import com.circaso.support.api.SupportFrames
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketRepliedFrame
import com.circaso.support.api.TicketStatus
import com.circaso.support.api.TicketUpdatedFrame
import com.circaso.wallet.api.PaymentReceivedFrame
import com.circaso.wallet.api.SetupRequestedFrame
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferUpdatedFrame
import com.circaso.wallet.api.WalletAmount
import com.circaso.wallet.api.WalletFrames
import com.circaso.wallet.api.WalletReadyFrame
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

        /** Set to make the next `incrementRenewing` (the `system` frame's `seq`) fail, as a Redis error would. */
        val failNextIncrement = AtomicBoolean(false)
        private val channels = ConcurrentHashMap<String, MutableSharedFlow<String>>()

        private fun channel(name: String) =
            channels.computeIfAbsent(name) { MutableSharedFlow(extraBufferCapacity = 64) }

        /** Waits until [channel] has [listeners] and [key] holds [value], so a publish is not lost on a busy build. */
        suspend fun awaitReady(
            channel: String,
            listeners: Int,
            key: String,
            value: String,
        ) = withTimeout(3000) {
            while (channel(channel).subscriptionCount.value < listeners || values[key] != value) delay(10)
        }

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

        override suspend fun incrementRenewing(key: String, ttl: Duration): Long {
            if (failNextIncrement.compareAndSet(true, false)) error("redis down")
            return increment(key, ttl)
        }

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

    /** Counts what the hub reports, by metric name and labels. */
    private class RecordingTelemetry : Telemetry by NoopTelemetry {
        val counts = ConcurrentHashMap<Pair<String, Map<String, String>>, Long>()

        override fun count(
            metric: String,
            labels: Map<String, String>,
            by: Long,
        ) {
            counts.merge(metric to labels, by, Long::plus)
        }
    }

    private val user = UserId.new()
    private val device = DeviceId.new()
    private val session = SessionId.new()
    private val laterSession = SessionId.new()
    private val newDevice = DeviceId.new()
    private val otherUser = UserId.new()

    private val verifier =
        object : TokenVerifier {
            override suspend fun verify(accessToken: String): Resource<Principal> =
                when (accessToken) {
                    "good" -> Resource.success(Principal(user, device, session, Scope.APP))

                    // The same phone after signing out and back in: a new session.
                    "later" -> Resource.success(Principal(user, device, laterSession, Scope.APP))

                    "new-phone" -> Resource.success(Principal(user, newDevice, SessionId.new(), Scope.APP))

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
                when (value) {
                    is Boolean -> JsonPrimitive(value)
                    is List<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
                    else -> JsonPrimitive(value.toString())
                }
            },
        ),
    )

    private val chat = ChatId.new()
    private val messageId = UUID.randomUUID()

    /** A typed `messaging` frame, encoded as messaging encodes it. */
    private fun <T> typed(
        type: String,
        serializer: KSerializer<T>,
        data: T,
    ) = RealtimeEnvelope.of("messaging", type, 1, data, serializer)

    private fun newMessage(pushable: Boolean) =
        typed(
            MessageNewFrame.TYPE,
            MessageNewFrame.serializer(),
            MessageNewFrame(messageId, chat, UserId.new(), MessageKind.MESSAGE, pushable, Instant.now()),
        )

    private fun membersAdded(userIds: List<UserId>) =
        typed(MemberAddedFrame.TYPE, MemberAddedFrame.serializer(), MemberAddedFrame(chat, userIds, UserId.new()))

    private fun hub(
        redis: RedisScope = MemoryRedis(),
        replays: List<RealtimeModule> = emptyList(),
        inbound: List<RealtimeInboundModule> = emptyList(),
        nodeId: String = "node-1",
        telemetry: Telemetry = NoopTelemetry,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    ) = SocketHub(
        redis,
        verifier,
        { replays },
        { inbound },
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        telemetry,
        nodeId,
        nowMillis,
    )

    private fun ApplicationTestBuilder.serve(hub: SocketHub) {
        application {
            install(WebSockets) { maxFrameSize = SocketHub.MAX_FRAME_BYTES }
            routing { route("/v1") { hub.routes(this) } }
        }
    }

    private suspend fun DefaultClientWebSocketSession.closedWith(): CloseReason? =
        withTimeout(3000) {
            // Drain until the server's close arrives; the reason is only complete once incoming ends.
            while (incoming.receiveCatching().isSuccess) Unit
            closeReason.await()
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
                send(Frame.Text("""{"type":"auth","accessToken":"good","last":[{"module":"users","seq":41}]}"""))
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
                newMessage(pushable = true),
            )

            assertEquals(setOf("token-$offline", "token-$alsoOffline"), push.sent.map { it.first.token }.toSet())
            assertEquals(1, redis.pipelinedReads.get(), "who is offline, in one pipelined lookup")
            assertEquals(0, redis.singleReads.get())
            push.sent.forEach { (_, message) ->
                assertEquals(PushPayload(chat.toString(), messageId.toString()), message.data)
                assertEquals("New message", message.body, "no text from the chat")
                assertTrue(message.dataOnly, "the app fetches, decrypts and words it")
            }
        }

    @Test
    fun `Asha back after maintenance pushes offline people once each, opening Asha, and online ones get the frame`() =
        runBlocking {
            val redis = MemoryRedis()
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(redis), push, registrations)
            val (online, offline) = List(2) { UserId.new() }
            redis.values["conn:$online"] = "node-2"

            fun status(available: Boolean) =
                RealtimeEnvelope.of(
                    AssistantFrames.MODULE,
                    AssistantFrames.STATUS,
                    0,
                    AssistantStatusFrame(available, "2026-11-07T12:00:00Z"),
                    AssistantStatusFrame.serializer(),
                )

            realtime.deliverMany(listOf(online, offline), status(available = true))
            realtime.deliverMany(listOf(offline), status(available = false))

            val (target, message) = push.sent.single()
            assertEquals("token-$offline", target.token)
            assertEquals("Asha is back", message.title)
            assertEquals("Ask me anything.", message.body)
            assertEquals(PushPayload(kind = "asha.back"), message.data)
            assertTrue(!message.dataOnly, "no personal data: the phone shows it as it is")
        }

    @Test
    fun `a support reply pushes the ticket's number and id from Circaso Support, a status change never`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)
            val user = UserId.new()
            val ticketId = TicketId.new()

            realtime.deliver(
                user,
                RealtimeEnvelope.of(
                    SupportFrames.MODULE,
                    SupportFrames.TICKET_REPLIED,
                    1,
                    TicketRepliedFrame(ticketId, "CS-000123", TicketStatus.PENDING_USER),
                    TicketRepliedFrame.serializer(),
                ),
            )
            realtime.deliver(
                user,
                RealtimeEnvelope.of(
                    SupportFrames.MODULE,
                    SupportFrames.TICKET_UPDATED,
                    2,
                    TicketUpdatedFrame(ticketId, "CS-000123", TicketStatus.RESOLVED),
                    TicketUpdatedFrame.serializer(),
                ),
            )

            val (_, message) = push.sent.single()
            assertEquals("Circaso Support", message.title)
            assertEquals("We replied to your ticket CS-000123.", message.body)
            assertEquals(PushPayload(kind = "support.ticket", ticketId = ticketId.toString()), message.data)
        }

    @Test
    fun `a message that is not pushable wakes nobody and looks nobody up`() =
        runBlocking {
            val redis = MemoryRedis()
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(redis), push, registrations)

            realtime.deliverMany(
                List(3) { UserId.new() },
                newMessage(pushable = false),
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
    fun `only the people added to a group are pushed for member added`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)
            val (first, second, member) = List(3) { UserId.new() }

            realtime.deliverMany(
                listOf(first, second, member),
                membersAdded(listOf(first, second)),
            )

            assertEquals(setOf("token-$first", "token-$second"), push.sent.map { it.first.token }.toSet())
            assertEquals(2, push.sent.size, "the member already in is not woken")
            assertTrue(push.sent.all { it.second.dataOnly }, "the phone words it from the sealed group name")
            assertEquals(
                PushPayload(chatId = chat.toString()),
                push.sent
                    .first()
                    .second.data,
            )
        }

    @Test
    fun `a member added frame without userIds wakes nobody`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)
            val added = UserId.new()

            realtime.deliverMany(
                listOf(added),
                frame(1, "messaging", "member.added", mapOf("chatId" to "c1", "userId" to added)),
            )

            assertTrue(push.sent.isEmpty())
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

    @Test
    fun `the 101st phone frame in a second is dropped and counted, and the socket stays open`() =
        testApplication {
            val messaging = RecordingInbound()
            val telemetry = RecordingTelemetry()
            val clock = AtomicLong(0)
            val hub =
                hub(
                    inbound = listOf(RealtimeInboundModule("messaging", messaging)),
                    telemetry = telemetry,
                    nowMillis = { clock.get() },
                )
            serve(hub)
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                authenticate()
                repeat(101) { send(Frame.Text("""{"module":"messaging","type":"read","data":{"n":$it}}""")) }

                val received = List(100) { withTimeout(3000) { messaging.received.receive() }.third }
                assertEquals("""{"n":99}""", received.last().data.toString(), "the first 100 went through in order")
                assertNull(withTimeoutOrNull(300) { messaging.received.receive() }, "the 101st was dropped")
                val dropped = Metrics.GATEWAY_SOCKET_FRAMES_DROPPED to mapOf("reason" to "rate_limited")
                assertEquals(1L, telemetry.counts[dropped])

                assertTrue(hub.deliver(user, frame(5)), "the socket is still registered")
                assertEquals(5, nextFrame().seq, "and still open")

                clock.set(1_000)
                send(Frame.Text("""{"module":"messaging","type":"read","data":{"n":"next second"}}"""))
                assertEquals("read", withTimeout(3000) { messaging.received.receive() }.third.type)
            }
        }

    @Test
    fun `a frame over 64 KB closes only that socket with too big, and the hub keeps serving`() =
        testApplication {
            val messaging = RecordingInbound()
            val hub = hub(inbound = listOf(RealtimeInboundModule("messaging", messaging)))
            serve(hub)
            val client = createClient { install(ClientWebSockets) }
            val padding = "x".repeat(SocketHub.MAX_FRAME_BYTES.toInt())

            client.webSocket("/v1/ws") {
                authenticate()
                send(Frame.Text("""{"module":"messaging","type":"read","data":{"p":"$padding"}}"""))

                assertEquals(CloseReason.Codes.TOO_BIG.code, closedWith()?.code)
            }
            assertNull(withTimeoutOrNull(300) { messaging.received.receive() }, "the frame reached no module")
            delay(200)
            assertEquals(false, hub.deliver(user, frame(1)), "the closed socket was unregistered")

            client.webSocket("/v1/ws") {
                authenticate()
                assertTrue(hub.deliver(user, frame(2)))
                assertEquals(2, nextFrame().seq, "a new socket works as before")
            }
        }

    @Test
    fun `an auth frame over 64 KB is closed with too big`() =
        testApplication {
            serve(hub())
            val client = createClient { install(ClientWebSockets) }
            val padding = "x".repeat(SocketHub.MAX_FRAME_BYTES.toInt())

            client.webSocket("/v1/ws") {
                send(Frame.Text("""{"type":"auth","accessToken":"$padding"}"""))

                assertEquals(CloseReason.Codes.TOO_BIG.code, closedWith()?.code)
            }
        }

    @Test
    fun `a malformed last is closed with invalid last, not auth required`() =
        testApplication {
            serve(hub())
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                send(Frame.Text("""{"type":"auth","accessToken":"good","last":[{"module":"users","seq":"x"}]}"""))

                assertEquals(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid last"), closedWith())
            }
        }

    @Test
    fun `each refused auth frame gets its own close reason`() =
        testApplication {
            serve(hub())
            val client = createClient { install(ClientWebSockets) }
            val cases =
                mapOf(
                    """{"module":"messaging","type":"ack"}""" to "auth required",
                    "not json" to "auth required",
                    """{"type":"auth","accessToken":"good","last":[1]}""" to "invalid last",
                    """{"type":"auth","accessToken":"good","last":{"users":4}}""" to "invalid last",
                    """{"type":"auth","accessToken":"good","last":null}""" to "invalid last",
                    """{"type":"auth","accessToken":"good","last":[{"module":"users"}]}""" to "invalid last",
                    """{"type":"auth","accessToken":"good","last":[{"module":"users","seq":-1}]}""" to "invalid last",
                    """{"type":"auth","accessToken":"good","last":[{"module":"users","seq":1},""" +
                        """{"module":"users","seq":2}]}""" to "invalid last",
                    """{"type":"auth","accessToken":"bad","last":[{"module":"users","seq":4}]}""" to "unauthorized",
                )

            cases.forEach { (first, reason) ->
                client.webSocket("/v1/ws") {
                    send(Frame.Text(first))
                    assertEquals(reason, closedWith()?.message, first)
                }
            }
        }

    @Test
    fun `signing out sends session revoked and closes only that session's socket`() =
        testApplication {
            val redis = MemoryRedis()
            val hub = hub(redis)
            hub.start()
            serve(hub)
            val signedOut = gatewayIdentitySubscription(hub).handlers.getValue(SignedOut.TYPE)
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                val ended = this
                authenticate("good")
                client.webSocket("/v1/ws") {
                    authenticate("later")
                    redis.awaitReady("fanout", 1, "conn:$user", "node-1")

                    signedOut.handle(EventEnvelope.of(SignedOut(user, session), SignedOut.serializer()))

                    val revoked = ended.nextFrame()
                    assertEquals("system" to "session.revoked", revoked.module to revoked.type)
                    assertEquals(SessionRevokedFrame, revoked.decodeData<SessionRevokedFrame>())
                    assertEquals("{}", revoked.data.toString())
                    assertEquals(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "session revoked"), ended.closedWith())

                    assertTrue(hub.deliver(user, frame(9)))
                    assertEquals(9, nextFrame().seq, "the new session's socket stays open")
                }
            }
        }

    @Test
    fun `a sign-out reaches the old session's socket on node one while rt conn points at node two`() =
        testApplication {
            val redis = MemoryRedis()
            val (nodeOne, nodeTwo) = twoNodes(redis)
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/one/ws") {
                val ended = this
                authenticate("good")
                client.webSocket("/two/ws") {
                    authenticate("later")
                    redis.awaitReady("fanout", 2, "conn:$user", "node-2")

                    gatewayIdentitySubscription(nodeTwo)
                        .handlers
                        .getValue(SignedOut.TYPE)
                        .handle(EventEnvelope.of(SignedOut(user, session), SignedOut.serializer()))

                    assertEquals("session.revoked", ended.nextFrame().type)
                    assertEquals("session revoked", ended.closedWith()?.message)
                    val revoke = CircasoJson.decodeFromString(NodeMessage.serializer(), redis.published.single().second)
                    assertEquals("fanout", redis.published.single().first, "one publish every node reads")
                    assertEquals(NodeMessage.Revoke(user, sessionId = session), revoke, "by session, not by phone")

                    assertTrue(nodeOne.deliver(user, frame(9)))
                    assertEquals(9, nextFrame().seq, "the newer session's socket on node two stays open")
                }
            }
        }

    @Test
    fun `a replaced phone's socket on node one is revoked while the new phone's on node two stays open`() =
        testApplication {
            val redis = MemoryRedis()
            val (_, nodeTwo) = twoNodes(redis)
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/one/ws") {
                val oldPhone = this
                authenticate("good")
                client.webSocket("/two/ws") {
                    authenticate("new-phone")
                    redis.awaitReady("fanout", 2, "conn:$user", "node-2")

                    gatewayIdentitySubscription(nodeTwo)
                        .handlers
                        .getValue(DeviceReplaced.TYPE)
                        .handle(
                            EventEnvelope.of(DeviceReplaced(user, device, newDevice), DeviceReplaced.serializer()),
                        )

                    assertEquals("session.revoked", oldPhone.nextFrame().type)
                    assertEquals("session revoked", oldPhone.closedWith()?.message)
                    assertTrue(nodeTwo.deliver(user, frame(3)))
                    assertEquals(3, nextFrame().seq, "the new phone's socket stays open")
                }
            }
        }

    @Test
    fun `a relinked account has every socket of that user closed on every node, and nobody else's`() =
        testApplication {
            val redis = MemoryRedis()
            val (nodeOne, _) = twoNodes(redis)
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/one/ws") {
                val first = this
                authenticate("good")
                client.webSocket("/two/ws") {
                    val second = this
                    authenticate("new-phone")
                    client.webSocket("/two/ws") {
                        authenticate("other")
                        redis.awaitReady("fanout", 2, "conn:$otherUser", "node-2")

                        gatewayIdentitySubscription(nodeOne)
                            .handlers
                            .getValue(AccountRelinked.TYPE)
                            .handle(EventEnvelope.of(AccountRelinked(user, "google"), AccountRelinked.serializer()))

                        listOf(first, second).forEach { socket ->
                            assertEquals("session.revoked", socket.nextFrame().type)
                            assertEquals("session revoked", socket.closedWith()?.message)
                        }
                        assertTrue(nodeOne.deliver(otherUser, frame(4)))
                        assertEquals(4, nextFrame().seq, "another user's socket stays open")
                    }
                }
            }
        }

    @Test
    fun `a node message that fails is skipped and the channel keeps delivering`() =
        testApplication {
            val redis = MemoryRedis()
            val hub = hub(redis)
            hub.start()
            serve(hub)
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/v1/ws") {
                authenticate()
                redis.awaitReady("fanout", 1, "conn:$user", "node-1")
                redis.failNextIncrement.set(true)

                hub.revoke(user)
                hub.deliverMany(listOf(user), frame(6, "messaging", "receipt"))

                assertEquals(6, nextFrame().seq, "the fan-out listener survived the failed revoke")
                assertTrue(hub.deliver(user, frame(7)))
                assertEquals(7, nextFrame().seq)
            }
        }

    @Test
    fun `an incoming call wakes the phone data-only for the ring, and a missed call is an alert`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)
            val (caller, callee) = List(2) { UserId.new() }
            val callId = CallId.new()

            realtime.deliver(
                callee,
                RealtimeEnvelope.of(
                    CallFrames.MODULE,
                    CallFrames.RINGING,
                    1,
                    CallRingingFrame(callId, chat, "VIDEO", caller, "Wanjiru"),
                    CallRingingFrame.serializer(),
                ),
            )

            fun ended(reason: String, calleeState: String) =
                RealtimeEnvelope.of(
                    CallFrames.MODULE,
                    CallFrames.ENDED,
                    2,
                    CallSnapshot(
                        callId = callId,
                        chatId = chat,
                        kind = "VOICE",
                        state = "ENDED",
                        startedBy = caller,
                        createdAt = "2026-10-29T10:00:00Z",
                        endReason = reason,
                        participants =
                            listOf(
                                CallParticipantSnapshot(caller, "Wanjiru", "LEFT", true, false, emptyList()),
                                CallParticipantSnapshot(callee, "Otieno", calleeState, true, false, emptyList()),
                            ),
                    ),
                    CallSnapshot.serializer(),
                )
            realtime.deliver(callee, ended("MISSED", "MISSED"))
            realtime.deliver(caller, ended("MISSED", "MISSED"))
            realtime.deliver(callee, ended("CANCELLED", "MISSED"))
            realtime.deliver(callee, ended("COMPLETED", "LEFT"))

            val (ring, missed, cancelled) = push.sent.map { it.second }
            assertEquals(3, push.sent.size, "the caller and a finished call push nothing")
            assertTrue(ring.dataOnly)
            assertEquals(45L, ring.ttlSeconds)
            assertEquals("Incoming video call", ring.title)
            assertEquals(callId.toString(), ring.data.callId)
            assertEquals("call.ringing", ring.data.kind)
            assertEquals("Wanjiru", ring.data.callerName)
            assertTrue(missed.dataOnly, "the phone words it, so Notification privacy can hide the name")
            assertEquals("Missed call from Wanjiru", missed.body)
            assertEquals("call.missed", missed.data.kind)
            assertTrue(cancelled.dataOnly, "the phone stops ringing and words it itself")
        }

    @Test
    fun `wallet frames push the end of a payment, money received and a setup request, never an amount`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)
            val user = UserId.new()
            val amount = WalletAmount("USDC", "20000000", "20")
            val paidBy = TransferId.new()

            fun transfer(state: String) =
                RealtimeEnvelope.of(
                    WalletFrames.MODULE,
                    WalletFrames.TRANSFER_UPDATED,
                    1,
                    TransferUpdatedFrame(TransferId.new(), state, "BASE", amount),
                    TransferUpdatedFrame.serializer(),
                )
            realtime.deliver(user, transfer("SUBMITTED"))
            realtime.deliver(user, transfer("CONFIRMED"))
            realtime.deliver(user, transfer("FAILED"))
            realtime.deliver(
                user,
                RealtimeEnvelope.of(
                    WalletFrames.MODULE,
                    WalletFrames.PAYMENT_RECEIVED,
                    2,
                    PaymentReceivedFrame(
                        "t-1",
                        "BASE",
                        amount,
                        UserId.new(),
                        "Amina",
                        transferId = paidBy,
                        chatId = "c-1",
                    ),
                    PaymentReceivedFrame.serializer(),
                ),
            )
            realtime.deliver(
                user,
                RealtimeEnvelope.of(
                    WalletFrames.MODULE,
                    WalletFrames.SETUP_REQUESTED,
                    3,
                    SetupRequestedFrame(UserId.new(), "Amina"),
                    SetupRequestedFrame.serializer(),
                ),
            )
            realtime.deliver(
                user,
                RealtimeEnvelope.of(
                    WalletFrames.MODULE,
                    WalletFrames.WALLET_READY,
                    4,
                    WalletReadyFrame("BASE"),
                    WalletReadyFrame.serializer(),
                ),
            )

            assertEquals(
                listOf(
                    "Payment sent",
                    "Payment failed",
                    "Amina sent you money",
                    "Amina wants to send you money. Set up your wallet",
                ),
                push.sent.map { it.second.body },
            )
            assertTrue(push.sent.none { "20" in it.second.body || "20" in it.second.title })
            // Ids only, for the phone to word the amount itself and open the chat at the card (2026-11-06).
            val received = push.sent[2].second.data
            assertEquals("payment.received", received.kind)
            assertEquals(paidBy.toString(), received.transferId)
            assertEquals("t-1", received.transactionId)
            assertEquals("c-1", received.chatId)
            assertEquals(listOf("transfer.updated", "transfer.updated"), push.sent.take(2).map { it.second.data.kind })
            assertTrue(push.sent.take(2).all { it.second.data.transferId != null })
            assertEquals(
                "wallet.setup",
                push.sent[3]
                    .second.data.kind,
            )
        }

    @Test
    fun `group money pushes are data-only with ids, so the phone names the group and the amount`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)
            val member = UserId.new()
            val contributor = UserId.new()
            val chat = ChatId.new()
            val requestId =
                com.circaso.chama.api.RequestId
                    .new()
            val amount = WalletAmount("USDC", "20000000", "20")
            realtime.deliver(
                member,
                RealtimeEnvelope.of(
                    com.circaso.chama.api.ChamaFrames.MODULE,
                    com.circaso.chama.api.ChamaFrames.CONTRIBUTION_ADDED,
                    1,
                    com.circaso.chama.api.ContributionAddedFrame(
                        chat,
                        contributor,
                        "Wanjiru",
                        "Holiday Fund",
                        amount,
                        amount,
                        amount,
                    ),
                    com.circaso.chama.api.ContributionAddedFrame
                        .serializer(),
                ),
            )
            realtime.deliver(
                member,
                RealtimeEnvelope.of(
                    com.circaso.chama.api.ChamaFrames.MODULE,
                    com.circaso.chama.api.ChamaFrames.REQUEST_UPDATED,
                    2,
                    com.circaso.chama.api.RequestUpdatedFrame(
                        requestId,
                        chat,
                        "PENDING",
                        contributor,
                        "Otieno",
                        "Holiday Fund",
                        amount,
                        0,
                        1,
                        waitingOn = listOf(member),
                    ),
                    com.circaso.chama.api.RequestUpdatedFrame
                        .serializer(),
                ),
            )

            val (contribution, request) = push.sent.map { it.second }
            assertTrue(contribution.dataOnly && request.dataOnly)
            assertEquals("chama.contribution", contribution.data.kind)
            assertEquals(chat.toString(), contribution.data.chatId)
            assertEquals(contributor.toString(), contribution.data.userId)
            assertEquals("chama.request.pending", request.data.kind)
            assertEquals(requestId.toString(), request.data.requestId)
            assertTrue(push.sent.none { "20" in it.second.body || "20" in it.second.title })
        }

    @Test
    fun `a reaction on my moment pushes the emoji and the name, a plain view or a cleared reaction never`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)
            val poster = UserId.new()
            val momentId = MomentId.new()
            val at = Instant.parse("2026-11-03T09:00:00Z")

            fun viewed(
                seq: Long,
                reaction: String?,
                name: String?,
            ) = RealtimeEnvelope.of(
                StatusFrames.MODULE,
                StatusFrames.VIEWED,
                seq,
                MomentViewedFrame(momentId, UserId.new(), at, reaction != null, viewerName = name, reaction = reaction),
                MomentViewedFrame.serializer(),
            )
            realtime.deliver(poster, viewed(1, null, null)) // a plain view
            realtime.deliver(poster, viewed(2, "😂", "Amina"))
            realtime.deliver(poster, viewed(3, null, null)) // cleared
            realtime.deliver(poster, viewed(4, "🔥", null)) // no name: status found no profile
            realtime.deliver(poster, viewed(5, "\u2764\uFE0F", "Baraka"))

            assertEquals(
                listOf("Amina reacted 😂 to your moment", "Baraka reacted \u2764\uFE0F to your moment"),
                push.sent.map { it.second.body },
            )
            assertTrue(push.sent.all { it.second.title == "Circaso" && it.second.data.kind == "moment.loved" })
        }

    @Test
    fun `space pushes - post text only for public spaces, and money into a space wallet names the space`() =
        runBlocking {
            val push = RecordingPush()
            val realtime = GatewayRealtime(hub(), push, registrations)
            val member = UserId.new()
            val spaceId =
                com.circaso.spaces.api.SpaceId
                    .new()

            fun posted(visibility: String) =
                RealtimeEnvelope.of(
                    com.circaso.spaces.api.SpacesFrames.MODULE,
                    com.circaso.spaces.api.SpacesFrames.POST_NEW,
                    1,
                    com.circaso.spaces.api.PostNewFrame(
                        spaceId,
                        "Book Club",
                        com.circaso.spaces.api.FramePost(
                            postId =
                                com.circaso.spaces.api.PostId
                                    .new(),
                            spaceId = spaceId,
                            author =
                                com.circaso.spaces.api
                                    .FramePerson(UserId.new(), "Njeri"),
                            text = "Chapter 7 tonight",
                            createdAt = "2026-11-05T09:00:00Z",
                        ),
                        notify = true,
                        visibility = visibility,
                    ),
                    com.circaso.spaces.api.PostNewFrame
                        .serializer(),
                )
            realtime.deliver(member, posted("PUBLIC"))
            realtime.deliver(member, posted("MEMBERS_ONLY"))
            realtime.deliver(member, posted("INVITE_ONLY"))
            val amount = WalletAmount("USDC", "20000000", "20")
            realtime.deliver(
                member,
                RealtimeEnvelope.of(
                    WalletFrames.MODULE,
                    WalletFrames.PAYMENT_RECEIVED,
                    2,
                    PaymentReceivedFrame(
                        "t-1",
                        "BASE",
                        amount,
                        UserId.new(),
                        "Amina",
                        com.circaso.wallet.api.WalletId
                            .new(),
                        "SPACE",
                        "Sneaker Drop",
                    ),
                    PaymentReceivedFrame.serializer(),
                ),
            )

            assertEquals(
                listOf(
                    "Njeri: Chapter 7 tonight",
                    "New post in Book Club",
                    "New post in Book Club",
                    "Amina paid Sneaker Drop",
                ),
                push.sent.map { it.second.body },
            )
        }

    /** Two nodes sharing [redis], both listening, served at `/one/ws` and `/two/ws`. */
    private fun ApplicationTestBuilder.twoNodes(redis: MemoryRedis): Pair<SocketHub, SocketHub> {
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
        return nodeOne to nodeTwo
    }
}
