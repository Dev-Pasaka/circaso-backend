package com.circaso.wallet.presentation.services

import com.circaso.integrations.api.SdkSession
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.models.AddChainCommand
import com.circaso.wallet.domain.models.ChainAddition
import com.circaso.wallet.domain.models.ChainOption
import com.circaso.wallet.domain.models.CreateGroupWalletCommand
import com.circaso.wallet.domain.models.CreateWalletCommand
import com.circaso.wallet.domain.models.GroupWalletSetup
import com.circaso.wallet.domain.models.GroupWalletView
import com.circaso.wallet.domain.models.WalletListing
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.models.WalletSetup
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.domain.usecases.AddChain
import com.circaso.wallet.domain.usecases.AddMissingChains
import com.circaso.wallet.domain.usecases.CreateGroupWallet
import com.circaso.wallet.domain.usecases.CreateWallet
import com.circaso.wallet.domain.usecases.GetGroupWallet
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.ListChains
import com.circaso.wallet.domain.usecases.ListMyWallets
import com.circaso.wallet.domain.usecases.OpenSession

/** The wallet itself: W1 to W3 and the chains it is on (W12). */
class WalletService(
    private val getWallet: GetWallet,
    private val createWallet: CreateWallet,
    private val openSession: OpenSession,
    private val listChains: ListChains,
    private val addChain: AddChain,
    private val addMissingChains: AddMissingChains,
    private val createGroupWallet: CreateGroupWallet,
    private val getGroupWallet: GetGroupWallet,
    private val listMyWallets: ListMyWallets,
) {
    suspend fun wallet(query: WalletQuery): Resource<WalletView> = getWallet(query)

    suspend fun create(command: CreateWalletCommand): Resource<WalletSetup> = createWallet(command)

    suspend fun session(userId: UserId, walletId: WalletId? = null): Resource<SdkSession> =
        openSession(userId, walletId)

    suspend fun chains(userId: UserId, walletId: WalletId? = null): Resource<List<ChainOption>> =
        listChains(userId, walletId)

    suspend fun addChain(command: AddChainCommand): Resource<ChainAddition> = addChain.invoke(command)

    suspend fun addMissingChains(userId: UserId, walletId: WalletId? = null): Resource<List<ChainAddition>> =
        addMissingChains.invoke(userId, walletId)

    suspend fun createGroupWallet(command: CreateGroupWalletCommand): Resource<GroupWalletSetup> =
        createGroupWallet.invoke(command)

    suspend fun groupWallet(userId: UserId, chatId: ChatId): Resource<GroupWalletView> = getGroupWallet(userId, chatId)

    suspend fun wallets(userId: UserId): Resource<List<WalletListing>> = listMyWallets(userId)
}
