package com.circaso.wallet

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.decode
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.TransferFailed
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.RequestedAmount
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.usecases.LookupTransfer
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * W4 with `rampOrderId` (the off-ramp, decision 2026-11-23): the wallet stores the ramp order a transfer funds, echoes
 * it on the transfer's events and summary, and lets one order be funded by one transfer at a time. Nothing else about
 * the id is checked: it is the ramp's.
 */
class RampOrderTransferTest {
    private val h = Harness()
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val order = UUID.randomUUID()

    private suspend fun fund(rampOrderId: UUID? = order, key: UUID = UUID.randomUUID()) =
        h.prepareTransfer(
            TransferCommand(
                senderId = amina,
                blockchain = TEST_CHAINS.default,
                payee = Payee.Person(bob),
                amount = RequestedAmount.of(Money(10_000_000, Asset.USDC)),
                chatId = null,
                proposalId = null,
                idempotencyKey = key,
                rampOrderId = rampOrderId,
            ),
        )

    private suspend fun settle(transfer: Transfer, state: CustodyTransactionState) =
        h.applyUpdate(
            h.update(
                h.walletOn(amina),
                h.circleUserOf(amina),
                CustodyDirection.OUT,
                state,
                10_000_000,
                refId = transfer.transferId.toString(),
                circleTxId = "tx-${transfer.transferId}",
                destination = transfer.destinationAddress,
            ),
        )

    private suspend fun setUp() {
        h.fund(amina, 100_000_000)
        h.walletOn(bob)
        h.reach.connect(amina, bob)
    }

    @Test
    fun `the ramp order is kept, and echoed on the confirmed event and the summary`() =
        runBlocking<Unit> {
            setUp()
            val transfer = (fund() as Resource.Success).data.transfer
            assertEquals(order, transfer.rampOrderId)

            settle(transfer, CustodyTransactionState.CONFIRMED)

            val event =
                h.bus.published
                    .single { it.type == TransferConfirmed.TYPE }
                    .decode<TransferConfirmed>()
            assertEquals(order.toString(), event.rampOrderId)
            assertEquals(order, LookupTransfer(h.transfers)(transfer.transferId)!!.rampOrderId)
        }

    @Test
    fun `a second transfer for the same order is refused while the first may still land, and allowed once it failed`() =
        runBlocking<Unit> {
            setUp()
            val first = (fund() as Resource.Success).data.transfer

            val again = fund()
            assertEquals("wallet.ramp_order_funded", (again as Resource.Error).error.code)
            assertIs<Resource.Success<*>>(fund(rampOrderId = UUID.randomUUID()), "another order is another matter")

            settle(first, CustodyTransactionState.FAILED)
            val failed =
                h.bus.published
                    .single { it.type == TransferFailed.TYPE }
                    .decode<TransferFailed>()
            assertEquals(order.toString(), failed.rampOrderId)
            assertIs<Resource.Success<*>>(fund(), "the failed one moved nothing")
        }

    @Test
    fun `repeating the request with the same key answers the same transfer, not a refusal`() =
        runBlocking<Unit> {
            setUp()
            val key = UUID.randomUUID()
            val first = (fund(key = key) as Resource.Success).data.transfer

            val repeated = (fund(key = key) as Resource.Success).data.transfer

            assertEquals(first.transferId, repeated.transferId)
        }
}
