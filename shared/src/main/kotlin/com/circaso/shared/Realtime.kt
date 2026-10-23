package com.circaso.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
}

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
