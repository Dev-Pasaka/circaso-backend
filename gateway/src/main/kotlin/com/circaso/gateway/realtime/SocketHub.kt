package com.circaso.gateway.realtime

import com.circaso.identity.api.Principal
import com.circaso.identity.api.SessionId
import com.circaso.identity.api.TokenVerifier
import com.circaso.infrastructure.api.RedisScope
import com.circaso.observability.api.Metrics
import com.circaso.observability.api.Telemetry
import com.circaso.shared.CircasoJson
import com.circaso.shared.ClientFrame
import com.circaso.shared.DeviceId
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeInboundModule
import com.circaso.shared.RealtimeModule
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.circaso.gateway.realtime.SocketHub")

private val AUTH_WINDOW_MILLIS = 10_000L
private val HEARTBEAT_MILLIS = 25_000L
private val CONN_TTL: Duration = Duration.ofSeconds(60)
private val SEQ_TTL: Duration = Duration.ofDays(8)

/** `rt:fanout`: every node subscribes, so one publish reaches a frame's users wherever their sockets are. */
private const val FANOUT_CHANNEL = "fanout"

/** Close reasons the phone can tell apart (Mobile integration → Realtime socket). */
private const val AUTH_REQUIRED = "auth required"
private const val INVALID_LAST = "invalid last"
private const val UNAUTHORIZED = "unauthorized"
private const val SESSION_REVOKED = "session revoked"

private val lastSerializer = MapSerializer(String.serializer(), Long.serializer())

/** What a node publishes on `rt:node:{nodeId}` for the node that holds the user's socket, or on `rt:fanout` for all. */
@Serializable
internal sealed interface NodeMessage {
    @Serializable
    data class Frame(val userId: UserId, val frame: RealtimeEnvelope) : NodeMessage

    /** On `rt:fanout`: every node writes [frame] to the sockets it holds for [userIds] and ignores the rest. */
    @Serializable
    data class Frames(val userIds: List<UserId>, val frame: RealtimeEnvelope) : NodeMessage

    /**
     * Tell the user's sockets they are revoked, then close them: only [deviceId]'s when set (a replaced phone), only
     * [sessionId]'s when set (a sign-out), every socket of the user when both are null.
     */
    @Serializable
    data class Revoke(val userId: UserId, val deviceId: DeviceId? = null, val sessionId: SessionId? = null) :
        NodeMessage
}

/**
 * The WebSocket hub (Realtime and push). One socket per phone: the first frame authenticates with the access
 * token, the app sends its last `seq` per module and gets what it missed from each module's [RealtimeReplay],
 * then frames flow until the phone closes it. Each node records the sockets it holds in `rt:conn:{userId}` and
 * listens on its own channel `rt:node:{nodeId}`, so a frame published from any node reaches the right socket. A
 * frame for many users goes once on `rt:fanout`, which every node reads.
 * Frames the phone sends after authenticating go to the `RealtimeInbound` of the module they name, at most
 * [FrameRateLimiter.FRAMES_PER_SECOND] a second per socket.
 */
