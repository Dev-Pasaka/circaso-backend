package com.circaso.wallet

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.shared.UserId
import com.circaso.shared.decode
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.TransferFailed
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.ports.WalletFrame
import com.circaso.wallet.domain.support.toChainWallet
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Circle's webhook through `CustodyNotifications`: the transition guard orders updates by meaning, repeats change
 * nothing, and only the update that moves a transfer publishes its event and tells the user.
 */
class CustodyUpdateTest {
    private val h = Harness()
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val tenUsdc = 10_000_000L

    private suspend fun prepared(): Transfer {
        h.fund(amina, 100_000_000)
        h.walletOn(bob)
        h.reach.connect(amina, bob)
        return h.pay(amina, Payee.Person(bob), tenUsdc).data().transfer
    }

    private suspend fun outbound(transfer: Transfer, state: CustodyTransactionState) =
        h.applyUpdate(
            h.update(
                h.walletOn(amina),
                h.circleUserOf(amina),
                CustodyDirection.OUT,
                state,
                tenUsdc,
                refId = transfer.transferId.toString(),
                circleTxId = "tx-out-${transfer.transferId}",
                destination = transfer.destinationAddress,
            ),
        )

    private suspend fun inbound(
        state: CustodyTransactionState,
        circleTxId: String = "tx-in-1",
        source: String? = null,
    ) = h.applyUpdate(
        h.update(
            h.walletOn(bob),
            h.circleUserOf(bob),
            CustodyDirection.IN,
            state,
            tenUsdc,
            circleTxId = circleTxId,
            source = source ?: h.walletOn(amina).address,
        ),
    )

    private fun transfer(transfer: Transfer) =
        (h.transfers as MemoryTransferRepository).rows.getValue(transfer.transferId)

    private fun rows() = (h.transactions as MemoryTransactionRepository).rows

    // ---- outgoing ----

    @Test
    fun `an outbound update finds its transfer by refId, moves it and its OUT row, and tells the sender`() =
        runBlocking<Unit> {
            val prepared = prepared()

            outbound(prepared, CustodyTransactionState.SUBMITTED).data()

            assertEquals(TransferState.SUBMITTED, transfer(prepared).state)
            assertEquals("tx-out-${prepared.transferId}", transfer(prepared).circleTxId)
            val out = rows().single { it.direction == Direction.OUT }
            assertEquals(TransferState.SUBMITTED, out.state)
            assertEquals("tx-out-${prepared.transferId}", out.circleTxId)
            val frame = h.frames.to<WalletFrame.TransferUpdated>(amina).single()
            assertEquals("SUBMITTED", frame.state)
            assertEquals(prepared.transferId, frame.transferId)
            assertTrue(h.bus.published.none { it.type.startsWith("wallet.Transfer") }, "no event until it's final")
        }

    @Test
    fun `CONFIRMED publishes TransferConfirmed once, with its outbox row, however often Circle repeats it`() =
        runBlocking<Unit> {
            val prepared = prepared()
            outbound(prepared, CustodyTransactionState.SUBMITTED).data()

            repeat(3) { outbound(prepared, CustodyTransactionState.CONFIRMED).data() }

            assertEquals(TransferState.CONFIRMED, transfer(prepared).state)
            assertEquals("0xhash", transfer(prepared).txHash)
            val events = h.bus.published.filter { it.type == TransferConfirmed.TYPE }
            assertEquals(1, events.size)
            assertEquals(events, (h.outbox as RecordingOutbox).written.filter { it.type == TransferConfirmed.TYPE })
            val event = events.single().decode<TransferConfirmed>()
            assertEquals(prepared.transferId, event.transferId)
            assertEquals(bob, event.recipientId)
            assertEquals("BASE-SEPOLIA", event.blockchain)
            assertEquals("10000000", event.amount.amountMinor)
            assertEquals("10", event.amount.amount)
            assertEquals("APP", event.origin)
            assertEquals(
                listOf("SUBMITTED", "CONFIRMED"),
                h.frames.to<WalletFrame.TransferUpdated>(amina).map { it.state },
            )
        }

