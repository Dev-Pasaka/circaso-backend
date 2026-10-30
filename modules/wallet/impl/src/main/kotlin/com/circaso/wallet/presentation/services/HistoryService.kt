package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.HistoryPage
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.usecases.ListTransactions

/** The payment history: W6 (W7's statements join it later). */
class HistoryService(private val listTransactions: ListTransactions) {
    suspend fun transactions(query: HistoryQuery): Resource<HistoryPage> = listTransactions(query)
}
