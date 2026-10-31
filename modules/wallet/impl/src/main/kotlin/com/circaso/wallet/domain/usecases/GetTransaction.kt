package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.ExchangeRates
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.HISTORY_STATES
import com.circaso.wallet.domain.models.TransactionDetail
import com.circaso.wallet.domain.models.TransactionQuery
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.support.Counterparties

/**
 * One of the caller's own payments by its history row id (Asha's agent route, decision 2026-10-31). Someone else's
 * row, or one the history doesn't show (an abandoned PIN sheet), answers `wallet.transfer_not_found`, as W5 does.
 */
class GetTransaction(
    private val transactions: TransactionRepository,
    private val counterparties: Counterparties,
    private val rates: ExchangeRates,
) {
    suspend operator fun invoke(query: TransactionQuery): Resource<TransactionDetail> {
        val row =
            transactions.find(query.userId, query.transactionId)?.takeIf { it.state in HISTORY_STATES }
                ?: return Resource.error(WalletError.TransferNotFound)
        val entry = counterparties.entries(listOf(row)).single()
        return Resource.success(TransactionDetail(entry, query.currency?.let { rates.usdRate(it) }))
    }
}
