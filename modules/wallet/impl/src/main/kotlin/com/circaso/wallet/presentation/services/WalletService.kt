package com.circaso.wallet.presentation.services

import com.circaso.integrations.api.SdkSession
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.AddChainCommand
import com.circaso.wallet.domain.models.ChainAddition
import com.circaso.wallet.domain.models.ChainOption
import com.circaso.wallet.domain.models.CreateWalletCommand
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.models.WalletSetup
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.domain.usecases.AddChain
import com.circaso.wallet.domain.usecases.CreateWallet
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.ListChains
import com.circaso.wallet.domain.usecases.OpenSession

/** The wallet itself: W1 to W3 and the chains it is on (W12). */
class WalletService(
    private val getWallet: GetWallet,
    private val createWallet: CreateWallet,
    private val openSession: OpenSession,
    private val listChains: ListChains,
    private val addChain: AddChain,
) {
    suspend fun wallet(query: WalletQuery): Resource<WalletView> = getWallet(query)

    suspend fun create(command: CreateWalletCommand): Resource<WalletSetup> = createWallet(command)

    suspend fun session(userId: UserId): Resource<SdkSession> = openSession(userId)

    suspend fun chains(userId: UserId): Resource<List<ChainOption>> = listChains(userId)

    suspend fun addChain(command: AddChainCommand): Resource<ChainAddition> = addChain.invoke(command)
}
