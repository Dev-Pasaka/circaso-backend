package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.data.repositories.TransferRepositoryImpl
import com.circaso.wallet.data.repositories.WalletExpiry
import com.circaso.wallet.data.repositories.WalletOwnerRepositoryImpl
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group wallets on PostgreSQL (V3): one wallet per group claimed by `wallet_owners_chat_key`, the owner rows read back
 * by id, group and signer, and a transfer's group fields and payout round-tripped and found by `transfers_payout_idx`.
 */
class GroupWalletDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val owners = WalletOwnerRepositoryImpl(database)
    private val transfers = TransferRepositoryImpl(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("wallet")
            WalletExpiry(database).run()
        }

    private fun group(chatId: ChatId = ChatId.new(), signer: UserId = UserId.new()) =
        WalletOwner.Group(WalletId.new(), chatId, signer, signer, now)

    @Test
    fun `a group gets one wallet however many owners' taps race for it`() =
        runBlocking<Unit> {
            val chat = ChatId.new()
            val signer = UserId.new()
            val won =
                coroutineScope {
                    (1..8).map { async(Dispatchers.IO) { owners.insert(group(chat, signer)) } }.awaitAll()
                }

            assertEquals(1, won.count { it })
            val stored = owners.byChat(chat)!!
            assertEquals(stored, owners.find(stored.walletId))
            assertEquals(listOf(stored), owners.bySigner(signer))
            assertEquals(listOf(stored), owners.byChats(listOf(chat, ChatId.new())))
            assertTrue(owners.byChats(emptyList()).isEmpty())
            assertNull(owners.find(WalletId(signer.value)), "a personal wallet has no owner row")
        }

    @Test
    fun `the same wallet id can't be filed twice`() =
        runBlocking<Unit> {
            val first = group()
            assertTrue(owners.insert(first))
            assertFalse(owners.insert(first.copy(chatId = ChatId.new())))
        }

    @Test
    fun `a transfer keeps who signed it, its payout and the groups on either side`() =
        runBlocking<Unit> {
            val wallet = group()
            val payout = UUID.randomUUID()
            val transfer =
                Transfer(
                    transferId = TransferId.new(),
                    senderId = wallet.holder,
                    senderWalletId = "w-group",
                    blockchain = "BASE-SEPOLIA",
                    idempotencyKey = UUID.randomUUID(),
                    recipientId = UserId.new(),
                    destinationAddress = "0xabc",
                    amount = Money(20_000_000, Asset.USDC),
                    state = TransferState.AWAITING_SIGNATURE,
                    circleTxId = null,
                    txHash = null,
                    chatId = wallet.chatId,
                    origin = TransferOrigin.APP,
                    proposalId = null,
                    failureReason = null,
                    createdAt = now,
                    updatedAt = now,
                    signerId = wallet.signerId,
                    payoutRequestId = payout,
                    fromGroupId = wallet.chatId,
                    toGroupId = null,
                )
            assertTrue(transfers.insert(transfer))

            assertEquals(transfer, transfers.find(transfer.transferId))
            assertEquals(listOf(transfer), transfers.forPayout(payout))
            val moved = transfers.move(transfer.transferId, TransferState.SUBMITTED, TransferChange(at = now))!!
            assertEquals(payout, moved.payoutRequestId)
            assertEquals(wallet.signerId, moved.signer)
        }
}
