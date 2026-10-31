package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.ToolStatus

/**
 * Where Asha's reply goes while the model writes it (decision 2026-10-27): A2's `message` events, A11's sentences.
 * The pieces, joined, are the reply's text as it is saved, so what the user saw (or heard) is what the chat keeps.
 */
fun interface ReplyListener {
    suspend fun text(delta: String)

    /** A tool is about to run (decision 2026-10-31): A2's `tool_status` event, A11's frame of the same name. */
    suspend fun toolStarted(status: ToolStatus) = Unit

    /** That tool has answered ([isOk] false when it failed): `tool_done`. */
    suspend fun toolFinished(status: ToolStatus, isOk: Boolean) = Unit

    companion object {
        /** For a caller that only wants the finished turn. */
        val None = ReplyListener { }
    }
}
