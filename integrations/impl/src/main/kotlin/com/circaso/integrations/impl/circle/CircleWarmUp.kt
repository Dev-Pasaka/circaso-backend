package com.circaso.integrations.impl.circle

import com.circaso.infrastructure.api.WarmUp

/**
 * Once at start-up, Circle's `GET /ping` (no key, no user, nothing changed): the first wallet call after a deploy then
 * finds the connection open and the TLS, HTTP and JSON paths compiled, instead of paying for them.
 */
internal class CircleWarmUp(private val client: CircleClient) : WarmUp {
    override val name = "circle"
    override val once = true

    override suspend fun run() {
        client.ping()
    }
}
