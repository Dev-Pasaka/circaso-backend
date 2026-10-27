package com.circaso.gateway

import com.circaso.gateway.realtime.AuthFrame
import com.circaso.gateway.realtime.ModuleSeq
import com.circaso.gateway.realtime.PingFrame
import com.circaso.gateway.realtime.SessionRevokedFrame
import com.circaso.shared.CircasoJson
import kotlin.test.Test
import kotlin.test.assertEquals

/** The gateway's own socket frames, as the phone writes and reads them (decision 2026-10-27). */
class SocketFramesTest {
    @Test
    fun `the auth frame carries last as a list of module and seq, with seq a number`() {
        val raw =
            """{"type":"auth","accessToken":"t",""" +
                """"last":[{"module":"users","seq":1042},{"module":"messaging","seq":17}]}"""

        val frame = CircasoJson.decodeFromString(AuthFrame.serializer(), raw)

        assertEquals(AuthFrame("auth", "t", listOf(ModuleSeq("users", 1042), ModuleSeq("messaging", 17))), frame)
        assertEquals(raw, CircasoJson.encodeToString(AuthFrame.serializer(), frame))
    }

    @Test
    fun `last may be left out on first connect`() {
        val frame = CircasoJson.decodeFromString(AuthFrame.serializer(), """{"type":"auth","accessToken":"t"}""")

        assertEquals(emptyList(), frame.last)
    }

    @Test
    fun `session revoked is an empty object and the heartbeat is a ping`() {
        assertEquals("{}", CircasoJson.encodeToString(SessionRevokedFrame.serializer(), SessionRevokedFrame))
        assertEquals("""{"type":"ping"}""", CircasoJson.encodeToString(PingFrame.serializer(), PingFrame()))
    }
}
