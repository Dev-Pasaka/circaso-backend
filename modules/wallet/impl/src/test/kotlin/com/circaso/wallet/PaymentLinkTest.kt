package com.circaso.wallet

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.data.cache.toEnvelope
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.ReconcilePass
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.ports.WalletFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Money from a Circaso wallet names the transfer that paid it (2026-11-05): the recipient's `IN` row gets
 * `paid_by_transfer_id` and the transfer's chat, and `payment.received` carries both, so the phone can show the
 * chat's Received card from the server's record. Linked by the transaction hash, or by the one transfer that fits;
 * never a guess between two.
 */
class PaymentLinkTest {
    private val h = Harness()
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val chat = ChatId.new()
    private val tenUsdc = 10_000_000L

    private suspend fun ready() {
        h.fund(amina, 100_000_000)
        h.walletOn(bob)
        h.reach.connect(amina, bob)
        h.chats.members[chat] = setOf(amina, bob)
    }

    private suspend fun paid(state: CustodyTransactionState = CustodyTransactionState.SUBMITTED): Transfer {
        val transfer = h.pay(amina, Payee.Person(bob), tenUsdc, chatId = chat).data().transfer
        h
            .applyUpdate(
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
            ).data()
        return transfers().getValue(transfer.transferId)
    }

    private suspend fun inboundUpdate(
        circleTxId: String = "tx-in-1",
        source: String? = null,
        txHash: String = "0xhash",
    ): CustodyTransaction =
        h
            .update(
                h.walletOn(bob),
                h.circleUserOf(bob),
                CustodyDirection.IN,
                CustodyTransactionState.CONFIRMED,
                tenUsdc,
                circleTxId = circleTxId,
                source = source ?: h.walletOn(amina).address,
            ).copy(txHash = txHash)

    private suspend fun inbound(
        circleTxId: String = "tx-in-1",
        source: String? = null,
        txHash: String = "0xhash",
    ) = h.applyUpdate(inboundUpdate(circleTxId, source, txHash)).data()

    private fun transfers() = (h.transfers as MemoryTransferRepository).rows

    private fun incoming(circleTxId: String = "tx-in-1") =
        (h.transactions as MemoryTransactionRepository).rows.single {
            it.direction == Direction.IN && it.circleTxId == circleTxId
        }

    private fun received() = h.frames.to<WalletFrame.PaymentReceived>(bob)

    @Test
    fun `the transfer whose hash is the inbound hash pays the row, whatever the case, beside others that fit`() =
        runBlocking<Unit> {
            ready()
            val other = paid(CustodyTransactionState.CONFIRMED)
            transfers()[other.transferId] = other.copy(txHash = "0xother")
            val paying = paid(CustodyTransactionState.CONFIRMED)
            transfers()[paying.transferId] = paying.copy(txHash = "0xAbC")

            inbound(txHash = "0xabc")

            val row = incoming()
            assertEquals(paying.transferId, row.paidByTransferId)
            assertEquals(chat, row.chatId)
            assertNull(row.transferId, "transfer_id stays the sender's OUT row's")
            val frame = received().single()
            assertEquals(paying.transferId, frame.transferId)
            assertEquals(chat, frame.chatId)
            assertEquals(row.id, frame.transactionId)
        }

    @Test
    fun `the one transfer that fits and has no hash yet pays the row`() =
        runBlocking<Unit> {
            ready()
            val paying = paid(CustodyTransactionState.SUBMITTED)
            assertNull(paying.txHash)

            inbound()

            assertEquals(paying.transferId, incoming().paidByTransferId)
            assertEquals(chat, incoming().chatId)
            assertEquals(paying.transferId, received().single().transferId)
        }

    @Test
    fun `money confirmed before the outbound webhook moved the transfer is linked, and the late webhook keeps it`() =
        runBlocking<Unit> {
            ready()
            val prepared = h.pay(amina, Payee.Person(bob), tenUsdc, chatId = chat).data().transfer
            assertEquals(TransferState.AWAITING_SIGNATURE, transfers().getValue(prepared.transferId).state)

            inbound()

            assertEquals(prepared.transferId, incoming().paidByTransferId)
            assertEquals(chat, incoming().chatId)
            assertEquals(prepared.transferId, received().single().transferId)

            h
                .applyUpdate(
                    h.update(
                        h.walletOn(amina),
                        h.circleUserOf(amina),
                        CustodyDirection.OUT,
                        CustodyTransactionState.CONFIRMED,
                        tenUsdc,
                        refId = prepared.transferId.toString(),
                        circleTxId = "tx-out-${prepared.transferId}",
                        destination = prepared.destinationAddress,
                    ),
                ).data()

            assertEquals(TransferState.CONFIRMED, transfers().getValue(prepared.transferId).state)
            assertEquals(prepared.transferId, incoming().paidByTransferId)
            assertEquals(1, received().size, "the late outbound webhook tells the recipient nothing more")
        }

