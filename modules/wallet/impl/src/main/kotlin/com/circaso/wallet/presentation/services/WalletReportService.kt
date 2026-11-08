package com.circaso.wallet.presentation.services

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.TransferFilter
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.FinanceTotals
import com.circaso.wallet.domain.models.GroupWalletResults
import com.circaso.wallet.domain.models.StaffHoldings
import com.circaso.wallet.domain.models.StaffTransfer
import com.circaso.wallet.domain.models.TransferResults
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.usecases.BuildFinanceReport
import com.circaso.wallet.domain.usecases.CountGroupWallets
import com.circaso.wallet.domain.usecases.FindGroupWallets
import com.circaso.wallet.domain.usecases.GetStaffHoldings
import com.circaso.wallet.domain.usecases.GetStaffTransfer
import com.circaso.wallet.domain.usecases.ListGroupWallets
import com.circaso.wallet.domain.usecases.ListStaffTransfers
import com.circaso.wallet.domain.usecases.ListTransfersOf
import com.circaso.wallet.domain.usecases.SumFeesPaid
import java.time.Instant
import java.util.UUID

/** Staff on the ops gateway (decision 2026-11-08): read-only money, per person and platform-wide. */
class WalletReportService(
    private val getStaffHoldings: GetStaffHoldings,
    private val listTransfersOf: ListTransfersOf,
    private val sumFeesPaid: SumFeesPaid,
    private val listStaffTransfers: ListStaffTransfers,
    private val getStaffTransfer: GetStaffTransfer,
    private val buildFinanceReport: BuildFinanceReport,
    private val countGroupWallets: CountGroupWallets,
    private val listGroupWallets: ListGroupWallets,
    private val findGroupWallets: FindGroupWallets,
) {
    suspend fun holdings(holderId: UUID): Resource<StaffHoldings> = getStaffHoldings(holderId)

    suspend fun transfersOf(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<TransferResults> = listTransfersOf(userId, cursor, limit)

    suspend fun feesPaidBy(userId: UserId): Resource<Long> = sumFeesPaid(userId)

    suspend fun transfers(
        filter: TransferFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TransferResults> = listStaffTransfers(filter, cursor, limit)

    suspend fun transfer(transferId: TransferId): Resource<StaffTransfer?> = getStaffTransfer(transferId)

    suspend fun report(
        from: Instant,
        to: Instant,
        bucket: ReportBucket,
    ): Resource<FinanceTotals> = buildFinanceReport(from, to, bucket)

    suspend fun groupWalletCount(): Resource<Long> = countGroupWallets()

    suspend fun groupWallets(cursor: String?, limit: Int): Resource<GroupWalletResults> =
        listGroupWallets(cursor, limit)

    suspend fun groupWalletsOf(chatIds: Set<ChatId>): Resource<Map<ChatId, WalletOwner.Group>> =
        findGroupWallets(chatIds)
}
