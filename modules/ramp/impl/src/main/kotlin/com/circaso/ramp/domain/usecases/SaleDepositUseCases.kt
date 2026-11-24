package com.circaso.ramp.domain.usecases

import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.ports.UsdcContracts
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.wallet.api.SaleDeposit
import java.time.Clock
import java.util.UUID

/**
 * The wallet's `RampSaleDeposits.awaiting` (decision 2026-11-24, Cash Out carries no wallet fee): what funds the sale
 * [orderId] while it still waits for its USDC, so the wallet can send exactly that free of its own fee. Null for
 * anything else: no such order, a purchase, any state but `AWAITING_TRANSFER`, a transfer already known, its deadline
 * passed (the expiry sweep may not have run yet), or no USDC contract configured on its chain.
 */
class FindSaleDeposit(private val orders: OrderRepository, private val usdc: UsdcContracts, private val clock: Clock) {
    suspend operator fun invoke(orderId: UUID): SaleDeposit? {
        val order = orders.find(RampOrderId(orderId)) ?: return null
        val waiting =
            order.direction == Direction.OFF_RAMP &&
                order.state == OrderState.AWAITING_TRANSFER &&
                order.walletTransferId == null &&
                clock.instant().isBefore(order.paymentDeadline)
        if (!waiting) return null
        val contract = usdc.contractOn(order.network, order.blockchain) ?: return null
        return SaleDeposit(
            orderId = order.orderId.value,
            payer = order.userId,
            blockchain = order.blockchain,
            address = order.destinationAddress,
            tokenAddress = contract,
            amountMinor = order.assetMinor,
            fundBy = order.paymentDeadline,
        )
    }
}
