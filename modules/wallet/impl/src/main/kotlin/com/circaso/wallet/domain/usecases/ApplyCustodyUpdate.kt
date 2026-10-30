package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.CustodyTransaction
import com.circaso.shared.Resource
import com.circaso.wallet.domain.support.CustodyUpdates

/**
 * Integrations' `CustodyNotifications`: Circle's word on a transaction, from its webhook (Wallet → Events → From
 * Circle), applied through [CustodyUpdates], the path the reconciliation jobs share. An error makes the webhook answer
 * non-200, so Circle delivers the update again.
 */
class ApplyCustodyUpdate(private val updates: CustodyUpdates) {
    suspend operator fun invoke(update: CustodyTransaction): Resource<Unit> = updates.apply(update)
}
