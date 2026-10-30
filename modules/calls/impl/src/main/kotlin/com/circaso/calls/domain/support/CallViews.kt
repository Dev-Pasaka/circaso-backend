package com.circaso.calls.domain.support

import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallView
import com.circaso.integrations.api.CallMedia

/** A call with the names its tiles show and, for a REST answer, the ICE servers (with TURN credentials). */
class CallViews(private val people: People, private val media: CallMedia) {
    suspend fun view(call: Call): CallView = CallView(call, names(call), media.iceServers())

    /** For a frame: no ICE servers, so TURN credentials are never kept in the frame log. */
    suspend fun frameView(call: Call): CallView = CallView(call, names(call), emptyList())

    private suspend fun names(call: Call) = people.names(call.participants.map { it.userId }.toSet())
}
