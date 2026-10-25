package com.circaso.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    val data: JsonElement,
)

/** How a module sends a frame to one user. The gateway finds the node holding the socket, or falls back to push. */
interface Realtime {
    suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope): Resource<Unit>

    /**
     * The same frame to many users at once (a group message, a receipt batch, typing). [deliver] costs one Redis
     * read and one publish per user, 999 of each for a full group; this reads every user's node in one step and
     * publishes once per node, then hands the users with no socket to push when the frame is push-worthy.
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
    val data: JsonElement = JsonObject(emptyMap()),
)

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
