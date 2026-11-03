package com.circaso.wallet.domain.support

import com.circaso.messaging.api.ChatDirectory
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.WalletAccess
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.repositories.WalletOwnerRepository

/**
 * Who may use which wallet (decision 2026-11-03, option A). A person's own wallet is theirs alone. A group wallet is
 * seen by the group's members (balance, history, payments) and signed for only by its signer, who must still be in
 * the group. A wallet the caller may not see answers `wallet.not_found`, as one that doesn't exist does.
 */
class Holders(private val owners: WalletOwnerRepository, private val chats: ChatDirectory) {
    /** [walletId] (null: the caller's own) as the caller may see it. */
    suspend fun view(caller: UserId, walletId: WalletId?): Resource<WalletAccess> {
        val owner =
            when (val found = ownerOf(caller, walletId)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        return Resource.success(WalletAccess(owner, canSign = owner.signerId == caller))
    }

    /** [walletId] (null: the caller's own) when the caller is the one who signs for it, and still can. */
    suspend fun sign(caller: UserId, walletId: WalletId?): Resource<WalletAccess> {
        val access =
            when (val seen = view(caller, walletId)) {
                is Resource.Success -> seen.data
                is Resource.Error -> return seen
            }
        if (access.canSign) return Resource.success(access)
        val group = access.owner as? WalletOwner.Group ?: return Resource.error(WalletError.NotSigner)
        // The signer left (or was removed from) the group: nobody can sign for its wallet until they are back.
        if (!chats.isMember(group.chatId, group.signerId)) return Resource.error(WalletError.SignerLeft)
        return Resource.error(WalletError.NotSigner)
    }

    /** Whose the wallet filed under [holder] is: a group wallet's owner row, or else the person themselves. */
    suspend fun owner(holder: UserId): WalletOwner = owners.find(WalletId(holder.value)) ?: WalletOwner.Person(holder)

    private suspend fun ownerOf(caller: UserId, walletId: WalletId?): Resource<WalletOwner> {
        if (walletId == null || walletId.value == caller.value) return Resource.success(WalletOwner.Person(caller))
        val group = owners.find(walletId) ?: return Resource.error(WalletError.NotFound)
        if (!chats.isMember(group.chatId, caller)) return Resource.error(WalletError.NotFound)
        return Resource.success(group)
    }
}