    @Test
    fun `a transfer marked EXPIRED that the chain shows as paid is linked`() =
        runBlocking<Unit> {
            ready()
            val prepared = h.pay(amina, Payee.Person(bob), tenUsdc, chatId = chat).data().transfer
            h.transfers.move(prepared.transferId, TransferState.EXPIRED, TransferChange(at = h.clock.instant()))

            inbound()

            assertEquals(prepared.transferId, incoming().paidByTransferId)
        }

    @Test
    fun `a failed transfer never pays a row`() =
        runBlocking<Unit> {
            ready()
            paid(CustodyTransactionState.FAILED)

            inbound()

            assertNull(incoming().paidByTransferId)
        }

    @Test
    fun `two transfers that fit equally link nothing, and the money is still told`() =
        runBlocking<Unit> {
            ready()
            paid(CustodyTransactionState.SUBMITTED)
            paid(CustodyTransactionState.SUBMITTED)

            inbound()

            assertNull(incoming().paidByTransferId)
            assertNull(incoming().chatId)
            val frame = received().single()
            assertNull(frame.transferId)
            assertNull(frame.chatId)
            assertEquals(amina, frame.fromUserId)
        }

    @Test
    fun `a transfer with a different hash is ruled out`() =
        runBlocking<Unit> {
            ready()
            val other = paid(CustodyTransactionState.CONFIRMED)
            transfers()[other.transferId] = other.copy(txHash = "0xother")

            inbound(txHash = "0xmine")

            assertNull(incoming().paidByTransferId)
        }

    @Test
    fun `money from an outside address links nothing, even when a Circaso transfer fits`() =
        runBlocking<Unit> {
            ready()
            paid(CustodyTransactionState.SUBMITTED)

            inbound(source = "0x00000000000000000000000000000000000000Bb")

            assertNull(incoming().paidByTransferId)
            assertNull(received().single().transferId)
        }

    @Test
    fun `a repeated notification links once and tells once, and a linked transfer pays no second row`() =
        runBlocking<Unit> {
            ready()
            val paying = paid(CustodyTransactionState.SUBMITTED)

            repeat(3) { inbound() }
            inbound(circleTxId = "tx-in-2")

            assertEquals(paying.transferId, incoming().paidByTransferId)
            assertNull(incoming("tx-in-2").paidByTransferId, "the only fitting transfer already paid tx-in-1")
            assertEquals(1, (h.transactions as MemoryTransactionRepository).rows.count { it.paidByTransferId != null })
            assertEquals(listOf(paying.transferId, null), received().map { it.transferId })
        }

    @Test
    fun `the reconciler's path links the same way`() =
        runBlocking<Unit> {
            ready()
            val paying = paid(CustodyTransactionState.SUBMITTED)

            // The inbound webhook is lost; Circle lists it and the light pass applies it through CustodyUpdates.
            h.custody.record(inboundUpdate(circleTxId = "tx-in-listed"))
            h.getWallet(WalletQuery(bob, null)).data()
            h.clock.advance(Duration.ofMinutes(1))
            h.reconcileWallets(ReconcilePass.ACTIVE)

            assertEquals(paying.transferId, incoming("tx-in-listed").paidByTransferId)
            assertEquals(chat, received().single().chatId)
        }

    @Test
    fun `payment received carries transferId and chatId on the wire, and null for an unlinked one`() {
        val transferId = TransferId.new()
        val linked =
            WalletFrame.PaymentReceived(
                UUID.randomUUID(),
                "BASE-SEPOLIA",
                Money(tenUsdc, Asset.USDC),
                amina,
                "Amina",
                transferId = transferId,
                chatId = chat,
            )

        val data = linked.toEnvelope(1, Instant.EPOCH).data.jsonObject
        assertEquals(transferId.toString(), data.getValue("transferId").jsonPrimitive.content)
        assertEquals(chat.toString(), data.getValue("chatId").jsonPrimitive.content)

        val plain =
            linked
                .copy(transferId = null, chatId = null)
                .toEnvelope(2, Instant.EPOCH)
                .data.jsonObject
        assertEquals(JsonNull, plain["transferId"] ?: JsonNull)
        assertEquals(JsonNull, plain["chatId"] ?: JsonNull)
    }
}
