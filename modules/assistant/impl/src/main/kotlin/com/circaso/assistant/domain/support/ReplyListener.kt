package com.circaso.assistant.domain.support

/**
 * Where Asha's reply goes while the model writes it (decision 2026-10-27): A2's `message` events, A11's sentences.
 * The pieces, joined, are the reply's text as it is saved, so what the user saw (or heard) is what the chat keeps.
 */
fun interface ReplyListener {
    suspend fun text(delta: String)

    companion object {
        /** For a caller that only wants the finished turn. */
        val None = ReplyListener { }
    }
}
