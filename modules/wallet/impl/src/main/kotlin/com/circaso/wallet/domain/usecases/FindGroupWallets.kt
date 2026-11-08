package com.circaso.wallet.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.repositories.WalletOwnerRepository

/** Staff: the wallets of those of [chatIds] that have one (decision 2026-11-08), through `wallet_owners_chat_key`. */
class FindGroupWallets(private val owners: WalletOwnerRepository) {
    suspend operator fun invoke(chatIds: Set<ChatId>): Resource<Map<ChatId, WalletOwner.Group>> {
        if (chatIds.isEmpty()) return Resource.success(emptyMap())
        return Resource.success(owners.byChats(chatIds.take(MAX_IDS)).associateBy { it.chatId })
    }

    private companion object {
        /** A page of results at most; the ops module never asks for more at once. */
        const val MAX_IDS = 500
    }
}
