package com.circaso.calls.domain.support

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.ports.CallsOutbox
import com.circaso.calls.domain.ports.Transactor
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.envelope

/**
 * Every change to a call that already exists, in one shape: lock the call, decide on what is there now, write what
 * changed (with the ended event's outbox row when it ended), commit, then tell everyone. Two people leaving at once
 * both see the other's leave, so exactly one of them ends the call.
 */
class CallChanges(
    private val calls: CallRepository,
    private val transactor: Transactor,
    private val outbox: CallsOutbox,
    private val bus: EventBus,
    private val notices: CallNotices,
) {
    /** The call as it was and as [edit] left it. [edit] is pure: it decides, nothing else. */
    data class Change(val before: Call, val after: Call)

    suspend fun change(callId: CallId, edit: (Call) -> Resource<Call>): Resource<Change> {
        val result =
            transactor.attempt("calls.change") {
                val call = calls.lock(callId) ?: return@attempt Resource.error(CallsError.NotFound)
                val after =
                    when (val edited = edit(call)) {
                        is Resource.Error -> return@attempt edited
                        is Resource.Success -> edited.data
                    }
                if (after == call) return@attempt Resource.success(Written(call, call, null))
                calls.save(call, after)
                val event = if (after.isEnded && !call.isEnded) bus.envelope(CallRules.endedEvent(after)) else null
                event?.let { outbox.write(listOf(it)) }
                Resource.success(Written(call, after, event))
            }
        val written =
            when (result) {
                is Resource.Error -> return result
                is Resource.Success -> result.data
            }
        if (written.after != written.before) notices.changed(written.before, written.after, written.event)
        return Resource.success(Change(written.before, written.after))
    }

    private data class Written(val before: Call, val after: Call, val event: EventEnvelope?)
}