    @Test
    fun `updates are ordered by meaning - a late SUBMITTED after CONFIRMED changes nothing`() =
        runBlocking<Unit> {
            val prepared = prepared()

            outbound(prepared, CustodyTransactionState.CONFIRMED).data()
            outbound(prepared, CustodyTransactionState.SUBMITTED).data()
            outbound(prepared, CustodyTransactionState.FAILED).data()

            assertEquals(TransferState.CONFIRMED, transfer(prepared).state)
            assertEquals(TransferState.CONFIRMED, rows().single().state)
            assertEquals(1, h.frames.to<WalletFrame.TransferUpdated>(amina).size)
            assertTrue(h.bus.published.none { it.type == TransferFailed.TYPE })
        }

    @Test
    fun `STUCK is shown as submitted`() =
        runBlocking<Unit> {
            val prepared = prepared()
            outbound(prepared, CustodyTransactionState.STUCK).data()
            assertEquals(TransferState.SUBMITTED, transfer(prepared).state)
        }

    @Test
    fun `FAILED publishes TransferFailed with Circle's reason and gives the amount back to the daily limit`() =
        runBlocking<Unit> {
            val prepared = prepared()
            assertEquals(tenUsdc, h.used(amina))

            outbound(prepared, CustodyTransactionState.FAILED).data()
            outbound(prepared, CustodyTransactionState.FAILED).data()

            assertEquals(TransferState.FAILED, transfer(prepared).state)
            assertEquals("INSUFFICIENT_TOKEN", transfer(prepared).failureReason)
            val failed =
                h.bus.published
                    .single { it.type == TransferFailed.TYPE }
                    .decode<TransferFailed>()
            assertEquals("INSUFFICIENT_TOKEN", failed.reason)
            assertEquals(0L, h.used(amina), "given back once")
            assertEquals(
                "FAILED",
                h.frames
                    .to<WalletFrame.TransferUpdated>(amina)
                    .single()
                    .state,
            )
        }

    @Test
    fun `a transfer marked EXPIRED still follows Circle when the chain says it was signed`() =
        runBlocking<Unit> {
            val prepared = prepared()
            h.transfers.move(
                prepared.transferId,
                TransferState.EXPIRED,
                TransferChange(at = h.clock.instant()),
            )

            outbound(prepared, CustodyTransactionState.CONFIRMED).data()

            assertEquals(TransferState.CONFIRMED, transfer(prepared).state)
        }

    @Test
    fun `FAILED after EXPIRED gives nothing back, since the expiry already did, and FAILED once more gives nothing`() =
        runBlocking<Unit> {
            val prepared = prepared()
            h.transfers.move(prepared.transferId, TransferState.EXPIRED, TransferChange(at = h.clock.instant()))

            outbound(prepared, CustodyTransactionState.FAILED).data()
            outbound(prepared, CustodyTransactionState.FAILED).data()

            assertEquals(TransferState.FAILED, transfer(prepared).state)
            assertEquals(0L, h.used(amina), "given back exactly once")
            assertEquals(1, h.bus.published.count { it.type == TransferFailed.TYPE })
        }

    @Test
    fun `FAILED after SUBMITTED gives the amount back once`() =
        runBlocking<Unit> {
            val prepared = prepared()
            outbound(prepared, CustodyTransactionState.SUBMITTED).data()

            outbound(prepared, CustodyTransactionState.FAILED).data()
            outbound(prepared, CustodyTransactionState.FAILED).data()

            assertEquals(0L, h.used(amina))
        }

