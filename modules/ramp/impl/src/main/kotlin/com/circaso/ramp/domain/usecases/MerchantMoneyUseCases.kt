package com.circaso.ramp.domain.usecases

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Earnings
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.Statement
import com.circaso.ramp.domain.repositories.SettlementRepository
import com.circaso.ramp.domain.repositories.StatementRepository
import com.circaso.ramp.domain.support.Periods
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock

/**
 * RM8: what the merchant made in a period and what Circaso took (decision 2026-11-22): orders, shillings in, USDC
 * sold, the fees, both shares, how much of Circaso's has been swept and how much the float still holds, and the daily
 * sweeps. Every figure is added up from the order and commission rows, the same rows the statement lists.
 */
class GetEarnings(
    private val statements: StatementRepository,
    private val settlements: SettlementRepository,
    private val settings: SettingsBook,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        period: Period?,
    ): Resource<Earnings> {
        val covered = period ?: Periods.thisMonth(clock.instant())
        val current = settings.of(network)
        return Resource.success(
            Earnings(
                period = covered,
                network = network,
                totals = statements.totals(merchantId, network, covered),
                feeBps = current.feeBps,
                merchantShareBps = current.merchantShareBps,
                sweeps = settlements.ofMerchant(merchantId, network, covered, MAX_SWEEPS),
                buyingFeeBands = current.feeBandsOf(Direction.ON_RAMP),
                sellingFeeBands = current.feeBandsOf(Direction.OFF_RAMP),
            ),
        )
    }

    private companion object {
        /** A period is at most a year of daily sweeps, with room for the odd failed one. */
        const val MAX_SWEEPS = 500
    }
}

/** RM15: every exchange completed in a period, a page at a time, with the totals of the whole period. */
class GetStatement(private val statements: StatementRepository, private val clock: Clock) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        period: Period?,
        limit: Int,
        after: PageCursor?,
    ): Resource<Statement> {
        val covered = period ?: Periods.thisMonth(clock.instant())
        return Resource.success(
            Statement(
                period = covered,
                network = network,
                lines = statements.lines(merchantId, network, covered, after, limit.coerceIn(1, MAX_LIMIT)),
                totals = statements.totals(merchantId, network, covered),
            ),
        )
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}
