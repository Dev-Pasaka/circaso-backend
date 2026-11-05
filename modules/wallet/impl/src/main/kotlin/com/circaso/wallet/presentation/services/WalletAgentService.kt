package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.AssetValueQuery
import com.circaso.wallet.domain.models.AssetValues
import com.circaso.wallet.domain.models.FeeEstimate
import com.circaso.wallet.domain.models.HistoryPage
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.models.SummaryQuery
import com.circaso.wallet.domain.models.TransactionDetail
import com.circaso.wallet.domain.models.TransactionQuery
import com.circaso.wallet.domain.models.TransactionSummary
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.WalletListing
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.domain.usecases.EstimateFee
import com.circaso.wallet.domain.usecases.GetTransaction
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.ListMyWallets
import com.circaso.wallet.domain.usecases.ListTransactions
import com.circaso.wallet.domain.usecases.SummariseTransactions
import com.circaso.wallet.domain.usecases.ValueAssets

/**
 * What Asha's read-only agent routes read (decisions 2026-10-31 and 2026-11-05): reads only, the caller's own wallets
 * only (their own and their groups'), and the wallet's own rate and fee rules.
 */
class WalletAgentService(
    private val getWallet: GetWallet,
    private val listTransactions: ListTransactions,
    private val summariseTransactions: SummariseTransactions,
    private val getTransaction: GetTransaction,
    private val listMyWallets: ListMyWallets,
    private val valueAssets: ValueAssets,
    private val estimateFee: EstimateFee,
) {
    suspend fun getWallet(query: WalletQuery): Resource<WalletView> = getWallet.invoke(query)

    suspend fun listTransactions(query: HistoryQuery): Resource<HistoryPage> = listTransactions.invoke(query)

    suspend fun summariseTransactions(query: SummaryQuery): Resource<TransactionSummary> =
        summariseTransactions.invoke(query)

    suspend fun getTransaction(query: TransactionQuery): Resource<TransactionDetail> = getTransaction.invoke(query)

    suspend fun listWallets(userId: UserId): Resource<List<WalletListing>> = listMyWallets.invoke(userId)

    suspend fun getAssetValues(query: AssetValueQuery): Resource<AssetValues> = valueAssets.invoke(query)

    suspend fun getFeeEstimate(command: TransferCommand, currency: String?): Resource<FeeEstimate> =
        estimateFee.invoke(command, currency)
}