    @Test
    fun `an update naming a transfer from another wallet is warned about and not applied`() =
        runBlocking<Unit> {
            val prepared = prepared()
            val bobsWallet = h.walletOn(bob)
            val update =
                h.update(
                    bobsWallet,
                    h.circleUserOf(bob),
                    CustodyDirection.OUT,
                    CustodyTransactionState.CONFIRMED,
                    tenUsdc,
                    refId = prepared.transferId.toString(),
                )

            h.applyUpdate(update).data()

            assertEquals(TransferState.AWAITING_SIGNATURE, transfer(prepared).state)
            assertEquals(
                listOf(prepared.transferId.toString() to bobsWallet.circleWalletId),
                h.warnings.refIdMismatches,
            )
            assertTrue(h.bus.published.none { it.type == TransferConfirmed.TYPE })
            assertTrue(h.frames.to<WalletFrame.TransferUpdated>(amina).isEmpty())
        }

    @Test
    fun `an update that moves the transfer clears the wallet's cached balance`() =
        runBlocking<Unit> {
            val prepared = prepared()
            val base = h.walletOn(amina)
            h.cache.put(base.circleWalletId, listOf(Holding("USDC", USDC_BASE, 6, true, BigInteger.ONE)))

            outbound(prepared, CustodyTransactionState.SUBMITTED).data()

            assertTrue(!h.cache.values.containsKey(base.circleWalletId))
        }

    @Test
    fun `an update that changes nothing keeps the cached balance, as a reconcile replaying it does`() =
        runBlocking<Unit> {
            val prepared = prepared()
            val base = h.walletOn(amina)
            outbound(prepared, CustodyTransactionState.CONFIRMED).data()
            val held = listOf(Holding("USDC", USDC_BASE, 6, true, BigInteger.ONE))
            h.cache.put(base.circleWalletId, held)

            outbound(prepared, CustodyTransactionState.CONFIRMED).data() // the same word again
            outbound(prepared, CustodyTransactionState.SUBMITTED).data() // a late one

            assertEquals(held, h.cache.values[base.circleWalletId], "nothing moved, so nothing to read again")
        }

    @Test
    fun `an inbound update clears the receiver's cached balance once, not when Circle repeats it`() =
        runBlocking<Unit> {
            prepared()
            val receiving = h.walletOn(bob)
            val held = listOf(Holding("USDC", USDC_BASE, 6, true, BigInteger.ONE))
            h.cache.put(receiving.circleWalletId, held)

            inbound(CustodyTransactionState.CONFIRMED).data()
            assertTrue(!h.cache.values.containsKey(receiving.circleWalletId), "the money landed")

            h.cache.put(receiving.circleWalletId, held)
            inbound(CustodyTransactionState.CONFIRMED).data()
            assertEquals(held, h.cache.values[receiving.circleWalletId])
        }

