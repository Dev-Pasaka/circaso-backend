package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.CircasoJson
import com.circaso.shared.Money
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.decodeData
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferUpdatedFrame
import com.circaso.wallet.api.WalletFrames
import com.circaso.wallet.data.cache.RealtimeFramesImpl
import com.circaso.wallet.data.repositories.DatabaseTransactor
import com.circaso.wallet.data.repositories.TransactionRepositoryImpl
import com.circaso.wallet.data.repositories.TransferRepositoryImpl
import com.circaso.wallet.data.repositories.WalletExpiry
import com.circaso.wallet.data.repositories.WalletOutboxImpl
import com.circaso.wallet.data.repositories.WalletRepositoryImpl
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.ports.WalletFrame
import com.circaso.wallet.domain.usecases.ReplayFrames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Transfers on PostgreSQL (infrastructure's test container, migrated by wallet's own Flyway scripts): the key claim,
 * the transition guard under racing webhooks, inbound duplicates, and the frames kept for replay.
 */
class TransferDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val transfers = TransferRepositoryImpl(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("wallet")
            WalletExpiry(database).run()
        }

    private fun harness() =
        Harness(
            WalletRepositoryImpl(database),
            DatabaseTransactor(database),
            WalletOutboxImpl(database),
            transfers,
            TransactionRepositoryImpl(database),
        )

    private fun count(sql: String, vararg values: Any): Int =
        TestPostgres.superuser { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun transfer(senderId: UserId = UserId.new(), key: UUID = UUID.randomUUID()) =
        Transfer(
            transferId = TransferId.new(),
            senderId = senderId,
            senderWalletId = "w-1",
            blockchain = "BASE-SEPOLIA",
            idempotencyKey = key,
            recipientId = UserId.new(),
            destinationAddress = "0xabc",
            amount = Money(20_000_000, Asset.USDC),
            state = TransferState.AWAITING_SIGNATURE,
            circleTxId = null,
            txHash = null,
            chatId = ChatId.new(),
            origin = TransferOrigin.ASHA,
            proposalId = UUID.randomUUID(),
            failureReason = null,
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `the jobs find open transfers by state and age, newest first, and the parties of recent history`() =
        runBlocking<Unit> {
            val old = transfer().copy(createdAt = now.minusSeconds(3_600))
            val recent = transfer().copy(createdAt = now.minusSeconds(60))
            val fresh = transfer().copy(createdAt = now)
            listOf(old, recent, fresh).forEach { transfers.insert(it) }
            transfers.move(recent.transferId, TransferState.SUBMITTED, TransferChange(at = now))

            val awaiting =
                transfers.inState(TransferState.AWAITING_SIGNATURE, Instant.EPOCH, now, limit = 10)
            assertEquals(listOf(old.transferId), awaiting.map { it.transferId }, "created before, not at, the cut")
            val submitted =
                transfers.inState(TransferState.SUBMITTED, now.minusSeconds(120), now.minusSeconds(30), limit = 10)
            assertEquals(listOf(recent.transferId), submitted.map { it.transferId })
            assertEquals(1, transfers.inState(TransferState.AWAITING_SIGNATURE, Instant.EPOCH, now, 1).size)

            val h = harness()
            val amina = UserId.new()
            val bob = UserId.new()
            h.fund(amina, 100_000_000)
            h.walletOn(bob)
            h.reach.connect(amina, bob)
            h.pay(amina, Payee.Person(bob), 10_000_000).data()

            val parties = TransactionRepositoryImpl(database).parties(h.clock.instant().minusSeconds(60), limit = 10)
            assertEquals(setOf(amina, bob), parties.toSet(), "the sender's OUT row names the recipient too")
            assertEquals(
                emptyList(),
                TransactionRepositoryImpl(database).parties(h.clock.instant().plusSeconds(60), limit = 10),
            )
        }

    @Test
    fun `a transfer is claimed once per sender and key, read back as written, and moved only by the guard`() =
        runBlocking<Unit> {
            val first = transfer()
            assertEquals(true, transfers.insert(first))
            assertEquals(false, transfers.insert(transfer(first.senderId, first.idempotencyKey)))
            assertEquals(first, transfers.byKey(first.senderId, first.idempotencyKey))
            assertEquals(first, transfers.find(first.transferId))

            val later = now.plusSeconds(5)
            val confirmed =
                transfers.move(
                    first.transferId,
                    TransferState.CONFIRMED,
                    TransferChange(circleTxId = "tx-1", txHash = "0xh", at = later),
                )
            assertEquals(TransferState.CONFIRMED, confirmed?.state)
            assertEquals("tx-1", confirmed?.circleTxId)
            assertEquals(later, confirmed?.updatedAt)
            assertNull(transfers.move(first.transferId, TransferState.SUBMITTED, TransferChange(at = later)))
            assertNull(transfers.move(first.transferId, TransferState.FAILED, TransferChange(at = later)))
        }

    @Test
    fun `two W4 requests with one key at once make one transfer and one history row`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) {
                val h = harness()
                val (amina, bob) = UserId.new() to UserId.new()
                h.fund(amina, 100_000_000)
                h.walletOn(bob)
                h.reach.connect(amina, bob)
                val key = UUID.randomUUID()

                val answers = concurrently(2) { h.pay(amina, Payee.Person(bob), 1_000_000, key = key) }

                assertEquals(1, answers.map { (it as Resource.Success).data.transfer.transferId }.toSet().size)
                assertEquals(1, count("SELECT count(*) FROM wallet.transfers WHERE sender_id = ?", amina.value))
                assertEquals(1, count("SELECT count(*) FROM wallet.transactions WHERE user_id = ?", amina.value))
            }
        }

    @Test
    fun `racing CONFIRMED webhooks move the transfer once, with one outbox row and one event`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) {
                val h = harness()
                val (amina, bob) = UserId.new() to UserId.new()
                h.fund(amina, 100_000_000)
                h.walletOn(bob)
                h.reach.connect(amina, bob)
                val prepared = h.pay(amina, Payee.Person(bob), 1_000_000).data().transfer
                val update =
                    h.update(
                        h.walletOn(amina),
                        h.circleUserOf(amina),
                        CustodyDirection.OUT,
                        CustodyTransactionState.CONFIRMED,
                        1_000_000,
                        refId = prepared.transferId.toString(),
                    )

                concurrently(4) { h.applyUpdate(update) }

                assertEquals(1, h.bus.published.count { it.type == TransferConfirmed.TYPE })
                assertEquals(
                    1,
                    count(
                        "SELECT count(*) FROM wallet.outbox WHERE type = ? AND payload LIKE ?",
                        TransferConfirmed.TYPE,
                        "%${prepared.transferId}%",
                    ),
                )
                assertEquals(
                    1,
                    count(
                        "SELECT count(*) FROM wallet.transactions WHERE transfer_id = ? AND state = 'CONFIRMED'",
                        prepared.transferId.value,
                    ),
                )
                assertEquals(1, h.frames.to<WalletFrame.TransferUpdated>(amina).size)
            }
        }

    @Test
    fun `a repeated inbound notification, even at once, adds one row and tells the recipient once`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) {
                val h = harness()
                val bob = UserId.new()
                val wallet = h.walletOn(bob)
                val update =
                    h.update(
                        wallet,
                        h.circleUserOf(bob),
                        CustodyDirection.IN,
                        CustodyTransactionState.CONFIRMED,
                        5_000_000,
                    )

                concurrently(4) { h.applyUpdate(update) }

                assertEquals(1, count("SELECT count(*) FROM wallet.transactions WHERE user_id = ?", bob.value))
                assertEquals(1, h.frames.to<WalletFrame.PaymentReceived>(bob).size)
            }
        }

    @Test
    fun `frames are kept in wallet realtime and replayed in order, or resync after a gap`() =
        runBlocking<Unit> {
            val delivered = CopyOnWriteArrayList<RealtimeEnvelope>()
            val realtime =
                object : Realtime {
                    override suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope): Resource<Unit> {
                        delivered += envelope
                        return Resource.success(Unit)
                    }

                    override suspend fun deliverMany(
                        userIds: Collection<UserId>,
                        envelope: RealtimeEnvelope,
                    ): Resource<Unit> = Resource.success(Unit)
                }
            val frames = RealtimeFramesImpl(WalletRedisTest.MemoryRedis(), database, realtime, MutableClock(now))
            val amina = UserId.new()
            val transferId = TransferId.new()

            frames.send(amina, WalletFrame.WalletReady("BASE-SEPOLIA"))
            frames.send(
                amina,
                WalletFrame.TransferUpdated(transferId, "CONFIRMED", "BASE-SEPOLIA", Money(1, Asset.USDC), "0xh", null),
            )

            assertEquals(listOf(1L, 2L), delivered.map { it.seq })
            assertEquals(WalletFrames.MODULE, delivered.first().module)
            val kept = ReplayFrames(frames)(amina, 0).data()!!
            assertEquals(listOf(1L, 2L), kept.map { it.seq })
            val replayed = CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), kept[1].json)
            assertEquals(WalletFrames.TRANSFER_UPDATED, replayed.type)
            assertEquals("0.000001", replayed.decodeData<TransferUpdatedFrame>().amount.amount)
            assertEquals(emptyList(), ReplayFrames(frames)(amina, 2).data(), "nothing missed")
            assertNull(ReplayFrames(frames)(amina, -1).data(), "a gap before what is kept: resync")
        }

    private companion object {
        const val RACE_RUNS = 10

        suspend fun <T> concurrently(times: Int, block: suspend (Int) -> T): List<T> =
            coroutineScope { (0 until times).map { async(Dispatchers.Default) { block(it) } }.awaitAll() }
    }
}
