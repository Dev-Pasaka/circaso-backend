package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.RecipientCheck
import com.circaso.wallet.domain.models.RecipientQuery
import com.circaso.wallet.domain.models.RecipientStatus
import com.circaso.wallet.domain.support.Payees
import com.circaso.wallet.domain.support.SupportAccountRule

/**
 * W9 `GET /wallet/recipients/{userId}`: whether this person can be paid, and on which chains (decision 2026-10-28: a
 * transfer names its chain). `NO_WALLET` when they have none at all (the app offers W10), `NO_WALLET_ON_CHAIN` when
 * they can't receive on the chain asked for, or on any chain if none was asked. A person the caller can't reach
 * answers `users.not_reachable`, never saying why. Circaso support, which the app shows as such, can't be paid at all:
 * `wallet.support_account_has_no_wallet` (decision 2026-10-31).
 */
class CheckRecipient(
    private val payees: Payees,
    private val custody: StablecoinCustody,
    private val support: SupportAccountRule,
) {
    suspend operator fun invoke(query: RecipientQuery): Resource<RecipientCheck> {
        if (query.recipientId == query.senderId) return Resource.error(WalletError.SelfTransfer)
        support.refusal(query.recipientId)?.let { return it }
        if (query.blockchain != null && custody.supported.find(query.blockchain) == null) {
            return Resource.error(WalletError.ChainUnsupported)
        }
        if (!payees.canReach(query.senderId, query.recipientId, query.chatId)) {
            return Resource.error(WalletError.NotReachable)
        }

        val receiving = payees.receiving(query.recipientId)
        val chains = receiving.open.map { it.blockchain }
        val status =
            when {
                receiving.active.isEmpty() -> RecipientStatus.NO_WALLET
                query.blockchain == null && chains.isEmpty() -> RecipientStatus.NO_WALLET_ON_CHAIN
                query.blockchain != null && query.blockchain !in chains -> RecipientStatus.NO_WALLET_ON_CHAIN
                else -> RecipientStatus.CAN_RECEIVE
            }
        return Resource.success(RecipientCheck(query.recipientId, status, chains))
    }
}
