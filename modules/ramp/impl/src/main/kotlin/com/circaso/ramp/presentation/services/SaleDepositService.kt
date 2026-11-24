package com.circaso.ramp.presentation.services

import com.circaso.ramp.domain.usecases.FindSaleDeposit
import com.circaso.wallet.api.SaleDeposit
import java.util.UUID

/** What the wallet asks about a sale's funding (decision 2026-11-24: Cash Out carries no wallet fee). */
class SaleDepositService(private val findSaleDeposit: FindSaleDeposit) {
    suspend fun awaiting(orderId: UUID): SaleDeposit? = findSaleDeposit(orderId)
}
