package com.circaso.wallet.domain.support

import com.circaso.integrations.api.Challenge
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.WalletSetup
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.ports.PendingSetups
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.WalletRepository
import java.time.Clock
import java.util.UUID

/**
 * Creating a wallet at Circle, the same for a person's own (W2) and a group's (W14): the Circle user filed under the
 * wallet's [holder] id is claimed if needed, then Circle is asked for the challenge in which the PIN is set and the
 * wallet made on the default chain. Asking again while it is `SETTING_UP` gives a fresh challenge for the same Circle
 * user; once the wallet is `ACTIVE` the answer is `wallet.already_exists`.
 *
 * The Circle user's id is random and claimed by the `circle_users` primary key, so two taps at once share one id.
 *
 * A challenge handed out is kept ([PendingSetups]): asking again while it is still open at Circle gives it back (with
 * a current SDK session) rather than a second `user/initialize`, after first reading Circle's wallet list, so a PIN
 * already set answers `wallet.already_exists` with the wallet recorded. A challenge Circle no longer has open (done,
 * failed, expired) or that can't be checked is replaced by a fresh one, as before.
 */
class WalletCreation(
    private val wallets: WalletRepository,
    private val transactor: Transactor,
    private val custody: StablecoinCustody,
    private val sync: WalletSync,
    private val clock: Clock,
    private val pending: PendingSetups,
) {
    suspend fun start(holder: UserId, idempotencyKey: UUID): Resource<WalletSetup> {
        val user = wallets.circleUser(holder) ?: claim(holder)
        if (hasOpenWallet(holder)) return Resource.error(WalletError.AlreadyExists)
        pending.challenge(holder)?.let { open -> reuse(user, open)?.let { return it } }

        val circleUserId = user.circleUserId.toString()
        val registered = custody.createUser(circleUserId).orCustodyUnavailable()
        if (registered is Resource.Error) return registered
        return when (val challenge = custody.createWallet(circleUserId, idempotencyKey)) {
            is Resource.Success -> answer(user, challenge.data)
            is Resource.Error -> challenge.orCustodyUnavailable()
        }
    }

    /**
     * Circle had nothing to sign: the PIN is already set, so the wallet exists or is on its way. If Circle lists it,
     * it is recorded and the wallet is already set up; otherwise it is still being set up, with no challenge.
     */
    private suspend fun answer(user: CircleUser, challenge: Challenge): Resource<WalletSetup> {
        if (challenge.needsSignature) {
            pending.put(user.userId, challenge.challengeId)
            return Resource.success(WalletSetup(WalletStatus.SETTING_UP, challenge.challengeId, challenge.sdk))
        }
        val synced = sync.sync(user)
        if (synced is Resource.Success && synced.data.any { it.state != ChainWalletState.CLOSED }) {
            return Resource.error(WalletError.AlreadyExists)
        }
        return Resource.success(WalletSetup(WalletStatus.SETTING_UP, challengeId = null, sdk = challenge.sdk))
    }

    /**
     * The setup's challenge handed out before: null to start a fresh one (Circle says it is no longer open, or can't
     * say). Circle's wallets are read first, so a setup that already went through is recorded, not asked for again.
     */
    private suspend fun reuse(user: CircleUser, challengeId: String): Resource<WalletSetup>? {
        val circleUserId = user.circleUserId.toString()
        val synced = sync.sync(user)
        if (synced is Resource.Success && synced.data.any { it.state != ChainWalletState.CLOSED }) {
            pending.clear(user.userId)
            return Resource.error(WalletError.AlreadyExists)
        }
        val open = custody.challengeOpen(circleUserId, challengeId)
        if (open !is Resource.Success || !open.data) {
            if (open is Resource.Success) pending.clear(user.userId)
            return null
        }
        return when (val session = custody.sdkSession(circleUserId)) {
            is Resource.Success -> Resource.success(WalletSetup(WalletStatus.SETTING_UP, challengeId, session.data))
            is Resource.Error -> null
        }
    }

    private suspend fun claim(holder: UserId): CircleUser {
        val fresh = CircleUser(holder, UUID.randomUUID(), clock.instant())
        return transactor.run("wallet.claim-circle-user") {
            if (wallets.insertCircleUser(fresh)) fresh else checkNotNull(wallets.circleUser(holder))
        }
    }

    private suspend fun hasOpenWallet(holder: UserId): Boolean =
        wallets.wallets(holder).any { it.state != ChainWalletState.CLOSED }
}
