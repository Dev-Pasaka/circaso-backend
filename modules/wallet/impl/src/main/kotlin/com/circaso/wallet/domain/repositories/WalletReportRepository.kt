package com.circaso.wallet.domain.repositories

import com.circaso.shared.UserId
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.domain.models.ChainAssetSums
import com.circaso.wallet.domain.models.GroupWalletKey
import com.circaso.wallet.domain.models.PeriodSums
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferKey
import com.circaso.wallet.domain.models.TransferQuery
import com.circaso.wallet.domain.models.WalletOwner
import java.time.Instant
import java.time.LocalDate

/**
 * Staff reads over the wallet's tables (decision 2026-11-08). They run in the ops process on the app's own pools, so
 * each is a short keyset page or an aggregate bounded by a time range of at most 366 days, served by an index
 * (V9__staff_reports); nothing here writes or locks.
 */
interface WalletReportRepository {
    /** Transfers matching [query], newest first, strictly after [after] in `(created_at, transfer_id) DESC` order. */
    suspend fun transfers(
        query: TransferQuery,
        after: TransferKey?,
        limit: Int,
    ): List<Transfer>

    /** Micro-dollars of the fee legs [holder] paid that were collected, all time. */
    suspend fun feesPaidBy(holder: UserId): Long

    /** Confirmed transfers created in [from, until), by [bucket] period (its first UTC day); empty periods absent. */
    suspend fun confirmedByPeriod(
        from: Instant,
        until: Instant,
        bucket: ReportBucket,
    ): Map<LocalDate, PeriodSums>

    /** Collected fees (micro-dollars) of fee legs created in [from, until), by [bucket] period. */
    suspend fun feesByPeriod(
        from: Instant,
        until: Instant,
        bucket: ReportBucket,
    ): Map<LocalDate, Long>

    /** Confirmed transfers created in [from, until), by chain and coin. */
    suspend fun confirmedByChainAsset(from: Instant, until: Instant): List<ChainAssetSums>

    /** Collected fees created in [from, until), by (chain, coin). */
    suspend fun feesByChainAsset(from: Instant, until: Instant): Map<Pair<String, String>, Long>

    suspend fun groupWalletCount(): Long

    /** Group wallets newest first, strictly after [after] in `(created_at, wallet_id) DESC` order. */
    suspend fun groupWallets(after: GroupWalletKey?, limit: Int): List<WalletOwner.Group>
}
