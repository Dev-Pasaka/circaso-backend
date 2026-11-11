package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.usecases.HoldAfterVaultOpened
import java.time.Instant

/** What the wallet does about identity's events. */
class IdentityEventsService(private val holdAfterVaultOpened: HoldAfterVaultOpened) {
    suspend fun vaultOpened(userId: UserId, openedAt: Instant): Resource<Unit> = holdAfterVaultOpened(userId, openedAt)
}