class SocketHub(
    private val redis: RedisScope,
    private val verifier: TokenVerifier,
    /** Looked up when a phone connects, not when the hub is built: the modules that replay also send through the hub. */
    private val replays: () -> List<RealtimeModule>,
    /** The modules that take frames from the phone, looked up the same way as [replays]. */
    private val inbound: () -> List<RealtimeInboundModule>,
    private val scope: CoroutineScope,
    private val telemetry: Telemetry,
    val nodeId: String = Uuid7.generate().toString(),
    /** Monotonic milliseconds for the per-socket frame limit; tests pass their own. */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Connection(
        val userId: UserId,
        val deviceId: DeviceId,
        val sessionId: SessionId,
        val session: WebSocketSession,
    )

    /** How the auth frame went: a phone to serve, a reason to close with, or a socket that is already closing. */
    private sealed interface Auth {
        data class Accepted(val principal: Principal, val last: Map<String, Long>) : Auth

        data class Refused(val reason: String) : Auth

        data object Gone : Auth
    }

    private val connections = ConcurrentHashMap<UserId, MutableSet<Connection>>()

    /** Starts listening on this node's channel and on the fan-out channel. Called once, when the application starts. */
    fun start() {
        listen("node:$nodeId")
        listen(FANOUT_CHANNEL)
    }

    private fun listen(channel: String) {
        redis
            .subscribe(channel)
            .onEach { raw ->
                runCatching { CircasoJson.decodeFromString(NodeMessage.serializer(), raw) }
                    .onSuccess { handle(it) }
                    .onFailure { log.warn("unreadable node message", it) }
            }.launchIn(scope)
    }

    private suspend fun handle(message: NodeMessage) {
        when (message) {
            is NodeMessage.Frame -> sendLocal(message.userId, message.frame)
            is NodeMessage.Frames -> message.userIds.forEach { sendLocal(it, message.frame) }
            is NodeMessage.Revoke -> revokeLocal(message)
        }
    }

    /** Hands a frame to the node that holds the user's socket; false if no node does (the caller falls back to push). */
    suspend fun deliver(userId: UserId, frame: RealtimeEnvelope): Boolean {
        val node = redis.get("conn:$userId") ?: return false
        if (node == nodeId) {
            handle(NodeMessage.Frame(userId, frame))
        } else {
            redis.publish(
                "node:$node",
                CircasoJson.encodeToString(NodeMessage.serializer(), NodeMessage.Frame(userId, frame)),
            )
        }
        return true
    }

    /**
     * Hands one frame to many users with one publish on `rt:fanout` and no Redis read: every node, this one included
     * (through its own subscription), writes it to the sockets it holds for those users. A plain `PUBLISH` reaches
     * every node of a Redis cluster too.
     */
    suspend fun deliverMany(userIds: Collection<UserId>, frame: RealtimeEnvelope) {
        val users = userIds.distinct()
        if (users.isEmpty()) return
        val message = NodeMessage.Frames(users, frame)
        redis.publish(FANOUT_CHANNEL, CircasoJson.encodeToString(NodeMessage.serializer(), message))
    }

    /**
     * Of [userIds], those no node holds a socket for, from single-key `GET`s of `rt:conn:{userId}` sent as one
     * pipeline. Only asked for frames worth a push, so receipts and typing never read Redis.
     */
    suspend fun withoutSocket(userIds: Collection<UserId>): List<UserId> {
        val users = userIds.distinct()
        if (users.isEmpty()) return emptyList()
        val nodes = redis.getPipelined(users.map { "conn:$it" })
        return users.filterIndexed { index, _ -> nodes[index] == null }
    }

    /**
     * Sends `system/session.revoked` and closes the socket, on whichever node holds it: only [deviceId]'s sockets
     * when given (a replaced phone), only [sessionId]'s when given (a sign-out), otherwise every socket of the user.
     */
    suspend fun revoke(
        userId: UserId,
        deviceId: DeviceId? = null,
        sessionId: SessionId? = null,
    ) {
        val node = redis.get("conn:$userId") ?: return
        val message = NodeMessage.Revoke(userId, deviceId, sessionId)
        if (node == nodeId) {
            revokeLocal(message)
        } else {
            redis.publish("node:$node", CircasoJson.encodeToString(NodeMessage.serializer(), message))
        }
    }

    /** The next `seq` for the gateway's own `system` frames, which are not replayed. */
    suspend fun nextSystemSeq(userId: UserId): Long {
        val key = "seq:$userId"
        return redis.incrementRenewing(key, SEQ_TTL)
    }

    private suspend fun revokeLocal(message: NodeMessage.Revoke) {
        val targets =
            connections[message.userId]
                ?.filter { message.deviceId == null || it.deviceId == message.deviceId }
                ?.filter { message.sessionId == null || it.sessionId == message.sessionId }
                .orEmpty()
        if (targets.isEmpty()) return
        val frame =
            RealtimeEnvelope(
                "system",
                "session.revoked",
                Uuid7.generate().toString(),
                nextSystemSeq(message.userId),
                Instant.now(),
                JsonObject(emptyMap()),
            )
        targets.forEach {
            send(it, frame)
            runCatching { it.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, SESSION_REVOKED)) }
        }
    }

    private suspend fun sendLocal(userId: UserId, frame: RealtimeEnvelope) {
        connections[userId]?.toList()?.forEach { send(it, frame) }
    }

    private suspend fun send(connection: Connection, frame: RealtimeEnvelope) {
        runCatching {
            connection.session.send(
                Frame.Text(CircasoJson.encodeToString(RealtimeEnvelope.serializer(), frame)),
            )
        }.onFailure { log.debug("socket write failed", it) }
    }

    private fun register(connection: Connection) {
        connections.computeIfAbsent(connection.userId) { ConcurrentHashMap.newKeySet() }.add(connection)
    }

    private suspend fun unregister(connection: Connection) {
        val set = connections[connection.userId] ?: return
        set.remove(connection)
        if (set.isEmpty()) {
            connections.remove(connection.userId, set)
            // Only drop the mapping if it still points at this node: the phone may have reconnected elsewhere.
            if (redis.get("conn:${connection.userId}") == nodeId) redis.delete("conn:${connection.userId}")
        }
    }

    fun routes(route: Route) =
        with(route) {
            webSocket("ws") {
                val auth = authenticate()
                if (auth is Auth.Refused) close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, auth.reason))
                if (auth !is Auth.Accepted) return@webSocket
                val principal = auth.principal

                val connection = Connection(principal.userId, principal.deviceId, principal.sessionId, this)
                register(connection)
                redis.set("conn:${principal.userId}", nodeId, CONN_TTL)
                val heartbeat =
                    scope.launch {
                        while (true) {
                            delay(HEARTBEAT_MILLIS)
                            redis.set("conn:${principal.userId}", nodeId, CONN_TTL)
                            runCatching { send(Frame.Text("""{"type":"ping"}""")) }
                        }
                    }
                try {
                    replay(connection, auth.last)
                    readFrames(connection)
                } finally {
                    heartbeat.cancel()
                    unregister(connection)
                }
            }
        }

    /**
     * Reads the auth frame. Anything but an auth frame is refused with `auth required`; an auth frame whose `last`
     * isn't a map of module to `seq` with `invalid last`, so the phone can tell a bug in its own frame from a missing
     * token; a token that doesn't verify as an app token with `unauthorized`.
     */
    private suspend fun WebSocketSession.authenticate(): Auth {
        val text = firstText() ?: return Auth.Gone
        val hello =
            runCatching { CircasoJson.decodeFromString(Hello.serializer(), text) }
                .getOrNull()
                ?.takeIf { it.type == "auth" }
                ?: return Auth.Refused(AUTH_REQUIRED)
        val last = parseLast(hello.last) ?: return Auth.Refused(INVALID_LAST)
        val principal =
            (verifier.verify(hello.accessToken) as? Resource.Success)
                ?.data
                ?.takeIf { it.scope == Scope.APP }
                ?: return Auth.Refused(UNAUTHORIZED)
        return Auth.Accepted(principal, last)
    }

    /** The phone's last `seq` per module: empty when it sent none, null when it isn't a map of module to number. */
    private fun parseLast(raw: JsonElement?): Map<String, Long>? {
        if (raw == null) return emptyMap()
        return runCatching { CircasoJson.decodeFromJsonElement(lastSerializer, raw) }.getOrNull()
    }

    /**
     * The first frame as text: an empty string for a frame that isn't text or doesn't arrive within the auth window,
     * so it is refused; null when the socket is already closing (the phone left, or Ktor closed it for a frame over
     * [MAX_FRAME_BYTES] with 1009 "too big"), when there is no one to tell.
     */
    private suspend fun WebSocketSession.firstText(): String? =
        try {
            withTimeoutOrNull(AUTH_WINDOW_MILLIS) { (incoming.receive() as? Frame.Text)?.readText() } ?: ""
        } catch (_: ClosedReceiveChannelException) {
            null
        } catch (_: FrameTooBigException) {
            null
        }

    /**
     * Hands the phone's frames to their modules until the socket closes. Frames over the per-socket limit are dropped
     * unread and counted (decision 2026-10-25). A frame over [MAX_FRAME_BYTES] makes Ktor close the socket with 1009
     * "too big" and end [incoming] with [FrameTooBigException], which ends this socket alone.
     */
    private suspend fun WebSocketSession.readFrames(connection: Connection) {
        val limiter = FrameRateLimiter(nowMillis = nowMillis)
        try {
            for (frame in incoming) {
                if (!limiter.tryAcquire()) {
                    telemetry.count(Metrics.GATEWAY_SOCKET_FRAMES_DROPPED, mapOf("reason" to "rate_limited"))
                    continue
                }
                if (frame is Frame.Text) receive(connection, frame.readText())
            }
        } catch (_: FrameTooBigException) {
            log.debug("socket closed: frame too big")
        }
    }

    /**
     * A frame from an authenticated phone (typing, read receipts, presence, acks), handed to the module it names. A
     * frame that names no registered module, or isn't a [ClientFrame] at all (a pong), is dropped. A failing handler
     * never closes the socket.
     */
    private suspend fun receive(connection: Connection, text: String) {
        val frame = runCatching { CircasoJson.decodeFromString(ClientFrame.serializer(), text) }.getOrNull()
        val handler = frame?.let { incoming -> inbound().firstOrNull { it.module == incoming.module } }
        if (frame == null || handler == null) {
            log.debug("client frame ignored")
            return
        }
        val result =
            try {
                handler.inbound.receive(connection.userId, connection.deviceId, frame)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn(
                    "client frame failed: module={} type={} error={}",
                    frame.module,
                    frame.type,
                    failure.javaClass.name,
                )
                return
            }
        if (result is Resource.Error) {
            log.debug("client frame refused: module={} type={} code={}", frame.module, frame.type, result.error.code)
        }
    }

    private suspend fun replay(connection: Connection, last: Map<String, Long>) {
        for (module in replays()) {
            val since = last[module.module] ?: continue
            when (val missed = module.replay.since(connection.userId, since)) {
                is Resource.Success -> missed.data.forEach { send(connection, it) }
                is Resource.Error -> log.warn("replay failed: module={} code={}", module.module, missed.error.code)
            }
        }
    }

    /** `last` stays raw here so a malformed one is told apart from a frame that isn't an auth frame at all. */
    @Serializable
    private data class Hello(val type: String, val accessToken: String = "", val last: JsonElement? = null)

    companion object {
        /** The largest frame a phone may send (plan GW1: 64 KB, the largest chat ciphertext). */
        const val MAX_FRAME_BYTES = 64L * 1024
    }
}
