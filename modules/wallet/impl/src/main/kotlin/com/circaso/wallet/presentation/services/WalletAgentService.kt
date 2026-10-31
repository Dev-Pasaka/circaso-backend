package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.HistoryPage
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.models.SummaryQuery
import com.circaso.wallet.domain.models.TransactionDetail
import com.circaso.wallet.domain.models.TransactionQuery
import com.circaso.wallet.domain.models.TransactionSummary
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.domain.usecases.GetTransaction
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.ListTransactions
import com.circaso.wallet.domain.usecases.SummariseTransactions

/** What Asha's read-only agent routes read (decision 2026-10-31): reads only, the caller's own wallet only. */
class WalletAgentService(
    private val getWallet: GetWallet,
    private val listTransactions: ListTransactions,
    private val summariseTransactions: SummariseTransactions,
    private val getTransaction: GetTransaction,
) {
    suspend fun getWallet(query: WalletQuery): Resource<WalletView> = getWallet.invoke(query)

    suspend fun listTransactions(query: HistoryQuery): Resource<HistoryPage> = listTransactions.invoke(query)

    suspend fun summariseTransactions(query: SummaryQuery): Resource<TransactionSummary> =
        summariseTransactions.invoke(query)

    suspend fun getTransaction(query: TransactionQuery): Resource<TransactionDetail> = getTransaction.invoke(query)
}
