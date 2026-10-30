package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.Challenge
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.CreateWalletCommand
import com.circaso.wallet.domain.models.WalletSetup
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.SupportAccountRule
import com.circaso.wallet.domain.support.WalletSync
import com.circaso.wallet.domain.support.orCustodyUnavailable
import java.time.Clock
import java.util.UUID

/**
 * W2 `POST /wallet`: creates the Circle user if needed (decision 2026-10-25: on demand, from the wallet section) and
 * the challenge in which the user sets the PIN and Circle creates the wallet on the default chain (Base; decision
 * 2026-10-28). Asking again while it is `SETTING_UP` gives a fresh challenge for the same Circle user; once the wallet
 * is `ACTIVE` the answer is `wallet.already_exists`.
 *
 * A support account never gets a Circle user at all (decision 2026-10-31): `wallet.support_account_has_no_wallet`.
 *
 * The Circle user's id is random and claimed by the `circle_users` primary key, so two taps at once share one id.
 */
class CreateWallet(
    private val wallets: WalletRepository,
    private val transactor: Transactor,
    private val custody: StablecoinCustody,
    private val sync: WalletSync,
    private val support: SupportAccountRule,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: CreateWalletCommand): Resource<WalletSetup> {
        support.refusal(command.userId)?.let { return it }
        val user = wallets.circleUser(command.userId) ?: claim(command.userId)
        if (hasOpenWallet(command.userId)) return Resource.error(WalletError.AlreadyExists)

        val circleUserId = user.circleUserId.toString()
        val registered = custody.createUser(circleUserId).orCustodyUnavailable()
        if (registered is Resource.Error) return registered
        return when (val challenge = custody.createWallet(circleUserId, command.idempotencyKey)) {
            is Resource.Success -> answer(user, challenge.data)
            is Resource.Error -> challenge.orCustodyUnavailable()
        }
    }

    /**
     * Circle had nothing to sign: the user already set the PIN, so the wallet exists or is on its way. If Circle lists
     * it, it is recorded and the wallet is already set up; otherwise it is still being set up, with no challenge.
     */
    private suspend fun answer(user: CircleUser, challenge: Challenge): Resource<WalletSetup> {
        if (challenge.needsSignature) {
            return Resource.success(WalletSetup(WalletStatus.SETTING_UP, challenge.challengeId, challenge.sdk))
        }
        val synced = sync.sync(user)
        if (synced is Resource.Success && synced.data.any { it.state != ChainWalletState.CLOSED }) {
            return Resource.error(WalletError.AlreadyExists)
        }
        return Resource.success(WalletSetup(WalletStatus.SETTING_UP, challengeId = null, sdk = challenge.sdk))
    }

    private suspend fun claim(userId: UserId): CircleUser {
        val fresh = CircleUser(userId, UUID.randomUUID(), clock.instant())
        return transactor.run("wallet.claim-circle-user") {
            if (wallets.insertCircleUser(fresh)) fresh else checkNotNull(wallets.circleUser(userId))
        }
    }

    private suspend fun hasOpenWallet(userId: UserId): Boolean =
        wallets.wallets(userId).any { it.state != ChainWalletState.CLOSED }
}
