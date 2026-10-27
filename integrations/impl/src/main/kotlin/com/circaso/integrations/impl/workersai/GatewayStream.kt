package com.circaso.integrations.impl.workersai

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel

/** A streamed Workers AI answer, line by line as Cloudflare sends it ([WorkersAiGateway.stream]). */
class GatewayStream internal constructor(private val lines: ReceiveChannel<String>, private val reader: Job) {
    /** Whether the answer was cut off (the connection dropped, or it ran past its deadline) rather than ending. */
    var isBroken = false
        private set

    /** The next line, waiting for it; null once the answer has ended or was cut off ([isBroken]). */
    suspend fun nextLine(): String? {
        val next = lines.receiveCatching()
        if (next.isClosed && next.exceptionOrNull() != null) isBroken = true
        return next.getOrNull()
    }

    /** Stops reading and lets go of the connection. Safe to call twice. */
    fun close() = reader.cancel()
}
