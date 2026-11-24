package com.circaso.ramp.domain.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.ports.OnlineSessionCopies
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import java.time.Clock

/**
 * Taking a merchant offline, the same way from every door (RM6, sign-out, a suspension, a provider's refusal), timed
 * session or open-ended: the session is ended with its [OfflineReason], so the engine gives them no new order and the
 * portal can say why, and the mark in Redis goes, and so does the session's encrypted working copy (2026-11-24),
 * before anyone is answered: nothing restores a session its merchant ended. **Open orders still finish**: while one of theirs waits for its
 * payment this node keeps their keys, because only the provider's own answer may settle it; `ramp.end-sessions`
 * drops the keys once none is left.
 */
class Presence(
    private val merchants: MerchantRepository,
    private val orders: OrderRepository,
    private val sessions: TradingSessions,
    private val clock: Clock,
    private val copies: OnlineSessionCopies,
) {
    suspend fun goOffline(merchantId: MerchantId, reason: OfflineReason) {
        merchants.goOffline(merchantId, reason, clock.instant())
        copies.delete(merchantId)
        sessions.end(merchantId, keepKeys = orders.needsKeys(merchantId))
    }
}
