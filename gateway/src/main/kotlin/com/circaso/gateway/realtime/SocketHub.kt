package com.circaso.gateway.realtime

import com.circaso.identity.api.SessionId
import com.circaso.identity.api.TokenVerifier
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.CircasoJson
import com.circaso.shared.DeviceId
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeModule
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.circaso.gateway.realtime.SocketHub")

private val AUTH_WINDOW_MILLIS = 10_000L
private val HEARTBEAT_MILLIS = 25_000L
private val CONN_TTL: Duration = Duration.ofSeconds(60)
private val SEQ_TTL: Duration = Duration.ofDays(8)

/** What one node publishes on `rt:node:{nodeId}` for the node that holds the user's socket. */
@Serializable
internal sealed interface NodeMessage {
    @Serializable
    data class Frame(val userId: UserId, val frame: RealtimeEnvelope) : NodeMessage

    /** Tell [deviceId]'s socket (or every socket of the user when null) it is revoked, then close it. */
    @Serializable
    data class Revoke(val userId: UserId, val deviceId: DeviceId? = null) : NodeMessage
}

/**
 * The WebSocket hub (Realtime and push). One socket per phone: the first frame authenticates with the access
 * token, the app sends its last `seq` per module and gets what it missed from each module's [RealtimeReplay],
 * then frames flow until the phone closes it. Each node records the sockets it holds in `rt:conn:{userId}` and
 * listens on its own channel `rt:node:{nodeId}`, so a frame published from any node reaches the right socket.
 */
class SocketHub(
    private val redis: RedisScope,
    private val verifier: TokenVerifier,
    /** Looked up when a phone connects, not when the hub is built: the modules that replay also send through the hub. */
    private val replays: () -> List<RealtimeModule>,
    private val scope: CoroutineScope,
    val nodeId: String = Uuid7.generate().toString(),
) {
    private class Connection(val userId: UserId, val deviceId: DeviceId, val sessionId: SessionId, val session: WebSocketSession)

    private val connections = ConcurrentHashMap<UserId, MutableSet<Connection>>()

    /** Starts listening on this node's channel. Called once, when the application starts. */
    fun start() {
        redis.subscribe("node:$nodeId")
            .onEach { raw ->
                runCatching { CircasoJson.decodeFromString(NodeMessage.serializer(), raw) }
                    .onSuccess { handle(it) }
                    .onFailure { log.warn("unreadable node message", it) }
            }
            .launchIn(scope)
    }

    private suspend fun handle(message: NodeMessage) {
        when (message) {
            is NodeMessage.Frame -> connections[message.userId]?.toList()?.forEach { send(it, message.frame) }
            is NodeMessage.Revoke -> revokeLocal(message.userId, message.deviceId)
        }
    }

    /** Hands a frame to the node that holds the user's socket; false if no node does (the caller falls back to push). */
    suspend fun deliver(userId: UserId, frame: RealtimeEnvelope): Boolean {
        val node = redis.get("conn:$userId") ?: return false
        if (node == nodeId) {
            handle(NodeMessage.Frame(userId, frame))
        } else {
            redis.publish("node:$node", CircasoJson.encodeToString(NodeMessage.serializer(), NodeMessage.Frame(userId, frame)))
        }
        return true
    }

    /** Sends `system/session.revoked` to the phone and closes its socket, on whichever node holds it. */
    suspend fun revoke(userId: UserId, deviceId: DeviceId? = null) {
        val node = redis.get("conn:$userId") ?: return
        if (node == nodeId) {
            revokeLocal(userId, deviceId)
        } else {
            redis.publish("node:$node", CircasoJson.encodeToString(NodeMessage.serializer(), NodeMessage.Revoke(userId, deviceId)))
        }
    }

    /** The next `seq` for the gateway's own `system` frames, which are not replayed. */
    suspend fun nextSystemSeq(userId: UserId): Long {
        val key = "seq:$userId"
        return redis.incrementRenewing(key, SEQ_TTL)
    }

    private suspend fun revokeLocal(userId: UserId, deviceId: DeviceId?) {
        val targets = connections[userId]?.filter { deviceId == null || it.deviceId == deviceId } ?: return
        val frame = RealtimeEnvelope("system", "session.revoked", Uuid7.generate().toString(), nextSystemSeq(userId), Instant.now(), JsonObject(emptyMap()))
        targets.forEach {
            send(it, frame)
            runCatching { it.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "session revoked")) }
        }
    }

    private suspend fun send(connection: Connection, frame: RealtimeEnvelope) {
        runCatching { connection.session.send(Frame.Text(CircasoJson.encodeToString(RealtimeEnvelope.serializer(), frame))) }
            .onFailure { log.debug("socket write failed", it) }
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

    fun routes(route: Route) = with(route) {
        webSocket("ws") {
            val first = withTimeoutOrNull(AUTH_WINDOW_MILLIS) { incoming.receive() as? Frame.Text }
            val hello = first?.let { runCatching { CircasoJson.decodeFromString(Hello.serializer(), it.readText()) }.getOrNull() }
            if (hello == null || hello.type != "auth") {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth required"))
                return@webSocket
            }
            val principal = (verifier.verify(hello.accessToken) as? Resource.Success)?.data?.takeIf { it.scope == Scope.APP }
            if (principal == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }

            val connection = Connection(principal.userId, principal.deviceId, principal.sessionId, this)
            register(connection)
            redis.set("conn:${principal.userId}", nodeId, CONN_TTL)
            val heartbeat = scope.launch {
                while (true) {
                    delay(HEARTBEAT_MILLIS)
                    redis.set("conn:${principal.userId}", nodeId, CONN_TTL)
                    runCatching { send(Frame.Text("""{"type":"ping"}""")) }
                }
            }
            try {
                replay(connection, hello.last)
                for (frame in incoming) {
                    // Only typing, read receipts, presence and acks travel this way; none are handled yet.
                    if (frame is Frame.Text) log.debug("client frame ignored")
                }
            } finally {
                heartbeat.cancel()
                unregister(connection)
            }
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

    @Serializable
    private data class Hello(val type: String, val accessToken: String = "", val last: Map<String, Long> = emptyMap())
}
