package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.ExchangeRates
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.HISTORY_STATES
import com.circaso.wallet.domain.models.TransactionDetail
import com.circaso.wallet.domain.models.TransactionQuery
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.support.Counterparties
import com.circaso.wallet.domain.support.Holders

/**
 * One of the caller's own payments by its history row id (Asha's agent route, decision 2026-10-31). Someone else's
 * row, or one the history doesn't show (an abandoned PIN sheet), answers `wallet.transfer_not_found`, as W5 does.
 * With a group wallet's id (2026-11-10), a row of that wallet's history, for its members only.
 */
class GetTransaction(
    private val transactions: TransactionRepository,
    private val counterparties: Counterparties,
    private val rates: ExchangeRates,
    private val holders: Holders,
) {
    suspend operator fun invoke(query: TransactionQuery): Resource<TransactionDetail> {
        val holder =
            when (val access = holders.view(query.userId, query.walletId)) {
                is Resource.Success -> access.data.owner.holder
                is Resource.Error -> return access
            }
        val row =
            transactions.find(holder, query.transactionId)?.takeIf { it.state in HISTORY_STATES }
                ?: return Resource.error(WalletError.TransferNotFound)
        val entry = counterparties.entries(listOf(row)).single()
        return Resource.success(TransactionDetail(entry, query.currency?.let { rates.usdRate(it) }))
    }
}
