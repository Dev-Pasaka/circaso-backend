package com.circaso.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitTableTest {
    @Test
    fun `W6 history reads have their own bucket of 60 per user per minute`() {
        val limit = RateLimitTable.limitFor("GET", "/v1/wallet/transactions", signedIn = true)!!

        assertEquals("wallet-history", limit.name)
        assertEquals(60, limit.max)
        assertEquals(LimitBy.USER, limit.by)
        assertEquals("user", RateLimitTable.limitFor("GET", "/v1/wallet/transfers/x", signedIn = true)!!.name)
    }
}
