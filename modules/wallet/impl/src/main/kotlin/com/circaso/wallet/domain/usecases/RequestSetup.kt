package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ReminderOutcome
import com.circaso.wallet.domain.ports.FrameSender
import com.circaso.wallet.domain.ports.Reminders
import com.circaso.wallet.domain.ports.WalletFrame
import com.circaso.wallet.domain.support.Payees
import com.circaso.wallet.domain.support.SupportAccountRule

/**
 * W10 `POST /wallet/recipients/{userId}/remind`: tells a contact without a wallet that someone wants to pay them, as
 * the `setup.requested` frame with a push ("Amina wants to send you money"). Once per pair per 24 hours
 * (`wal:remind`); nothing is held for them in the meantime.
 */
class RequestSetup(
    private val payees: Payees,
    private val reminders: Reminders,
    private val profiles: Profiles,
    private val frames: FrameSender,
    private val support: SupportAccountRule,
) {
    suspend operator fun invoke(from: UserId, to: UserId): Resource<ReminderOutcome> {
        if (from == to) return Resource.error(WalletError.SelfTransfer)
        support.refusal(to)?.let { return it }
        if (!payees.canReach(from, to, chatId = null)) return Resource.error(WalletError.NotReachable)
        if (payees.receiving(to).active.isNotEmpty()) return Resource.success(ReminderOutcome.HAS_WALLET)
        if (!reminders.claim(from, to)) return Resource.success(ReminderOutcome.ALREADY_SENT)

        val name = profiles.get(setOf(from))[from]?.displayName ?: SOMEONE
        frames.send(to, WalletFrame.SetupRequested(from, name))
        return Resource.success(ReminderOutcome.SENT)
    }

    private companion object {
        const val SOMEONE = "Someone"
    }
}
