package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.domain.models.FinanceTotals
import com.circaso.wallet.domain.models.PeriodSums
import com.circaso.wallet.domain.models.ReportChainAsset
import com.circaso.wallet.domain.models.ReportPeriod
import com.circaso.wallet.domain.repositories.WalletReportRepository
import com.circaso.wallet.domain.support.StaffReports
import java.time.Instant

/**
 * Staff: platform money over [from, to) by day, week or month (decision 2026-11-08). Only `CONFIRMED` transfers count;
 * the volume is each one's dollar weight at the price it was made at (`usd_total_minor`, amount and fee); the fees are
 * the legs actually collected. Every period of the range is in the series, zeros too. Periods are UTC, ISO weeks.
 *
 * The range is at most 366 days, which is what bounds the aggregates; wider or reversed is `request.malformed`,
 * thrown because the contract returns the report itself (the ops gateway's StatusPages answers it as usual).
 */
class BuildFinanceReport(private val reports: WalletReportRepository) {
    suspend operator fun invoke(
        from: Instant,
        to: Instant,
        bucket: ReportBucket,
    ): Resource<FinanceTotals> {
        StaffReports.checkRange(from, to)
        val confirmed = reports.confirmedByPeriod(from, to, bucket)
        val fees = reports.feesByPeriod(from, to, bucket)
        val series =
            StaffReports.periods(from, to, bucket).map { period ->
                val sums = confirmed[period] ?: PeriodSums(0, 0)
                ReportPeriod(period, sums.count, sums.volumeUsdMicros, fees[period] ?: 0)
            }
        val chainFees = reports.feesByChainAsset(from, to)
        val byChainAsset =
            reports
                .confirmedByChainAsset(from, to)
                .map { ReportChainAsset(it, chainFees[it.blockchain to it.asset] ?: 0) }
                .sortedWith(
                    compareByDescending<ReportChainAsset> { it.sums.volumeUsdMicros }.thenBy { it.sums.blockchain },
                )
        return Resource.success(
            FinanceTotals(
                series = series,
                byChainAsset = byChainAsset,
                count = series.sumOf { it.count },
                volumeUsdMicros = series.sumOf { it.volumeUsdMicros },
                feesUsdMicros = series.sumOf { it.feesUsdMicros },
            ),
        )
    }
}
