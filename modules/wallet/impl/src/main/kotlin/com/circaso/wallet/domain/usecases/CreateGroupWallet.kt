package com.circaso.wallet.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.messaging.api.ChatDirectory
import com.circaso.shared.Resource
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.CreateGroupWalletCommand
import com.circaso.wallet.domain.models.GroupWalletSetup
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.support.SupportAccountRule
import com.circaso.wallet.domain.support.WalletCreation
import java.time.Clock

/**
 * W14 `POST /wallet/groups/{chatId}` (decision 2026-11-03, Spaces phase 2, option A): the group's owner sets up the
 * group's own wallet, separate from everyone's personal money. The wallet is its own Circle user, filed under a fresh
 * [WalletId], so it has its own PIN (and its own biometrics on the owner's phone); the owner becomes its signer, the
 * one person who can send from it, and only after the admins approve each payout.
 *
 * One wallet per group: the owner row is claimed by `wallet_owners_chat_key` before Circle is asked, so two taps at
 * once share one wallet. Asking again while it is `SETTING_UP` gives a fresh challenge (the set-up screen's "Finish
 * Wallet Setup", GW6b); once it is `ACTIVE`, `wallet.already_exists`. Only the signer can finish a set-up someone
 * started: if ownership of the group moved since, the new owner gets `wallet.not_signer`.
 */
class CreateGroupWallet(
    private val chats: ChatDirectory,
    private val owners: WalletOwnerRepository,
    private val accounts: AccountDirectory,
    private val support: SupportAccountRule,
    private val creation: WalletCreation,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: CreateGroupWalletCommand): Resource<GroupWalletSetup> {
        support.refusal(command.userId)?.let { return it }
        if (!chats.isGroup(command.chatId) || !chats.isMember(command.chatId, command.userId)) {
            return Resource.error(WalletError.GroupNotFound)
        }
        if (accounts.status(command.userId) != AccountStatus.ACTIVE) {
            return Resource.error(WalletError.AccountRestricted)
        }
        val owner =
            owners.byChat(command.chatId)
                ?: when (val claimed = claim(command)) {
                    is Resource.Success -> claimed.data
                    is Resource.Error -> return claimed
                }
        if (owner.signerId != command.userId) return Resource.error(WalletError.NotSigner)
        return when (val setup = creation.start(owner.holder, command.idempotencyKey)) {
            is Resource.Success -> Resource.success(GroupWalletSetup(owner, setup.data))
            is Resource.Error -> setup
        }
    }

    /** Only the group's owner starts its wallet; of two starts at once, one row lands and both read it. */
    private suspend fun claim(command: CreateGroupWalletCommand): Resource<WalletOwner.Group> {
        if (chats.owner(command.chatId) != command.userId) return Resource.error(WalletError.NotGroupOwner)
        val fresh =
            WalletOwner.Group(
                walletId = WalletId.new(),
                chatId = command.chatId,
                signerId = command.userId,
                createdBy = command.userId,
                createdAt = clock.instant(),
            )
        if (owners.insert(fresh)) return Resource.success(fresh)
        return owners.byChat(command.chatId)?.let { Resource.success(it) } ?: Resource.error(WalletError.GroupNotFound)
    }
}
