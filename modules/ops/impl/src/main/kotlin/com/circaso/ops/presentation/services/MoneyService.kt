package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Finance
import com.circaso.ops.domain.models.TransferView
import com.circaso.ops.domain.models.TransferViewPage
import com.circaso.ops.domain.models.UserTransfers
import com.circaso.ops.domain.usecases.GetFinanceReport
import com.circaso.ops.domain.usecases.GetTransaction
import com.circaso.ops.domain.usecases.GetUserWallets
import com.circaso.ops.domain.usecases.ListTransactions
import com.circaso.ops.domain.usecases.ListUserTransfers
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.OpsHoldings
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.TransferFilter
import com.circaso.wallet.api.TransferId
import java.time.Instant

/** Money, read-only: a user's wallets and transfers, every transaction, and the finance report. */
class MoneyService(
    private val getUserWallets: GetUserWallets,
    private val listUserTransfers: ListUserTransfers,
    private val listTransactions: ListTransactions,
    private val getTransaction: GetTransaction,
    private val getFinanceReport: GetFinanceReport,
) {
    suspend fun wallets(userId: UserId): Resource<OpsHoldings> = getUserWallets(userId)

    suspend fun userTransfers(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<UserTransfers> = listUserTransfers(userId, cursor, limit)

    suspend fun transactions(
        filter: TransferFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TransferViewPage> = listTransactions(filter, cursor, limit)

    suspend fun transfer(transferId: TransferId): Resource<TransferView> = getTransaction(transferId)

    suspend fun finance(
        from: Instant,
        to: Instant,
        bucket: ReportBucket,
    ): Resource<Finance> = getFinanceReport(from, to, bucket)
}
