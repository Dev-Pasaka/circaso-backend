package com.circaso.ramp.presentation.api

import com.circaso.ramp.presentation.services.SaleDepositService
import com.circaso.wallet.api.RampSaleDeposits
import com.circaso.wallet.api.SaleDeposit
import java.util.UUID

/**
 * The wallet's [RampSaleDeposits] (decision 2026-11-24): a payment that funds the payer's own open sale, exactly,
 * carries no wallet fee. The wallet checks the payer, chain, address, coin and amount against what this answers.
 */
class RampSaleDepositsImpl(private val service: SaleDepositService) : RampSaleDeposits {
    override suspend fun awaiting(orderId: UUID): SaleDeposit? = service.awaiting(orderId)
}
