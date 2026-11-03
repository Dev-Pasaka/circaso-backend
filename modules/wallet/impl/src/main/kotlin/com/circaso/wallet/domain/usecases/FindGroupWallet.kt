package com.circaso.wallet.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.wallet.api.GroupWalletSummary
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.support.WalletListings

/** `Wallets.groupWallet`, for chama: the group's wallet, its status and its signer; null when it has none. */
class FindGroupWallet(private val owners: WalletOwnerRepository, private val listings: WalletListings) {
    suspend operator fun invoke(chatId: ChatId): GroupWalletSummary? {
        val owner = owners.byChat(chatId) ?: return null
        val listing = listings.of(owner, caller = null)
        return GroupWalletSummary(owner.walletId, chatId, listing.status.name, owner.signerId)
    }
}
