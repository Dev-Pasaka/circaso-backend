package com.circaso.ramp.domain.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.MerchantFigures
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.UnfinishedOrders
import com.circaso.ramp.domain.repositories.InsightRepository
import com.circaso.ramp.domain.repositories.StatementRepository
import com.circaso.shared.Network

/**
 * A merchant's figures for a period, for staff. The money is `StatementRepository.totals`, the very call the
 * merchant's own earnings overview (RM8) and statement (RM15) make, so what staff see and what the merchant sees are
 * one computation and can't drift apart; the orders that didn't complete are counted beside it.
 */
class MerchantInsights(private val statements: StatementRepository, private val insights: InsightRepository) {
    suspend fun figures(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): MerchantFigures =
        MerchantFigures(
            totals = statements.totals(merchantId, network, period),
            unfinished = UnfinishedOrders.of(insights.unfinished(merchantId, network, period)),
        )
}
