package com.circaso.wallet.domain.usecases

import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.GroupWalletView
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.support.SetupProgress
import com.circaso.wallet.domain.support.WalletListings

/**
 * W15 `GET /wallet/groups/{chatId}`: the group's wallet as its info shows it (GW6, GW6b, GW8): none yet, being set
 * up, or active with its signer and the chains it can be paid on. For the group's members; anyone else gets
 * `wallet.group_not_found`. Read from our rows; while the wallet is being set up, Circle's list is read first (at
 * most once per [SetupProgress] window), so the poll during set-up sees the wallet as soon as Circle has it.
 */
class GetGroupWallet(
    private val chats: ChatDirectory,
    private val owners: WalletOwnerRepository,
    private val listings: WalletListings,
    private val progress: SetupProgress,
) {
    suspend operator fun invoke(userId: UserId, chatId: ChatId): Resource<GroupWalletView> {
        if (!chats.isGroup(chatId) || !chats.isMember(chatId, userId)) {
            return Resource.error(WalletError.GroupNotFound)
        }
        val owner = owners.byChat(chatId) ?: return Resource.success(GroupWalletView(chatId, listing = null))
        progress.refresh(owner.holder)
        return Resource.success(GroupWalletView(chatId, listings.of(owner, userId)))
    }
}
