package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.SdkSession
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.ports.ActiveWallets
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.Holders
import com.circaso.wallet.domain.support.orCustodyUnavailable

/**
 * W3 `POST /wallet/session`: the values the phone's Circle SDK runs under (app id, user token, encryption key). They
 * go to the app only, never to the agent token. Open while the wallet is being set up too: that is when the SDK first
 * needs them.
 */
class OpenSession(
    private val wallets: WalletRepository,
    private val custody: StablecoinCustody,
    private val active: ActiveWallets,
    private val holders: Holders,
) {
    /**
     * [walletId] null opens the caller's own wallet. A group wallet's session (decision 2026-11-03: each group
     * wallet is its own Circle user, with its own PIN and biometrics) goes only to the person who signs for it.
     */
    suspend operator fun invoke(caller: UserId, walletId: WalletId? = null): Resource<SdkSession> {
        val holder =
            when (val access = holders.sign(caller, walletId)) {
                is Resource.Success -> access.data.owner.holder
                is Resource.Error -> return access
            }
        val user = wallets.circleUser(holder) ?: return Resource.error(WalletError.NotFound)
        // About to sign something: the light reconciliation pass watches this wallet for a while.
        active.touch(holder)
        return custody.sdkSession(user.circleUserId.toString()).orCustodyUnavailable()
    }
}
