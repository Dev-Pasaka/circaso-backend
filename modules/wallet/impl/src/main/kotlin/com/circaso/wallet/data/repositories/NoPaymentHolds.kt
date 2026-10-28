package com.circaso.wallet.data.repositories

import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.PaymentHold
import com.circaso.wallet.domain.ports.PaymentHolds

/**
 * Nobody is on hold yet: the 48-hour hold after a support restore (decision 2026-10-28) is written on
 * `identity.AccountRelinked` into `payment_holds`, which WS4 builds and reads here instead.
 */
class NoPaymentHolds : PaymentHolds {
    // TODO(WS4): read `payment_holds` (filtered by expires_at > now) once the hold is built.
    override suspend fun active(userId: UserId): PaymentHold? = null
}
