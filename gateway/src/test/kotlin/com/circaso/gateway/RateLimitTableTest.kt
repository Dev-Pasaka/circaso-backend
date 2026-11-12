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

    @Test
    fun `IC1 coin icons have their own bucket of 600 per IP per minute, signed in or not`() {
        listOf(true, false).forEach { signedIn ->
            val limit = RateLimitTable.limitFor("GET", "/v1/assets/icons/USDC", signedIn)!!

            assertEquals("icons", limit.name)
            assertEquals(600, limit.max)
            assertEquals(LimitBy.IP, limit.by)
        }
    }

    @Test
    fun `SH1 share links have their own bucket of 30 per user per hour`() {
        val limit = RateLimitTable.limitFor("POST", "/v1/share/links", signedIn = true)!!

        assertEquals("share", limit.name)
        assertEquals(30, limit.max)
        assertEquals(LimitBy.USER, limit.by)
        assertEquals("user", RateLimitTable.limitFor("GET", "/v1/share/links", signedIn = true)!!.name)
    }

    @Test
    fun `SU1 support tickets have their own bucket of 10 per user per hour`() {
        val limit = RateLimitTable.limitFor("POST", "/v1/support/tickets", signedIn = true)!!

        assertEquals("support", limit.name)
        assertEquals(10, limit.max)
        assertEquals(LimitBy.USER, limit.by)
        assertEquals("user", RateLimitTable.limitFor("GET", "/v1/support/tickets", signedIn = true)!!.name)
        assertEquals("user", RateLimitTable.limitFor("POST", "/v1/support/tickets/x/replies", signedIn = true)!!.name)
    }

    @Test
    fun `I17 the website's deletion request has its own bucket of 20 per address per hour`() {
        val limit = RateLimitTable.limitFor("POST", "/v1/web/account-deletion", signedIn = false)!!

        assertEquals("web-deletion", limit.name)
        assertEquals(20, limit.max)
        assertEquals(LimitBy.IP, limit.by)
    }
}
