package com.circaso.wallet

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.TransferFailed
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.ReconcilePass
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.ports.WalletFrame
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wallet's jobs, the safety net for a lost Circle webhook: `ExpireUnsignedTransfers`, `CheckStuckTransfers` and
 * `ReconcileWallets`. Each case checks that the job ends where the webhook would have, that running it again changes
 * nothing, and that the webhook arriving late after all changes nothing either.
 */
class JobsTest {
    private val tenUsdc = 10_000_000L
    private val day = LocalDate.of(2026, 10, 28)

    /** One world: Amina with 100 USDC, Bob with a wallet, the two connected. */
    private inner class World {
        val h = Harness()
        val amina: UserId = UserId.new()
        val bob: UserId = UserId.new()

        suspend fun ready(): World {
            h.fund(amina, 100_000_000)
            h.walletOn(bob)
            h.reach.connect(amina, bob)
            // Setting the world up read both wallets; each test says who is looking.
            h.active.seen.clear()
            return this
        }

        suspend fun pay(): Transfer = h.pay(amina, Payee.Person(bob), tenUsdc).data().transfer

        /** Circle's word on Amina's outgoing transfer. */
        suspend fun outbound(transfer: Transfer, state: CustodyTransactionState): CustodyTransaction =
            h.update(
                h.walletOn(amina),
                h.circleUserOf(amina),
                CustodyDirection.OUT,
                state,
                tenUsdc,
                refId = transfer.transferId.toString(),
                circleTxId = "tx-out-${transfer.transferId}",
                destination = transfer.destinationAddress,
            )

        /** Circle's word on money reaching Bob's wallet, from Amina's wallet or an outside address. */
        suspend fun inbound(
            state: CustodyTransactionState,
            circleTxId: String = "tx-in-1",
            source: String? = null,
        ): CustodyTransaction =
            h.update(
                h.walletOn(bob),
                h.circleUserOf(bob),
                CustodyDirection.IN,
                state,
                tenUsdc,
                circleTxId = circleTxId,
                source = source ?: h.walletOn(amina).address,
            )

        /** The webhook: Circle tells us. */
        suspend fun webhook(update: CustodyTransaction) = h.applyUpdate(update).data()

        /** The webhook is lost: only Circle knows. */
        fun lost(update: CustodyTransaction) = h.custody.record(update)

        fun transfer(): Transfer = (h.transfers as MemoryTransferRepository).rows.values.single()

        /** What a user and the chat can see, without the ids that differ between worlds. */
        fun outcome(): Outcome {
            val rows = (h.transactions as MemoryTransactionRepository).rows
            val transfer = (h.transfers as MemoryTransferRepository).rows.values.singleOrNull()
            return Outcome(
                transferState = transfer?.state,
                txHash = transfer?.txHash,
                knowsCircleTx = transfer?.circleTxId != null,
                rows = rows.map { Triple(it.userId.role(), it.direction, it.state) }.sortedBy { it.toString() },
                events = h.bus.published.map { it.type },
                outbox = (h.outbox as RecordingOutbox).written.map { it.type },
                senderFrames = h.frames.to<WalletFrame.TransferUpdated>(amina).map { it.state },
                received =
                    h.frames.to<WalletFrame.PaymentReceived>(bob).map {
                        Triple(it.amount.amountMinor, it.fromUserId?.role(), it.blockchain)
                    },
                spent = h.used(amina),
            )
        }

        private fun UserId.role() =
            when (this) {
                amina -> "amina"
                bob -> "bob"
                else -> "other"
            }
    }

    data class Outcome(
        val transferState: TransferState?,
        val txHash: String?,
        val knowsCircleTx: Boolean,
        val rows: List<Triple<String, Direction, TransferState>>,
        val events: List<String>,
        val outbox: List<String>,
        val senderFrames: List<String>,
        val received: List<Triple<Long, String?, String>>,
        val spent: Long,
    )

    private fun world(block: suspend World.() -> Unit): World =
        runBlocking {
            World().ready().also { it.block() }
        }

    // ---- ExpireUnsignedTransfers ----

    @Test
    fun `an unsigned transfer expires after 15 minutes, with its OUT row, its amount back and the sender told`() {
        world {
            val prepared = pay()
            h.clock.advance(Duration.ofMinutes(14))
            assertEquals(0, h.expireUnsigned(), "still inside the signing window")

            h.clock.advance(Duration.ofMinutes(1).plusSeconds(1))
            assertEquals(1, h.expireUnsigned())

            assertEquals(TransferState.EXPIRED, transfer().state)
            assertEquals(TransferState.EXPIRED, outcome().rows.single().third)
            assertEquals(listOf("EXPIRED"), outcome().senderFrames)
            assertEquals(0L, outcome().spent, "the amount goes back to the daily limit")
            assertTrue(h.bus.published.none { it.type.startsWith("wallet.Transfer") }, "no event for EXPIRED")

            val once = outcome()
            assertEquals(0, h.expireUnsigned(), "a second run finds nothing")
            assertEquals(once, outcome())
            assertEquals(prepared.transferId, transfer().transferId)
        }
    }