    @Test
    fun `an outgoing transaction we didn't prepare is still recorded in the history, once`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000)
            val update =
                h.update(
                    h.walletOn(amina),
                    h.circleUserOf(amina),
                    CustodyDirection.OUT,
                    CustodyTransactionState.CONFIRMED,
                    tenUsdc,
                    circleTxId = "tx-console",
                    destination = "0x00000000000000000000000000000000000000AA",
                )

            h.applyUpdate(update).data()
            h.applyUpdate(update).data()

            val row = rows().single()
            assertEquals(Direction.OUT, row.direction)
            assertEquals(TransferState.CONFIRMED, row.state)
            assertEquals("0x00000000000000000000000000000000000000aa", row.counterpartyAddress)
            assertTrue(h.frames.sent.none { it.second is WalletFrame.TransferUpdated })
        }

    // ---- incoming ----

    @Test
    fun `an inbound update writes the IN row once, and payment received goes out once it is confirmed`() =
        runBlocking<Unit> {
            prepared()
            h.profiles.names[amina] = "Amina"

            inbound(CustodyTransactionState.SUBMITTED).data()
            inbound(CustodyTransactionState.SUBMITTED).data()
            assertTrue(h.frames.to<WalletFrame.PaymentReceived>(bob).isEmpty(), "not until it is on chain")

            inbound(CustodyTransactionState.CONFIRMED).data()
            inbound(CustodyTransactionState.CONFIRMED).data()

            val row = rows().single { it.direction == Direction.IN }
            assertEquals(bob, row.userId)
            assertEquals(TransferState.CONFIRMED, row.state)
            assertEquals(amina, row.counterpartyId, "the sending address is Amina's wallet")
            val frame = h.frames.to<WalletFrame.PaymentReceived>(bob).single()
            assertEquals(amina, frame.fromUserId)
            assertEquals("Amina", frame.fromName)
            assertEquals(row.id, frame.transactionId)
        }

    @Test
    fun `money from an outside address names nobody, and a confirmed first sight is told at once`() =
        runBlocking<Unit> {
            h.walletOn(bob)

            inbound(CustodyTransactionState.CONFIRMED, source = "0x00000000000000000000000000000000000000Bb").data()

            val frame = h.frames.to<WalletFrame.PaymentReceived>(bob).single()
            assertEquals(null, frame.fromUserId)
            assertEquals(null, frame.fromName)
            assertEquals("0x00000000000000000000000000000000000000bb", rows().single().counterpartyAddress)
        }

    @Test
    fun `money reaching a wallet we haven't recorded yet records it, turns the wallet ACTIVE and is applied`() =
        runBlocking<Unit> {
            h.create(bob).data()
            val circleUserId = h.circleUserOf(bob)
            val signed = h.custody.sign(circleUserId)
            val unrecorded =
                signed.toChainWallet(bob, h.clock.instant())

            h
                .applyUpdate(
                    h.update(unrecorded, circleUserId, CustodyDirection.IN, CustodyTransactionState.CONFIRMED, tenUsdc),
                ).data()

            assertEquals(1, h.wallets.wallets(bob).size)
            assertEquals(
                "BASE-SEPOLIA",
                h.frames
                    .to<WalletFrame.WalletReady>(bob)
                    .single()
                    .blockchain,
            )
            assertEquals(1, h.frames.to<WalletFrame.PaymentReceived>(bob).size)
        }

    @Test
    fun `an update for a wallet that isn't ours is acknowledged and warned about`() =
        runBlocking<Unit> {
            val stranger = h.walletOn(bob).copy(circleWalletId = "w-stranger")

            h
                .applyUpdate(
                    h.update(stranger, "not-a-uuid", CustodyDirection.IN, CustodyTransactionState.CONFIRMED, 1),
                ).data()

            assertEquals(listOf("BASE-SEPOLIA" to "w-stranger"), h.warnings.unknownWallets)
            assertTrue(rows().isEmpty())
        }

    @Test
    fun `Circle being down while looking up an unknown wallet answers an error, so Circle delivers again`() =
        runBlocking<Unit> {
            h.create(bob).data()
            val circleUserId = h.circleUserOf(bob)
            val signed = h.custody.sign(circleUserId)
            h.custody.down = true
            val unrecorded =
                signed.toChainWallet(bob, h.clock.instant())

            val answer =
                h.applyUpdate(
                    h.update(unrecorded, circleUserId, CustodyDirection.IN, CustodyTransactionState.CONFIRMED, 1),
                )

            assertEquals("wallet.custody_unavailable", answer.code())
        }

    // ---- wallet.ready ----

    @Test
    fun `wallet ready goes out once, when the first chain is recorded, not for chains added later`() =
        runBlocking<Unit> {
            val circleUserId = h.activeWallet(amina)
            h.wallet(amina).data()
            h.add(amina, "SOL-DEVNET").data()
            h.custody.sign(circleUserId, "SOL-DEVNET")
            h.wallet(amina).data()
            h.clock.advance(Duration.ofMinutes(1))

            assertEquals(listOf("BASE-SEPOLIA"), h.frames.to<WalletFrame.WalletReady>(amina).map { it.blockchain })
        }
}
