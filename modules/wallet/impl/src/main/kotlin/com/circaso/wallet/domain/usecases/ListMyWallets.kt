package com.circaso.wallet.domain.usecases

import com.circaso.messaging.api.ChatDirectory
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.WalletListing
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.support.WalletListings

/**
 * W16 `GET /wallets`: every wallet the caller can see, for the "Paying from" switcher (GW10) and the Wallet tab's
 * title switcher (GW16): their own first (whatever its status, so the app knows whether to offer "Create wallet"),
 * then the wallets of the groups they are in, those they sign for first. Balances are W1's, per wallet.
 */
class ListMyWallets(
    private val chats: ChatDirectory,
    private val owners: WalletOwnerRepository,
    private val listings: WalletListings,
) {
    suspend operator fun invoke(userId: UserId): Resource<List<WalletListing>> {
        val own = listings.of(WalletOwner.Person(userId), userId)
        val groups =
            owners
                .byChats(chats.groupsOf(userId))
                .sortedWith(compareBy<WalletOwner.Group> { it.signerId != userId }.thenBy { it.createdAt })
                .map { listings.of(it, userId) }
        return Resource.success(listOf(own) + groups)
    }
}
