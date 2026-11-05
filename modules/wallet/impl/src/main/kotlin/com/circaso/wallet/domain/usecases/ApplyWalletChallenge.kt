package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.CustodyChallenge
import com.circaso.shared.Resource
import com.circaso.wallet.domain.support.SetupProgress

/**
 * Integrations' `CustodyNotifications.onWalletChallenge`: Circle finished a challenge that creates wallets (the PIN and
 * the first wallets, or a chain added), so that user's wallets are read from Circle now ([SetupProgress.onChallenge]):
 * the wallet turns `ACTIVE`, `WalletCreated` goes out (a space goes live) and `wallet.ready` reaches the app within
 * seconds of the PIN, instead of at the next poll or sweep. A failed challenge is no longer handed out again.
 */
class ApplyWalletChallenge(private val progress: SetupProgress) {
    suspend operator fun invoke(challenge: CustodyChallenge): Resource<Unit> =
        progress.onChallenge(challenge.providerUserId, challenge.completed)
}
