package com.circaso.wallet.presentation.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import com.circaso.shared.getOrThrow
import com.circaso.shared.map
import com.circaso.wallet.api.FinanceReport
import com.circaso.wallet.api.GroupWalletPage
import com.circaso.wallet.api.GroupWalletRef
import com.circaso.wallet.api.OpsHoldings
import com.circaso.wallet.api.OpsTransfer
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.TransferFilter
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferPage
import com.circaso.wallet.api.WalletReports
import com.circaso.wallet.presentation.mappers.toOps
import com.circaso.wallet.presentation.mappers.toRef
import com.circaso.wallet.presentation.services.WalletReportService
import java.time.Instant
import java.util.UUID

/**
 * wallet:api's staff contract (decision 2026-11-08), bound for the ops module only: the app gateway never mounts it.
 * Read-only; every call goes through [WalletReportService], like any other way in.
 */
class WalletReportsImpl(private val reports: WalletReportService) : WalletReports {
    override suspend fun holdings(holderId: UUID): Resource<OpsHoldings> = reports.holdings(holderId).map { it.toOps() }

    override suspend fun transfersOf(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<TransferPage> = reports.transfersOf(userId, cursor, limit).map { it.toOps() }

    override suspend fun feesPaidBy(userId: UserId): Long = reports.feesPaidBy(userId).getOrThrow()

    override suspend fun transfers(
        filter: TransferFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TransferPage> = reports.transfers(filter, cursor, limit).map { it.toOps() }

    override suspend fun transfer(transferId: TransferId): OpsTransfer? =
        reports.transfer(transferId).getOrNull()?.toOps()

    override suspend fun report(
        from: Instant,
        to: Instant,
        bucket: ReportBucket,
    ): FinanceReport = reports.report(from, to, bucket).getOrThrow().toOps()

    override suspend fun groupWalletCount(): Long = reports.groupWalletCount().getOrThrow()

    override suspend fun groupWallets(cursor: String?, limit: Int): Resource<GroupWalletPage> =
        reports.groupWallets(cursor, limit).map { it.toOps() }

    override suspend fun groupWalletsOf(chatIds: Set<ChatId>): Map<ChatId, GroupWalletRef> =
        reports
            .groupWalletsOf(chatIds)
            .getOrNull()
            .orEmpty()
            .mapValues { (_, owner) -> owner.toRef() }
}
