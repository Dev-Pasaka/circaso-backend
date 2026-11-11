package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.HoldReason
import com.circaso.wallet.domain.models.PaymentHold
import com.circaso.wallet.domain.ports.PaymentHolds
import java.time.Duration
import java.time.Instant

/**
 * On `identity.PinVaultOpened` (2026-11-11): pauses the person's outgoing payments for [HOLD] from the moment the vault
 * was opened ([openedAt], the event's time, not the handler's), so whoever opened it can't empty the wallet before
 * the owner reads the email. Safe to run twice or late: a hold only ever gets longer.
 */
class HoldAfterVaultOpened(private val holds: PaymentHolds) {
    suspend operator fun invoke(userId: UserId, openedAt: Instant): Resource<Unit> {
        holds.place(userId, PaymentHold(openedAt.plus(HOLD), HoldReason.VAULT_OPENED))
        return Resource.success(Unit)
    }

    companion object {
        val HOLD: Duration = Duration.ofHours(24)
    }
}
