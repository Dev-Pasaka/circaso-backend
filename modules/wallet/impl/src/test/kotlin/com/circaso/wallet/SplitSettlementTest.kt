package com.circaso.wallet

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyOperation
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.shared.decode
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.FeeState
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.ports.WalletFrame
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A split payment's batch (phase 2F) settles its transfer although Circle keeps no `refId` on it (2026-11-05: a real
 * BASE-SEPOLIA payment executed on chain and stayed "Sending"). The shapes are Circle's own records of that payment:
 * the sender's `OUTBOUND` `CONTRACT_EXECUTION` (no token, no amount, no `refId`), the payee's `INBOUND` `TRANSFER`, and
 * Circaso's fee wallet's `INBOUND` `TRANSFER` (developer-controlled, no user).
 */
class SplitSettlementTest {
    private val rules = MemoryRules()
    private val ledger = MemoryFeeLedger()
    private val h = Harness(ruleRows = rules, ledger = ledger)
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val usdc = 1_000_000L
    private val txHash = "0x624040c3b1d5576a05379e7bcd4429a94965f076d853e29438771fe7f51a020e"

    private suspend fun ready() {
        rules.seedFees()
        h.fund(amina, 400 * usdc)
        h.walletOn(bob)
        h.reach.connect(amina, bob)
    }

    private suspend fun pay(payee: Payee, amount: Long): Transfer {
        val quote = h.quote(amina, payee, amount).data()
        return h
            .prepareTransfer(
                TransferCommand(
                    amina,
                    "BASE-SEPOLIA",
                    payee,
                    Money(amount, Asset.USDC),
                    null,
                    null,
                    UUID.randomUUID(),
                    quoteId = quote.quoteId,
                ),
            ).data()
            .transfer
    }

    /** The sender's record as Circle has it: no token, no amount, no refId. */
    private suspend fun execution(
        state: CustodyTransactionState,
        hash: String = txHash,
        circleTxId: String = "8822478d-1b93-54c5-87b6-619290cd7d80",
    ): CustodyTransaction {
        val wallet = h.walletOn(amina)
        return CustodyTransaction(
            circleTxId = circleTxId,
            circleUserId = h.circleUserOf(amina),
            walletId = wallet.circleWalletId,
            blockchain = wallet.blockchain,
            direction = CustodyDirection.OUT,
            state = state,
            amount = Money(0, Asset.USDC),
            sourceAddress = wallet.address,
            destinationAddress = "",
            refId = "",
            txHash = hash,
            failureReason = "",
            updatedAt = h.clock.instant(),
            networkFee = "0.0000018510681",
            operation = CustodyOperation.CONTRACT_EXECUTION,
        )
    }

    /** A leg as the receiving wallet's inbound record of the same chain transaction. */
    private suspend fun leg(
        to: String,
        amountMinor: Long,
        walletId: String,
        developer: Boolean = false,
    ) = CustodyTransaction(
        circleTxId = "leg-${UUID.randomUUID()}",
        circleUserId = "",
        walletId = walletId,
        blockchain = "BASE-SEPOLIA",
        direction = CustodyDirection.IN,
        state = CustodyTransactionState.CONFIRMED,
        amount = Money(amountMinor, Asset.USDC),
        sourceAddress = h.walletOn(amina).address,
        destinationAddress = to,
        refId = "",
        txHash = txHash,
        failureReason = "",
        updatedAt = h.clock.instant(),
        developerWallet = developer,
    )

    private suspend fun legsOnChain(transfer: Transfer, payeeOnCircle: Boolean = true) {
        if (payeeOnCircle) {
            h.custody.recordLeg(
                txHash,
                leg(transfer.destinationAddress, transfer.amount.amountMinor, h.walletOn(bob).circleWalletId),
            )
        }
        h.custody.recordLeg(txHash, leg(FakeFeeConfig.FEE_WALLET, transfer.feeMinor, "d4eac6e0-fee", developer = true))
    }

    private fun stored(transfer: Transfer) =
        (h.transfers as MemoryTransferRepository).rows.getValue(transfer.transferId)

    private fun rows() = (h.transactions as MemoryTransactionRepository).rows

    @Test
    fun `the batch's execution confirms its transfer by its legs - event, OUT row, fee collected, sender told`() =
        runBlocking<Unit> {
            ready()
            val transfer = pay(Payee.Person(bob), 7_699_911)
            legsOnChain(transfer)

            h.applyUpdate(execution(CustodyTransactionState.CONFIRMED)).data()

            val now = stored(transfer)
            assertEquals(TransferState.CONFIRMED, now.state)
            assertEquals(txHash, now.txHash)
            assertEquals("8822478d-1b93-54c5-87b6-619290cd7d80", now.circleTxId)
            assertEquals(TransferState.CONFIRMED, rows().single { it.direction == Direction.OUT }.state)
            val event =
                h.bus.published
                    .single { it.type == TransferConfirmed.TYPE }
                    .decode<TransferConfirmed>()
            assertEquals(transfer.transferId, event.transferId)
            assertEquals(bob, event.recipientId)
            assertEquals(FeeState.COLLECTED, ledger.rows[transfer.transferId]?.state)
            assertEquals("0.0000018510681", ledger.gas[transfer.transferId]?.first, "the gas actually paid")
            assertEquals(listOf("CONFIRMED"), h.frames.to<WalletFrame.TransferUpdated>(amina).map { it.state })
        }