    @Test
    fun `Circle confirming an expired transfer ends as the webhook alone would, taking the amount again`() {
        val byWebhook =
            world {
                val prepared = pay()
                webhook(outbound(prepared, CustodyTransactionState.CONFIRMED))
            }.outcome()

        val afterExpiry =
            world {
                val prepared = pay()
                h.clock.advance(Duration.ofMinutes(16))
                h.expireUnsigned()
                webhook(outbound(prepared, CustodyTransactionState.CONFIRMED))
                webhook(outbound(prepared, CustodyTransactionState.CONFIRMED))
            }.outcome()

        assertEquals(TransferState.CONFIRMED, afterExpiry.transferState)
        assertEquals(tenUsdc, afterExpiry.spent, "taken again: the money left")
        assertEquals(byWebhook.copy(senderFrames = listOf("EXPIRED", "CONFIRMED")), afterExpiry)
    }

    @Test
    fun `a transfer Circle already moved is not expired`() {
        world {
            val prepared = pay()
            webhook(outbound(prepared, CustodyTransactionState.SUBMITTED))
            h.clock.advance(Duration.ofMinutes(20))

            assertEquals(0, h.expireUnsigned())
            assertEquals(TransferState.SUBMITTED, transfer().state)
        }
    }

    // ---- CheckStuckTransfers ----

    @Test
    fun `a submitted transfer whose CONFIRMED webhook was lost ends exactly as the webhook would have left it`() {
        val byWebhook =
            world {
                val prepared = pay()
                webhook(outbound(prepared, CustodyTransactionState.SUBMITTED))
                webhook(outbound(prepared, CustodyTransactionState.CONFIRMED))
            }.outcome()

        world {
            val prepared = pay()
            webhook(outbound(prepared, CustodyTransactionState.SUBMITTED))
            lost(outbound(prepared, CustodyTransactionState.CONFIRMED))
            h.clock.advance(Duration.ofSeconds(31))

            assertEquals(1, h.checkStuck())
            assertEquals(byWebhook, outcome())
            assertEquals(1, h.bus.published.count { it.type == TransferConfirmed.TYPE })

            h.checkStuck()
            assertEquals(byWebhook, outcome(), "a second run changes nothing")

            webhook(outbound(prepared, CustodyTransactionState.CONFIRMED))
            assertEquals(byWebhook, outcome(), "the webhook arriving late changes nothing")
        }
    }

    @Test
    fun `a lost FAILED webhook fails the transfer once and gives the amount back once, as the webhook would`() {
        val byWebhook =
            world {
                val prepared = pay()
                webhook(outbound(prepared, CustodyTransactionState.SUBMITTED))
                webhook(outbound(prepared, CustodyTransactionState.FAILED))
            }.outcome()

        world {
            val prepared = pay()
            webhook(outbound(prepared, CustodyTransactionState.SUBMITTED))
            lost(outbound(prepared, CustodyTransactionState.FAILED))
            h.clock.advance(Duration.ofSeconds(31))

            h.checkStuck()
            h.checkStuck()
            webhook(outbound(prepared, CustodyTransactionState.FAILED))

            assertEquals(byWebhook, outcome())
            assertEquals(0L, outcome().spent)
            assertEquals(1, h.bus.published.count { it.type == TransferFailed.TYPE })
        }
    }

    @Test
    fun `a transfer submitted less than 30 seconds ago is left to its webhook`() {
        world {
            val prepared = pay()
            webhook(outbound(prepared, CustodyTransactionState.SUBMITTED))
            lost(outbound(prepared, CustodyTransactionState.CONFIRMED))
            h.clock.advance(Duration.ofSeconds(10))

            assertEquals(0, h.checkStuck())
            assertEquals(0, h.custody.count("transaction"))
            assertEquals(TransferState.SUBMITTED, transfer().state)
        }
    }

    @Test
    fun `a transfer still stuck an hour after it was prepared is reported once`() {
        world {
            val prepared = pay()
            webhook(outbound(prepared, CustodyTransactionState.SUBMITTED))
            lost(outbound(prepared, CustodyTransactionState.STUCK))

            h.clock.advance(Duration.ofMinutes(30))
            h.checkStuck()
            assertTrue(h.warnings.stuck.isEmpty())

            h.clock.advance(Duration.ofMinutes(31))
            h.checkStuck()
            h.checkStuck()
            assertEquals(listOf(prepared.transferId.toString()), h.warnings.stuck)
            assertEquals(TransferState.SUBMITTED, transfer().state)
        }
    }

