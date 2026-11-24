package com.circaso.wallet

import com.circaso.wallet.api.RampSaleDeposits
import com.circaso.wallet.api.SaleDeposit
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Ramp's answer about its sales: one recorded here waits for its USDC until its `fundBy`, as ramp's own does. */
class FakeRampSales(private val clock: Clock) : RampSaleDeposits {
    val sales = ConcurrentHashMap<UUID, SaleDeposit>()

    override suspend fun awaiting(orderId: UUID): SaleDeposit? =
        sales[orderId]?.takeIf { clock.instant().isBefore(it.fundBy) }
}
