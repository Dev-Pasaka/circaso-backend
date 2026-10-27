package com.circaso.shared

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.time.Instant

/** Every server-to-client frame has this shape; the app routes on [module], then [type]. */
@Serializable
data class RealtimeEnvelope(
    val module: String,
    /** For example `contact.joined`. */
    val type: String,
    /** Identifies this frame. */
    val id: String,
    /** Increases per user per module, so the app can tell what it missed. */
    val seq: Long,
    @Serializable(with = InstantSerializer::class) val ts: Instant,
    /** The frame's own class, encoded by [of]; never built by hand (decision 2026-10-27). */
    val data: JsonElement,
) {
    companion object {
        /**
         * A frame whose `data` is [frame] encoded with its own serializer, so the compiler checks every field and a
         * number goes out as a number. Every producer builds its envelope here.
         */
        fun <T> of(
            module: String,
            type: String,
            seq: Long,
            frame: T,
            serializer: SerializationStrategy<T>,
            ts: Instant = Instant.now(),
            id: String = Uuid7.generate().toString(),
        ): RealtimeEnvelope =
            RealtimeEnvelope(
                module = module,
                type = type,
                id = id,
                seq = seq,
                ts = ts,
                data = CircasoJson.encodeToJsonElement(serializer, frame),
            )

        /** The `resync` frame a [RealtimeReplay] answers when the gap is older than what it keeps. */
        fun resync(
            module: String,
            seq: Long,
            ts: Instant = Instant.now(),
        ): RealtimeEnvelope = of(module, "resync", seq, ResyncFrame, ResyncFrame.serializer(), ts)
    }
}

/** Reads a frame's `data` back into its class, as a test or a consumer that kept the envelope does. */
fun <T> RealtimeEnvelope.decodeData(deserializer: DeserializationStrategy<T>): T =
    CircasoJson.decodeFromJsonElement(deserializer, data)

inline fun <reified T> RealtimeEnvelope.decodeData(): T = decodeData(serializer<T>())

/** `resync`: the gap is older than the 7 days a module keeps, so the app reloads that module over HTTP. No fields. */
@Serializable
data object ResyncFrame

/** How a module sends a frame to one user. The gateway finds the node holding the socket, or falls back to push. */
interface Realtime {
    suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope): Resource<Unit>

    /**
     * The same frame to many users at once (a group message, a receipt batch, typing). [deliver] costs one Redis
     * read and one publish per user, 999 of each for a full group; this publishes once for every node to read, and
     * looks up who has no socket, to push them, only when the frame is push-worthy.
     */
    suspend fun deliverMany(userIds: Collection<UserId>, envelope: RealtimeEnvelope): Resource<Unit>
}

/**
 * A frame the phone sends over the socket, after it has authenticated: `{"module":"messaging","type":"ack",
 * "data":{…}}`. Only acknowledgements, read receipts, typing and presence travel this way; commands use HTTP.
 */
@Serializable
data class ClientFrame(
    /** The module that handles it; the hub drops a frame whose module registered no [RealtimeInbound]. */
    val module: String,
    /** For example `ack`, `read`, `typing`. */
    val type: String,
    /** Raw until the module that handles [type] reads it with [decodeData] into that frame's class. */
    val data: JsonElement = NO_DATA,
)

/** A frame with no fields: encodes as `{}`. */
@Serializable
data object EmptyFrame

/** What a phone frame without `data` carries: `{}`, so a frame class whose fields all have defaults still reads. */
private val NO_DATA: JsonElement = CircasoJson.encodeToJsonElement(EmptyFrame.serializer(), EmptyFrame)

/**
 * The phone frame's `data` as [deserializer]'s class. Throws `SerializationException` when it doesn't fit, which the
 * hub logs and drops without closing the socket.
 */
fun <T> ClientFrame.decodeData(deserializer: DeserializationStrategy<T>): T =
    CircasoJson.decodeFromJsonElement(deserializer, data)

inline fun <reified T> ClientFrame.decodeData(): T = decodeData(serializer<T>())

/**
 * Implemented by a module that takes frames from the phone. The hub calls it in the order the frames arrive on one
 * socket, only for sockets that authenticated, with the user and phone the access token named.
 */
interface RealtimeInbound {
    suspend fun receive(
        userId: UserId,
        deviceId: DeviceId,
        frame: ClientFrame,
    ): Resource<Unit>
}

/** A module's handler for phone frames, named by the `module` those frames carry; `di` binds one per such module. */
data class RealtimeInboundModule(val module: String, val inbound: RealtimeInbound)

/**
 * Implemented by every module that sends frames. On reconnect the gateway calls it with the app's last
 * `seq`; the module returns what was missed from its own tables, or a single `resync` frame when the gap
 * is older than the 7 days it keeps, and the app then reloads that module's data over HTTP.
 */
interface RealtimeReplay {
    suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>>
}

/** A module's replay, named by the module whose frames it replays (`users`); `di` binds one per module that sends frames. */
data class RealtimeModule(val module: String, val replay: RealtimeReplay)
