package com.circaso.ramp.domain.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.OrderRepository

/**
 * Taking a merchant offline, the same way from every door (RM6, sign-out, a suspension, the end of the window): the
 * trading window is cleared, so the engine gives them no new order, and the mark in Redis goes. **Open orders still
 * finish**: while one of theirs waits for its payment this node keeps their keys, because only the provider's own
 * answer may settle it; `ramp.end-sessions` drops the keys once none is left.
 */
class Presence(
    private val merchants: MerchantRepository,
    private val orders: OrderRepository,
    private val sessions: TradingSessions,
) {
    suspend fun goOffline(merchantId: MerchantId) {
        merchants.setOnlineUntil(merchantId, null)
        sessions.end(merchantId, keepKeys = orders.hasAwaitingPayment(merchantId))
    }
}