    @Test
    fun `Circle's repeats and its COMPLETE find the transfer by the stored Circle id and change nothing`() =
        runBlocking<Unit> {
            ready()
            val transfer = pay(Payee.Person(bob), 7_699_911)
            legsOnChain(transfer)
            h.applyUpdate(execution(CustodyTransactionState.CONFIRMED)).data()
            h.custody.chainLegs.clear()

            repeat(3) { h.applyUpdate(execution(CustodyTransactionState.CONFIRMED)).data() }

            assertEquals(1, h.bus.published.count { it.type == TransferConfirmed.TYPE })
            assertEquals(1, h.frames.to<WalletFrame.TransferUpdated>(amina).size)
        }

    @Test
    fun `before the chain has it the execution is left alone - no transfer moved, no history row of zero`() =
        runBlocking<Unit> {
            ready()
            val transfer = pay(Payee.Person(bob), 7_699_911)
            val outRows = rows().size

            h.applyUpdate(execution(CustodyTransactionState.SUBMITTED, hash = "")).data()

            assertEquals(TransferState.AWAITING_SIGNATURE, stored(transfer).state)
            assertNull(stored(transfer).circleTxId)
            assertEquals(outRows, rows().size)
        }

    @Test
    fun `a contract call that pays none of our transfers is ignored`() =
        runBlocking<Unit> {
            ready()
            val transfer = pay(Payee.Person(bob), 7_699_911)
            h.custody.recordLeg(txHash, leg(h.walletOn(bob).address, 5 * usdc, h.walletOn(bob).circleWalletId))
            val before = rows().size

            h.applyUpdate(execution(CustodyTransactionState.CONFIRMED)).data()

            assertEquals(TransferState.AWAITING_SIGNATURE, stored(transfer).state)
            assertEquals(before, rows().size)
            assertTrue(h.warnings.unknownWallets.isEmpty())
        }

    @Test
    fun `a payee outside Circle has no record - the fee leg alone ties the batch to its transfer`() =
        runBlocking<Unit> {
            ready()
            val outside = "0x" + "1234567890".repeat(4)
            val transfer = pay(Payee.Address(outside), 20 * usdc)
            legsOnChain(transfer, payeeOnCircle = false)

            h.applyUpdate(execution(CustodyTransactionState.CONFIRMED)).data()

            assertEquals(TransferState.CONFIRMED, stored(transfer).state)
        }

    @Test
    fun `the fee wallet's own inbound notification is acknowledged and ignored`() =
        runBlocking<Unit> {
            ready()
            val transfer = pay(Payee.Person(bob), 7_699_911)

            h.applyUpdate(leg(FakeFeeConfig.FEE_WALLET, transfer.feeMinor, "d4eac6e0-fee", developer = true)).data()

            assertTrue(h.warnings.unknownWallets.isEmpty())
            assertEquals(TransferState.AWAITING_SIGNATURE, stored(transfer).state)
        }

    @Test
    fun `wallet unsettled-splits repairs a payment whose webhook never settled it, even after it expired`() =
        runBlocking<Unit> {
            ready()
            val transfer = pay(Payee.Person(bob), 7_699_911)
            legsOnChain(transfer)
            // What happened on 2026-11-05: the execution's notifications were refused, the signing window closed.
            h.clock.advance(Duration.ofMinutes(16))
            h.expireUnsigned()
            assertEquals(TransferState.EXPIRED, stored(transfer).state)
            h.custody.record(execution(CustodyTransactionState.CONFIRMED))
            h.clock.advance(Duration.ofHours(3))

            assertEquals(1, h.settleSplits())

            assertEquals(TransferState.CONFIRMED, stored(transfer).state)
            assertEquals(1, h.bus.published.count { it.type == TransferConfirmed.TYPE })
            assertEquals(FeeState.COLLECTED, ledger.rows[transfer.transferId]?.state)
            assertEquals(0, h.settleSplits(), "nothing left to settle")
        }

    @Test
    fun `the job leaves a payment alone in its first two minutes and when Circle has nothing for it`() =
        runBlocking<Unit> {
            ready()
            val transfer = pay(Payee.Person(bob), 7_699_911)

            assertEquals(0, h.settleSplits())
            assertEquals(0, h.custody.count("transactions"), "too young to look for")

            h.clock.advance(Duration.ofMinutes(3))
            assertEquals(0, h.settleSplits())
            assertEquals(TransferState.AWAITING_SIGNATURE, stored(transfer).state)
        }

    @Test
    fun `two identical payments both match - the newest is taken and the ambiguity reported`() =
        runBlocking<Unit> {
            ready()
            val first = pay(Payee.Person(bob), 7_699_911)
            h.clock.advance(Duration.ofSeconds(30))
            val second = pay(Payee.Person(bob), 7_699_911)
            legsOnChain(second)

            h.applyUpdate(execution(CustodyTransactionState.CONFIRMED)).data()

            assertEquals(TransferState.CONFIRMED, stored(second).state)
            assertEquals(TransferState.AWAITING_SIGNATURE, stored(first).state)
            assertEquals(listOf("8822478d-1b93-54c5-87b6-619290cd7d80" to 2), h.warnings.ambiguousSplits)
        }
}
