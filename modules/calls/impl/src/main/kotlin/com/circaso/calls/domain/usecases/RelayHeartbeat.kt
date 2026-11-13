package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.HeartbeatCommand
import com.circaso.calls.domain.ports.HeartbeatCache
import com.circaso.calls.domain.ports.LiveCallFrames
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.shared.Resource

/**
 * `calls/heartbeat` from a phone in a call (2026-11-13), relayed to the others joined as `call.heartbeat` and never
 * stored. Each phone hears the others' every 2 seconds, so when someone's phone drops off the network the others
 * know within a few seconds, and tell "they are gone" apart from "I am offline" (my own media still flows, theirs
 * and their heartbeat don't). Who is joined is read from the database at most every few seconds per call (kept in
 * `cal:hb:*`), and a phone beating faster than once a second is ignored, so the relay stays cheap.
 */
class RelayHeartbeat(
    private val calls: CallRepository,
    private val cache: HeartbeatCache,
    private val live: LiveCallFrames,
) {
    suspend operator fun invoke(command: HeartbeatCommand): Resource<Unit> {
        if (!cache.take(command.callId, command.userId)) return Resource.success(Unit)
        val joined =
            cache.joined(command.callId) ?: run {
                val call = calls.find(command.callId) ?: return Resource.error(CallsError.NotFound)
                if (call.isEnded) return Resource.error(CallsError.CallEnded)
                call.joined
                    .map { it.userId }
                    .toSet()
                    .also { cache.keepJoined(command.callId, it) }
            }
        if (command.userId !in joined) return Resource.error(CallsError.NotInCall)
        live.heartbeat(joined - command.userId, command.callId, command.userId)
        return Resource.success(Unit)
    }
}