    @Test
    fun `Circle being down stops the run and the next runs back off`() {
        world {
            val prepared = pay()
            webhook(outbound(prepared, CustodyTransactionState.SUBMITTED))
            h.clock.advance(Duration.ofMinutes(1))
            h.custody.down = true

            assertEquals(0, h.checkStuck())
            assertEquals(listOf("wallet.stuck-transfers" to 1), h.warnings.backoffs)
            val calls = h.custody.count("transaction")

            h.checkStuck()
            assertEquals(calls, h.custody.count("transaction"), "the next run is skipped")

            h.custody.down = false
            lost(outbound(prepared, CustodyTransactionState.CONFIRMED))
            h.checkStuck()
            assertEquals(TransferState.CONFIRMED, transfer().state)
        }
    }

    // ---- ReconcileWallets ----

    @Test
    fun `a lost inbound webhook is caught for a user who opened the wallet, with payment received sent once`() {
        val byWebhook =
            world {
                webhook(inbound(CustodyTransactionState.CONFIRMED))
            }.outcome()

        world {
            lost(inbound(CustodyTransactionState.CONFIRMED))
            h.clock.advance(Duration.ofMinutes(1))
            h.getWallet(WalletQuery(bob, null)).data()

            assertTrue(h.reconcileWallets(ReconcilePass.ACTIVE) > 0)
            assertEquals(byWebhook, outcome())
            assertEquals(1, outcome().received.size)

            h.reconcileWallets(ReconcilePass.ACTIVE)
            assertEquals(byWebhook, outcome(), "a second pass changes nothing")

            webhook(inbound(CustodyTransactionState.CONFIRMED))
            assertEquals(byWebhook, outcome(), "the webhook arriving late changes nothing")
        }
    }

    @Test
    fun `a user nobody has seen for longer than the active window is left to the daily pass`() {
        world {
            lost(inbound(CustodyTransactionState.CONFIRMED, source = "0x000000000000000000000000000000000000bEEF"))
            h.getWallet(WalletQuery(bob, null)).data()
            h.clock.advance(Duration.ofMinutes(31))

            assertEquals(0, h.reconcileWallets(ReconcilePass.ACTIVE))
            assertEquals(0, h.custody.count("transactions"))
            assertTrue(outcome().received.isEmpty())
        }
    }

    @Test
    fun `a payment whose every webhook was lost is settled by the light pass, for the sender and the recipient`() {
        val byWebhook =
            world {
                val prepared = pay()
                webhook(outbound(prepared, CustodyTransactionState.CONFIRMED))
                webhook(inbound(CustodyTransactionState.CONFIRMED))
            }.outcome()

        world {
            val prepared = pay()
            lost(outbound(prepared, CustodyTransactionState.CONFIRMED))
            lost(inbound(CustodyTransactionState.CONFIRMED))
            h.clock.advance(Duration.ofMinutes(2))

            // Nobody opened the wallet: the payment's own history row makes both of them active.
            h.reconcileWallets(ReconcilePass.ACTIVE)
            assertEquals(byWebhook, outcome())
            assertEquals(TransferState.CONFIRMED, transfer().state)

            h.reconcileWallets(ReconcilePass.ACTIVE)
            h.reconcileWallets(ReconcilePass.DAILY)
            webhook(outbound(prepared, CustodyTransactionState.CONFIRMED))
            webhook(inbound(CustodyTransactionState.CONFIRMED))
            assertEquals(byWebhook, outcome(), "later passes and late webhooks change nothing")
        }
    }

    @Test
    fun `the daily pass reconciles everyone with activity that day`() {
        val byWebhook =
            world {
                val prepared = pay()
                webhook(outbound(prepared, CustodyTransactionState.CONFIRMED))
                webhook(inbound(CustodyTransactionState.CONFIRMED))
            }.outcome()

        world {
            val prepared = pay()
            h.clock.advance(Duration.ofHours(2))
            lost(outbound(prepared, CustodyTransactionState.CONFIRMED))
            lost(inbound(CustodyTransactionState.CONFIRMED))
            h.clock.advance(Duration.ofHours(3))

            assertEquals(0, h.reconcileWallets(ReconcilePass.ACTIVE), "nobody active in the last 30 minutes")
            h.reconcileWallets(ReconcilePass.DAILY)
            assertEquals(byWebhook, outcome())

            h.reconcileWallets(ReconcilePass.DAILY)
            assertEquals(byWebhook, outcome(), "a second pass changes nothing")
        }
    }

    @Test
    fun `Circle being down stops a pass and backs it off`() {
        world {
            h.getWallet(WalletQuery(bob, null)).data()
            h.custody.down = true

            assertEquals(0, h.reconcileWallets(ReconcilePass.ACTIVE))
            assertEquals(listOf("wallet.reconcile-active" to 1), h.warnings.backoffs)
            val calls = h.custody.count("transactions")
            h.reconcileWallets(ReconcilePass.ACTIVE)
            assertEquals(calls, h.custody.count("transactions"), "the next pass is skipped")
        }
    }
}
