package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.CreateWalletCommand
import com.circaso.wallet.domain.models.WalletSetup
import com.circaso.wallet.domain.support.SupportAccountRule
import com.circaso.wallet.domain.support.WalletCreation

/**
 * W2 `POST /wallet`: creates the Circle user if needed (decision 2026-10-25: on demand, from the wallet section) and
 * the challenge in which the user sets the PIN and Circle creates the wallet on the default chain (Base; decision
 * 2026-10-28). Asking again while it is `SETTING_UP` gives a fresh challenge for the same Circle user; once the wallet
 * is `ACTIVE` the answer is `wallet.already_exists` ([WalletCreation]).
 *
 * A support account never gets a Circle user at all (decision 2026-10-31): `wallet.support_account_has_no_wallet`.
 */
class CreateWallet(private val creation: WalletCreation, private val support: SupportAccountRule) {
    suspend operator fun invoke(command: CreateWalletCommand): Resource<WalletSetup> {
        support.refusal(command.userId)?.let { return it }
        return creation.start(command.userId, command.idempotencyKey)
    }
}
