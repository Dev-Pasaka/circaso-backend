package com.circaso.ramp

import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.usecases.FindSaleDeposit
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What ramp tells the wallet about a sale's funding (decision 2026-11-24, Cash Out carries no wallet fee): only a sale
 * still waiting for its USDC, with what exactly funds it. The wallet waives its own fee on exactly that; the ramp's
 * sell fee and the payout provider's fee are the ramp's and don't change.
 */
class SaleDepositTest {
    private val ramp = Harness()
    private val find = FindSaleDeposit(ramp.orders, ramp.usdc, ramp.clock)

    private fun <T> Resource<T>.data() = (this as Resource.Success).data

    @Test
    fun `a sale waiting for its USDC answers its payer, chain, deposit address, contract, amount and deadline`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()

            val deposit = assertNotNull(find(order.orderId.value))

            assertEquals(ramp.amina, deposit.payer)
            assertEquals(CHAIN, deposit.blockchain)
            assertEquals(ramp.walletOf(ramp.crynce).address, deposit.address)
            assertEquals(USDC_CONTRACT, deposit.tokenAddress)
            assertEquals(order.assetMinor, deposit.amountMinor)
            assertEquals(order.paymentDeadline, deposit.fundBy)
        }

    @Test
    fun `nothing once its deadline passed, it was cancelled, or its transfer is known`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()
            ramp.clock.advance(900)
            assertNull(find(order.orderId.value), "past the deadline, before the expiry sweep ran")

            val second = Harness().also { it.openSelling() }
            val cancelled = second.sale()
            second.cancelOrder(second.amina, cancelled.orderId).data()
            assertNull(FindSaleDeposit(second.orders, second.usdc, second.clock)(cancelled.orderId.value))

            val third = Harness().also { it.openSelling() }
            val funded = third.sale()
            third.transfer(funded)
            assertNotNull(third.now(funded).walletTransferId)
            assertNull(FindSaleDeposit(third.orders, third.usdc, third.clock)(funded.orderId.value))
        }

    @Test
    fun `nothing for an unknown order or a chain with no USDC contract`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()
            assertNull(find(UUID.randomUUID()))

            ramp.usdc.contract = null
            assertNull(find(order.orderId.value))
        }

    @Test
    fun `the sale's own fee and the payout provider's fee are what its quote said, funded or not`() =
        runTest {
            ramp.openSelling()
            val order = ramp.sale()
            assertEquals(2_600L to 500L, order.feeMinor to order.providerFeeMinor)

            ramp.transfer(order)

            val funded = ramp.now(order)
            assertEquals(OrderState.PAYING_OUT, funded.state, "funded, and its payout sent at once")
            assertEquals(10_000_000L, funded.fundedMinor, "exactly the sale's USDC: no wallet fee taken out of it")
            assertEquals(2_600L to 500L, funded.feeMinor to funded.providerFeeMinor)
        }
}
