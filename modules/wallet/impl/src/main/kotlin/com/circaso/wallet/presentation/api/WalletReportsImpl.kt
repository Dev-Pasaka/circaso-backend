package com.circaso.wallet.presentation.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.WalletNetwork
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
import com.circaso.wallet.presentation.mappers.combined
import com.circaso.wallet.presentation.mappers.toOps
import com.circaso.wallet.presentation.mappers.toRef
import com.circaso.wallet.presentation.services.WalletReportService
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.withContext

/**
 * wallet:api's staff contract (decision 2026-11-08), bound for the ops module only: the app gateway never mounts it.
 * Read-only; every call goes through [WalletReportService], like any other way in.
 *
 * Networks (decision 2026-11-18): a call that names one runs with it as the coroutine's network, which the staff
 * queries filter by; one that names none covers both. Reads that go through the wallet's own rows for a holder (a
 * person has one wallet per network) run once per network and are put side by side, never added up across networks
 * unless both were asked for.
 */
class WalletReportsImpl(private val reports: WalletReportService) : WalletReports {
    override suspend fun holdings(holderId: UUID, network: Network?): Resource<OpsHoldings> {
        val each = networksOf(network).map { on(it) { reports.holdings(holderId) } }
        each.firstOrNull { it is Resource.Error }?.let { return it as Resource.Error }
        return Resource.success(each.map { (it as Resource.Success).data.toOps() }.combined())
    }

    override suspend fun transfersOf(
        userId: UserId,
        cursor: String?,
        limit: Int,
        network: Network?,
    ): Resource<TransferPage> = filtered(network) { reports.transfersOf(userId, cursor, limit).map { it.toOps() } }

    override suspend fun feesPaidBy(userId: UserId, network: Network?): Long =
        filtered(network) { reports.feesPaidBy(userId).getOrThrow() }

    override suspend fun transfers(
        filter: TransferFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TransferPage> = filtered(filter.network) { reports.transfers(filter, cursor, limit).map { it.toOps() } }

    override suspend fun transfer(transferId: TransferId): OpsTransfer? =
        reports.transfer(transferId).getOrNull()?.toOps()

    override suspend fun report(
        from: Instant,
        to: Instant,
        bucket: ReportBucket,
        network: Network?,
    ): FinanceReport =
        networksOf(network)
            .map { each -> on(each) { reports.report(from, to, bucket).getOrThrow().toOps(each) } }
            .combined()

    override suspend fun groupWalletCount(network: Network?): Long =
        filtered(network) { reports.groupWalletCount().getOrThrow() }

    override suspend fun groupWallets(
        cursor: String?,
        limit: Int,
        network: Network?,
    ): Resource<GroupWalletPage> = filtered(network) { reports.groupWallets(cursor, limit).map { it.toOps() } }

    override suspend fun groupWalletsOf(chatIds: Set<ChatId>, network: Network?): Map<ChatId, GroupWalletRef> =
        groupWalletsIn(chatIds, network).mapValues { it.value.first() }

    override suspend fun groupWalletsIn(chatIds: Set<ChatId>, network: Network?): Map<ChatId, List<GroupWalletRef>> =
        networksOf(network)
            .flatMap { each ->
                on(each) { reports.groupWalletsOf(chatIds) }
                    .getOrNull()
                    .orEmpty()
                    .values
                    .map { it.toRef(each) }
            }.groupBy { it.chatId }

    /** The network asked for, or both, mainnet first. */
    private fun networksOf(network: Network?): List<Network> = network?.let(::listOf) ?: Network.entries

    private suspend fun <T> on(network: Network, block: suspend () -> T): T =
        withContext(WalletNetwork(network)) { block() }

    /** Runs a staff query for [network] only, or for both when none is named (no network in the context). */
    private suspend fun <T> filtered(network: Network?, block: suspend () -> T): T =
        if (network == null) block() else on(network, block)
}
