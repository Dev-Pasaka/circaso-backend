package com.circaso.ramp.domain.usecases

import com.circaso.integrations.api.MerchantFloat
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.DayActivity
import com.circaso.ramp.domain.models.DayTotals
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantActivity
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantSummary
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.RampOverview
import com.circaso.ramp.domain.models.StaffFloat
import com.circaso.ramp.domain.models.StaffOrder
import com.circaso.ramp.domain.models.StaffOrderFilter
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.InsightRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.PayoutHoldRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.ramp.domain.repositories.SettlementRepository
import com.circaso.ramp.domain.support.MerchantInsights
import com.circaso.ramp.domain.support.Periods
import com.circaso.ramp.domain.support.ProviderWallets
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Instant

/**
 * Staff's overview of a network: what every merchant did in a period (Nairobi days; this month when none is given).
 * Listed are the approved merchants, and any other with an order in the period. Each row's money is the merchant's own
 * earnings figures ([MerchantInsights]), and the overview's totals are the sum of the rows.
 *
 * Staff's "online" is the trading window alone: the ops process holds nobody's keys, so it can't tell a merchant whose
 * node restarted from one who is unlocked.
 */
class GetRampOverview(
    private val merchants: MerchantRepository,
    private val orders: OrderRepository,
    private val insights: MerchantInsights,
    private val clock: Clock,
    private val settings: SettingsBook,
) {
    suspend operator fun invoke(network: Network, period: Period?): Resource<RampOverview> {
        val now = clock.instant()
        val covered = period ?: Periods.thisMonth(now)
        val current = settings.of(network)
        val rows =
            merchants
                .page(before = null, limit = MAX_MERCHANTS)
                .map { merchant ->
                    MerchantActivity(
                        merchant = merchant,
                        online = merchant.isOnlineAt(now),
                        figures = insights.figures(merchant.merchantId, network, covered),
                        lastOrderAt = lastOrderAt(merchant, network),
                        providerWallets =
                            ProviderWallets.of(merchant, merchants.balances(merchant.merchantId), current),
                    )
                }.filter { it.merchant.state == MerchantState.APPROVED || it.figures.orders > 0 }
        return Resource.success(RampOverview(covered, network, rows))
    }

    private suspend fun lastOrderAt(merchant: Merchant, network: Network): Instant? =
        orders.ofMerchant(merchant.merchantId, network, limit = 1, after = null).firstOrNull()?.createdAt

    private companion object {
        /** Partner merchants are vetted one by one; far more than there will be for a long time. */
        const val MAX_MERCHANTS = 500
    }
}

/**
 * Staff's view of one merchant on a network in a period: the account, the float (what open orders hold and what is
 * held for Circaso shown apart), the orders by how they ended, the same money figures the merchant's own earnings
 * overview shows, each Nairobi day with activity, and the daily sweeps.
 */
class GetMerchantSummary(
    private val merchants: MerchantRepository,
    private val wallets: MerchantWalletRepository,
    private val credentials: CredentialRepository,
    private val reservations: ReservationRepository,
    private val commissions: CommissionRepository,
    private val settlements: SettlementRepository,
    private val rows: InsightRepository,
    private val insights: MerchantInsights,
    private val float: MerchantFloat,
    private val clock: Clock,
    private val payoutHolds: PayoutHoldRepository,
    private val settings: SettingsBook,
) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        period: Period?,
    ): Resource<MerchantSummary> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        val now = clock.instant()
        val covered = period ?: Periods.thisMonth(now)
        val wallet = wallets.find(merchantId, network)
        val balances = merchants.balances(merchantId)
        return Resource.success(
            MerchantSummary(
                merchant = merchant,
                online = merchant.isOnlineAt(now),
                paymentsProvider = credentials.find(merchantId)?.provider,
                period = covered,
                network = network,
                wallet = wallet,
                float = wallet?.let { floatOf(it) },
                figures = insights.figures(merchantId, network, covered),
                daily = daily(merchantId, network, covered),
                sweeps = settlements.ofMerchant(merchantId, network, covered, MAX_SWEEPS),
                kesBalances = balances,
                kesHeldMinor = payoutHolds.openTotal(merchantId),
                providerWallets = ProviderWallets.of(merchant, balances, settings.of(network)),
            ),
        )
    }

    /** Null when the custodian can't be asked: never a zero nobody measured. */
    private suspend fun floatOf(wallet: MerchantWallet): StaffFloat? {
        val balance =
            when (val held = float.balance(wallet.network, wallet.custodyWalletId)) {
                is Resource.Error -> {
                    return null
                }

                is Resource.Success -> {
                    held.data.amountMinor
                        .min(Long.MAX_VALUE.toBigInteger())
                        .toLong()
                }
            }
        return StaffFloat(
            balanceMinor = balance,
            reservedMinor = reservations.openTotal(wallet.merchantId, wallet.network),
            owedMinor = commissions.owedTotal(wallet.merchantId, wallet.network),
        )
    }

    /** Every day something completed or was made and didn't complete, oldest first. */
    private suspend fun daily(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): List<DayActivity> {
        val completed = rows.completedByDay(merchantId, network, period).associateBy { it.day }
        val unfinished = rows.unfinishedByDay(merchantId, network, period)
        return (completed.keys + unfinished.keys).sorted().map { day ->
            DayActivity(completed[day] ?: DayTotals(day, 0, 0, 0, 0, 0, 0), unfinished[day] ?: 0)
        }
    }

    private companion object {
        /** As RM8: a period is at most a year of daily sweeps, with room for the odd failed one. */
        const val MAX_SWEEPS = 500
    }
}

/** A merchant's orders for staff, newest first, a page at a time by keyset: every state, or the one asked for. */
class ListStaffMerchantOrders(private val merchants: MerchantRepository, private val rows: InsightRepository) {
    suspend operator fun invoke(
        filter: StaffOrderFilter,
        after: PageCursor?,
        limit: Int,
    ): Resource<List<StaffOrder>> {
        merchants.find(filter.merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return Resource.success(rows.orders(filter, after, limit.coerceIn(1, MAX_LIMIT)))
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}
