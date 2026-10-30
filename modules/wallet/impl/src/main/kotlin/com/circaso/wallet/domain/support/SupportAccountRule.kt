package com.circaso.wallet.domain.support

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountKind
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError

/**
 * Support accounts never hold a wallet (decision 2026-10-31): none is set up for one, it adds no chain, it can't pay
 * or be paid, and nobody is asked to remind it. Identity owns the account's kind; the wallet only asks.
 */
class SupportAccountRule(private val accounts: AccountDirectory) {
    /** `wallet.support_account_has_no_wallet` when [userId] is a support account; null for a person. */
    suspend fun refusal(userId: UserId): Resource<Nothing>? =
        if (accounts.kind(userId) ==
            AccountKind.SUPPORT
        ) {
            Resource.error(WalletError.SupportAccountHasNoWallet)
        } else {
            null
        }
}
