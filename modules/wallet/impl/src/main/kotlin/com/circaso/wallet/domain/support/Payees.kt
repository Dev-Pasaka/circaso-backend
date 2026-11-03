package com.circaso.wallet.domain.support

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.repositories.WalletRepository

/**
 * Where a transfer goes: the wallet paid ([recipientId]: a person, or a group wallet's holder with [toGroupId] set;
 * null for an outside address) and the address on chain.
 */
data class Destination(val recipientId: UserId?, val address: String, val toGroupId: ChatId? = null) {
    override fun toString(): String = "Destination($recipientId, ***)"
}

/**
 * Who can be paid, on which chain (Wallet → Paying a person; decision 2026-10-28: a transfer names its chain). A
 * person must be reachable, or share the chat the payment is made from, and hold a wallet on that chain; an outside
 * address must be valid on that chain. A chain retired from receiving (decision 2026-10-28) pays nobody inside
 * Circaso, while its wallets can still send out. A support account is never paid (decision 2026-10-31).
 */
class Payees(
    private val wallets: WalletRepository,
    private val owners: WalletOwnerRepository,
    private val reach: ReachPolicy,
    private val chats: ChatDirectory,
    private val custody: StablecoinCustody,
    private val support: SupportAccountRule,
) {
    /**
     * The reach rule, or both people in the chat the payment is made from. A shared chat never overrides a block
     * (decision 2026-10-26).
     */
    suspend fun canReach(
        sender: UserId,
        recipient: UserId,
        chatId: ChatId?,
    ): Boolean {
        if (reach.canReach(sender, recipient)) return true
        if (chatId == null || reach.blockedEitherWay(sender, recipient)) return false
        return chats.isMember(chatId, sender) && chats.isMember(chatId, recipient)
    }

    /** [userId]'s active wallets, and those of them on a chain that takes payments now. */
    suspend fun receiving(userId: UserId): Receiving {
        val active = wallets.wallets(userId).filter { it.state == ChainWalletState.ACTIVE }
        return Receiving(active, active.filter { receives(it.blockchain) })
    }

    data class Receiving(val active: List<ChainWallet>, val open: List<ChainWallet>)

    /** Whether [blockchain] is supported and not retired from receiving. */
    fun receives(blockchain: String): Boolean = custody.supported.find(blockchain)?.receiveEnabled == true

    /**
     * W4's payee checked and turned into a destination on [blockchain], in the order the rules list them. [sender]
     * is the holder of the wallet it leaves; [actor] the person paying (the signer), whose reach and groups count.
     */
    suspend fun resolve(
        sender: UserId,
        payee: Payee,
        blockchain: String,
        chatId: ChatId?,
        actor: UserId = sender,
    ): Resource<Destination> =
        when (payee) {
            is Payee.Person -> person(sender, payee.userId, blockchain, chatId, actor)
            is Payee.Address -> address(sender, payee.address, blockchain)
            is Payee.Group -> group(sender, payee.chatId, blockchain, actor)
        }

    /**
     * A group's wallet (decision 2026-11-03): only its members pay into it, and only once it is set up on the chain.
     * A group without a wallet answers `wallet.recipient_no_wallet`, as a person without one does.
     */
    private suspend fun group(
        sender: UserId,
        chatId: ChatId,
        blockchain: String,
        actor: UserId,
    ): Resource<Destination> {
        if (!chats.isMember(chatId, actor)) return Resource.error(WalletError.GroupNotFound)
        val owner = owners.byChat(chatId) ?: return Resource.error(WalletError.RecipientNoWallet)
        if (owner.holder == sender) return Resource.error(WalletError.SelfTransfer)
        val receiving = receiving(owner.holder)
        if (receiving.active.isEmpty()) return Resource.error(WalletError.RecipientNoWallet)
        val wallet =
            receiving.open.firstOrNull { it.blockchain == blockchain }
                ?: return Resource.error(WalletError.RecipientNotOnChain)
        return Resource.success(Destination(owner.holder, wallet.address, toGroupId = chatId))
    }

    private suspend fun person(
        sender: UserId,
        recipient: UserId,
        blockchain: String,
        chatId: ChatId?,
        actor: UserId,
    ): Resource<Destination> {
        if (recipient == sender) return Resource.error(WalletError.SelfTransfer)
        support.refusal(recipient)?.let { return it }
        // A group wallet's payee was approved by the group's admins; the signer still has to be able to reach them.
        if (!canReach(actor, recipient, chatId)) return Resource.error(WalletError.NotReachable)
        val receiving = receiving(recipient)
        if (receiving.active.isEmpty()) return Resource.error(WalletError.RecipientNoWallet)
        val wallet =
            receiving.open.firstOrNull { it.blockchain == blockchain }
                ?: return Resource.error(WalletError.RecipientNotOnChain)
        return Resource.success(Destination(recipient, wallet.address))
    }

    /**
     * An address outside Circaso, as far as the payer knows. One of our own wallets is still recognised: the
     * sender's own is a self transfer, and one on a retired chain can't be paid.
     */
    private suspend fun address(
        sender: UserId,
        address: String,
        blockchain: String,
    ): Resource<Destination> {
        val normalised = Addresses.normalise(address)
        val ours = wallets.walletAt(normalised, blockchain)
        if (ours?.userId == sender) return Resource.error(WalletError.SelfTransfer)
        if (ours != null && !receives(blockchain)) return Resource.error(WalletError.RecipientNotOnChain)

        val valid =
            when (val checked = custody.validateAddress(blockchain, address).orCustodyUnavailable()) {
                is Resource.Success -> checked.data
                is Resource.Error -> return checked
            }
        if (!valid) return Resource.error(WalletError.AddressInvalid)
        return Resource.success(Destination(recipientId = null, address = normalised))
    }
}
