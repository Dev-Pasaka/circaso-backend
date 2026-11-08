package com.circaso.wallet.domain.support

import com.circaso.shared.DomainException
import com.circaso.shared.SharedError
import com.circaso.wallet.api.OpsTransferKind
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.Transfer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** The small rules behind the staff money reads (decision 2026-11-08), kept apart so each is tested on its own. */
internal object StaffReports {
    /** The widest report range: a year and a day, so a leap year fits. */
    val MAX_RANGE: Duration = Duration.ofDays(366)

    /**
     * A holding's worth in micro-dollars: amount × price, exactly, rounded half up once at the end; null when the coin
     * has no price. 1 USD = 1_000_000, the unit `usd_total_minor` and `fee_usd_minor` already use.
     */
    fun usdMicros(holding: Holding): Long? =
        holding.usdPrice?.let { price ->
            BigDecimal(holding.amountMinor)
                .multiply(price)
                .scaleByPowerOfTen(MICROS_PER_DOLLAR_DIGITS - holding.decimals)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }

    /**
     * How a transfer moved, checked in this order: to an address outside Circaso (no recipient and no group on the
     * other side), out of a group wallet, into a group wallet, else person to person (space wallets count as people
     * here: staff see their ids on the transfer).
     */
    fun kindOf(transfer: Transfer): OpsTransferKind =
        when {
            transfer.recipientId == null && transfer.toGroupId == null -> OpsTransferKind.EXTERNAL
            transfer.fromGroupId != null -> OpsTransferKind.GROUP_OUT
            transfer.toGroupId != null -> OpsTransferKind.GROUP_IN
            else -> OpsTransferKind.PERSON
        }

    /** Refuses a reversed range or one wider than [MAX_RANGE]; thrown, since the contract returns the report itself. */
    fun checkRange(from: Instant, until: Instant) {
        if (!until.isAfter(from) || Duration.between(from, until) > MAX_RANGE) {
            throw DomainException(SharedError.Malformed("to", "must be after from, at most 366 days"))
        }
    }

    /** The first UTC day of the [bucket] period holding [instant]: as PostgreSQL's `date_trunc` (ISO weeks, Monday). */
    fun periodOf(instant: Instant, bucket: ReportBucket): LocalDate {
        val day = instant.atOffset(ZoneOffset.UTC).toLocalDate()
        return when (bucket) {
            ReportBucket.DAY -> day
            ReportBucket.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ReportBucket.MONTH -> day.withDayOfMonth(1)
        }
    }

    /** Every period that overlaps [from, until), first to last, so a chart has every period, empty ones too. */
    fun periods(
        from: Instant,
        until: Instant,
        bucket: ReportBucket,
    ): List<LocalDate> =
        generateSequence(periodOf(from, bucket)) { next(it, bucket) }
            .takeWhile { it.atStartOfDay().toInstant(ZoneOffset.UTC).isBefore(until) }
            .toList()

    private fun next(period: LocalDate, bucket: ReportBucket): LocalDate =
        when (bucket) {
            ReportBucket.DAY -> period.plusDays(1)
            ReportBucket.WEEK -> period.plusWeeks(1)
            ReportBucket.MONTH -> period.plusMonths(1)
        }

    private const val MICROS_PER_DOLLAR_DIGITS = 6
}
