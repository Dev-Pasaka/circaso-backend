package com.circaso.integrations.impl.circle

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Q7 (audit 2026-11-05): the warm-up's one call to Circle opens the connection without a key, a user or a change. */
class CircleWarmUpTest {
    private val circle = FakeCircle()

    @Test
    fun `the warm-up runs once and is Circle's ping, sent without the API key`() =
        runBlocking<Unit> {
            circle.reply("GET /ping", """{"message":"pong"}""")
            val warmUp = CircleWarmUp(circle.client)

            warmUp.run()

            assertTrue(warmUp.once)
            val ping = circle.requests("GET /ping").single()
            assertNull(ping.headers[HttpHeaders.Authorization], "a ping needs no key, so none is sent")
            assertEquals(1, circle.clients.requests.size, "nothing else: no user token, no wallet call")
        }

    @Test
    fun `Circle unreachable leaves the warm-up quiet, so start-up carries on`() =
        runBlocking<Unit> {
            circle.down = true

            CircleWarmUp(circle.client).run()
            assertEquals(false, circle.client.ping())
        }
}
