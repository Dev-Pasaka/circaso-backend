package com.circaso.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientFieldsTest {
    @Test
    fun `our app names its own device`() {
        assertEquals("Android 15 · Xiaomi 23129RAA4G", deviceOf("Circaso/1.0.0 (Android 15; Xiaomi 23129RAA4G)"))
        assertEquals("iOS 26.0 · Apple iPhone", deviceOf("Circaso/1.2.0 (iOS 26.0; Apple iPhone)"))
    }

    @Test
    fun `browsers give the browser, the system and an Android model when there is one`() {
        assertEquals(
            "Chrome · Android 14 · Pixel 8",
            deviceOf(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A.240305.019) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
            ),
        )
        // Chrome's reduced User-Agent says "K" for the model: nothing worth logging.
        assertEquals(
            "Chrome · Android 10",
            deviceOf(
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
            ),
        )
        assertEquals(
            "Safari · iOS 17.4",
            deviceOf(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
            ),
        )
        assertEquals(
            "Firefox · Windows",
            deviceOf("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0"),
        )
        assertEquals(
            "Edge · macOS 10.15.7",
            deviceOf(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 Edg/124.0",
            ),
        )
    }

    @Test
    fun `tools are named, and nonsense is nothing`() {
        assertEquals("okhttp", deviceOf("okhttp/4.12.0"))
        assertEquals("curl", deviceOf("curl/8.4.0"))
        assertNull(deviceOf("something odd"))
    }

    @Test
    fun `a header can't forge a line or colour one`() {
        assertEquals("ab[31mc", cleanHeader("a\nb\u001b[31mc"))
        assertEquals(200, cleanHeader("x".repeat(500))!!.length)
        assertNull(cleanHeader("\n\t"))
    }
}
