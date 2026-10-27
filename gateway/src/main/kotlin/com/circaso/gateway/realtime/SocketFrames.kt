package com.circaso.gateway.realtime

import kotlinx.serialization.Serializable

/**
 * The phone's first frame on `/v1/ws`: `{"type":"auth","accessToken":"…","last":[{"module":"users","seq":1042}]}`.
 * [last] is the last `seq` the phone saw per module, empty on first connect (decision 2026-10-27: a list of
 * [ModuleSeq], not an object keyed by module).
 */
@Serializable
internal data class AuthFrame(val type: String, val accessToken: String = "", val last: List<ModuleSeq> = emptyList())

/** The last `seq` the phone saw from [module]. */
@Serializable
internal data class ModuleSeq(val module: String, val seq: Long)

/**
 * Only the parts of the first frame that say whether it is an auth frame at all, read before [AuthFrame], so a bad
 * `last` closes with `invalid last` and not `auth required`.
 */
@Serializable
internal data class AuthHeader(val type: String, val accessToken: String = "")

/** The heartbeat the hub sends every 25 seconds, outside the envelope; the phone may answer or ignore it. */
@Serializable
internal data class PingFrame(val type: String = "ping")

/** `system/session.revoked`: this phone's session ended (signed out, replaced or relinked); the socket closes next. */
@Serializable
internal data object SessionRevokedFrame
