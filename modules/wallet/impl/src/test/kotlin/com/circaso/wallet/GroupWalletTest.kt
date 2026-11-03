package com.circaso.wallet

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.shared.decode
import com.circaso.wallet.api.ApprovedPayout
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.domain.models.AddChainCommand
import com.circaso.wallet.domain.models.CreateGroupWalletCommand
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.ports.WalletFrame
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group wallets (Spaces phase 2, decision 2026-11-03, option A) over in-memory fakes: W14 set-up by the group's owner,
 * W15 and W16, W1/W3/W6/W12 with `walletId`, paying into a group (`to: { groupId }`), paying out only for an approved
 * payout, the frames each person gets, and the deletion guard.
 */
class GroupWalletTest {
    private val h = Harness()
    private val amina = UserId.new() // owns the group, sets the wallet's PIN
    private val otieno = UserId.new() // an admin
    private val wanjiru = UserId.new() // a member
    private val outsider = UserId.new()
    private val chat: ChatId = h.chats.group(listOf(amina, otieno, wanjiru))
    private val tenUsdc = 10_000_000L

    private suspend fun setUp(by: UserId = amina) =
        h.createGroupWallet(CreateGroupWalletCommand(by, chat, UUID.randomUUID()))

    /** The group's wallet, its PIN set and Circle's wallet recorded by a member's W1. */
    private suspend fun activeGroupWallet(): WalletOwner.Group {
        val owner = setUp().data().owner
        h.custody.sign(h.circleUserOf(owner.holder))
        h.getWallet(WalletQuery(wanjiru, null, owner.walletId)).data()
        return owner
    }

    private suspend fun fundGroup(owner: WalletOwner.Group, usdcMinor: Long) {
        val wallet = h.wallets.wallets(owner.holder).single()
        h.custody.held[wallet.circleWalletId] = mapOf("USDC" to BigInteger.valueOf(usdcMinor))
        h.cache.clear(wallet.circleWalletId)
    }

    private fun approve(
        requestId: UUID = UUID.randomUUID(),
        payee: UserId = wanjiru,
        amountMinor: Long = tenUsdc,
    ): UUID {
        h.approvals.approved[requestId] = ApprovedPayout(requestId, chat, payee, null, amountMinor)
        return requestId
    }

    private suspend fun payOut(
        owner: WalletOwner.Group,
        payee: Payee = Payee.Person(wanjiru),
        amountMinor: Long = tenUsdc,
        requestId: UUID? = null,
        by: UserId = amina,
        key: UUID = UUID.randomUUID(),
    ) = h.prepareTransfer(
        TransferCommand(
            by,
            "BASE-SEPOLIA",
            payee,
            Money(amountMinor, Asset.USDC),
            chat,
            null,
            key,
            walletId = owner.walletId,
            payoutRequestId = requestId,
        ),
    )

    // ---- W14 and W15: setting it up ----

    @Test
    fun `W14 sets up the group wallet as its own Circle user, with the owner as its signer`() =
        runBlocking<Unit> {
            val setup = setUp().data()

            assertEquals(WalletStatus.SETTING_UP, setup.setup.status)
            assertNotNull(setup.setup.challengeId, "the owner sets the group wallet's own PIN")
            assertEquals(amina, setup.owner.signerId)
            assertEquals(chat, setup.owner.chatId)
            val circleUser = h.wallets.circleUser(setup.owner.holder)
            assertNotNull(circleUser, "filed under the group wallet's id")
            assertTrue(h.wallets.circleUser(amina) == null, "Amina's personal wallet is untouched")
            assertEquals(
                WalletStatus.SETTING_UP,
                h
                    .getGroupWallet(wanjiru, chat)
                    .data()
                    .listing
                    ?.status,
            )
        }

    @Test
    fun `W14 is for the group's owner only, and the group must be one the caller is in`() =
        runBlocking<Unit> {
            assertEquals("wallet.not_group_owner", setUp(by = otieno).code())
            assertEquals("wallet.group_not_found", setUp(by = outsider).code())
            val direct = ChatId.new().also { h.chats.members[it] = setOf(amina, otieno) }
            assertEquals(
                "wallet.group_not_found",
                h.createGroupWallet(CreateGroupWalletCommand(amina, direct, UUID.randomUUID())).code(),
            )
        }

    @Test
    fun `one wallet per group - asking again gives a fresh challenge, then already_exists once it is active`() =
        runBlocking<Unit> {
            val first = setUp().data()
            val again = setUp().data()
            assertEquals(first.owner.walletId, again.owner.walletId)
            assertEquals(1, h.owners.rows.size)

            h.custody.sign(h.circleUserOf(first.owner.holder))
            h.getWallet(WalletQuery(amina, null, first.owner.walletId)).data()
            assertEquals("wallet.already_exists", setUp().code())
        }

    @Test
    fun `only the signer finishes a set-up, even after ownership of the group moved`() =
        runBlocking<Unit> {
            setUp().data()
            h.chats.owners[chat] = otieno

            assertEquals("wallet.not_signer", setUp(by = otieno).code())
        }

    @Test
    fun `when it turns active every member hears wallet ready with the wallet's id`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()

            val ready = h.frames.sent.filter { it.second is WalletFrame.WalletReady }
            assertEquals(setOf(amina, otieno, wanjiru), ready.map { it.first }.toSet())
            assertTrue(ready.all { (it.second as WalletFrame.WalletReady).walletId == owner.walletId })
            assertEquals(
                WalletStatus.ACTIVE,
                h
                    .getGroupWallet(otieno, chat)
                    .data()
                    .listing
                    ?.status,
            )
        }

    @Test
    fun `W15 answers NONE for a group without a wallet, and nothing to someone outside it`() =
        runBlocking<Unit> {
            assertNull(h.getGroupWallet(wanjiru, chat).data().listing)
            assertEquals("wallet.group_not_found", h.getGroupWallet(outsider, chat).code())
        }

    // ---- W1, W3, W6, W12 with walletId ----

    @Test
    fun `W1 with walletId shows the group wallet to its members, says whose it is and who can sign`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()
            fundGroup(owner, 50_000_000)

            val seen = h.getWallet(WalletQuery(wanjiru, null, owner.walletId)).data()
            assertEquals(WalletStatus.ACTIVE, seen.status)
            assertEquals(BigInteger.valueOf(50_000_000), seen.totals.single { it.symbol == "USDC" }.amountMinor)
            assertEquals(WalletOwnerType.GROUP, seen.access?.owner?.type)
            assertFalse(seen.access!!.canSign)
            assertTrue(
                h
                    .getWallet(WalletQuery(amina, null, owner.walletId))
                    .data()
                    .access!!
                    .canSign,
            )
            assertEquals("wallet.not_found", h.getWallet(WalletQuery(outsider, null, owner.walletId)).code())
        }

    @Test
    fun `W3 opens the group wallet's own session for its signer only`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()

            assertNotNull(h.openSession(amina, owner.walletId).data())
            assertEquals("wallet.not_signer", h.openSession(otieno, owner.walletId).code())
            assertEquals("wallet.not_found", h.openSession(outsider, owner.walletId).code())

            h.chats.members[chat] = setOf(otieno, wanjiru) // Amina left the group
            assertEquals("wallet.signer_left", h.openSession(otieno, owner.walletId).code())
        }

    @Test
    fun `W12 adds a chain to the group wallet only by its signer`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()

            assertEquals(
                "wallet.not_signer",
                h.addChain(AddChainCommand(wanjiru, "SOL-DEVNET", owner.walletId)).code(),
            )
            val added = h.addChain(AddChainCommand(amina, "SOL-DEVNET", owner.walletId)).data()
            assertNotNull(added.challengeId)
            assertEquals(setOf("SOL-DEVNET"), h.adding.list(owner.holder))
        }

    // ---- W16 ----

    @Test
    fun `W16 lists your own wallet first, then your groups' wallets, those you sign for first`() =
        runBlocking<Unit> {
            val ours = activeGroupWallet()
            val other = h.chats.group(listOf(otieno, amina, outsider))
            val theirs =
                h.createGroupWallet(CreateGroupWalletCommand(otieno, other, UUID.randomUUID())).data().owner

            val listed = h.listMyWallets(amina).data()
            assertEquals(WalletOwnerType.PERSON, listed[0].owner.type)
            assertEquals(WalletStatus.NONE, listed[0].status, "Amina has no personal wallet yet")
            assertEquals(listOf(ours.walletId, theirs.walletId), listed.drop(1).map { it.owner.walletId })
            assertEquals(listOf(true, false), listed.drop(1).map { it.canSign })
            assertEquals(listOf("BASE-SEPOLIA"), listed[1].blockchains)
            assertEquals(1, h.listMyWallets(wanjiru).data().size - 1, "Wanjiru is in one group with a wallet")
        }

    // ---- paying into a group ----

    @Test
    fun `a member pays into the group wallet - its address, the group on the transfer, the event says so`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()
            h.fund(wanjiru, 100_000_000)

            val prepared =
                h.pay(wanjiru, Payee.Group(chat), tenUsdc, chatId = chat).data().transfer
            assertEquals(owner.holder, prepared.recipientId)
            assertEquals(chat, prepared.toGroupId)
            assertNull(prepared.fromGroupId)
            assertEquals(
                h.wallets
                    .wallets(owner.holder)
                    .single()
                    .address,
                prepared.destinationAddress,
            )

            h.applyUpdate(
                h.update(
                    h.walletOn(wanjiru),
                    h.circleUserOf(wanjiru),
                    CustodyDirection.OUT,
                    CustodyTransactionState.CONFIRMED,
                    tenUsdc,
                    refId = prepared.transferId.toString(),
                    destination = prepared.destinationAddress,
                ),
            )
            val confirmed =
                h.bus.published
                    .single { it.type == TransferConfirmed.TYPE }
                    .decode<TransferConfirmed>()
            assertEquals(chat, confirmed.toGroupId)
            assertEquals(wanjiru, confirmed.signerId)
        }

    @Test
    fun `only members pay a group, and only once it has a wallet on the chain`() =
        runBlocking<Unit> {
            h.fund(outsider, 100_000_000)
            h.fund(wanjiru, 100_000_000)
            assertEquals("wallet.recipient_no_wallet", h.pay(wanjiru, Payee.Group(chat), tenUsdc).code())
            activeGroupWallet()
            assertEquals("wallet.group_not_found", h.pay(outsider, Payee.Group(chat), tenUsdc).code())
            h.fund(wanjiru, 100_000_000, "SOL-DEVNET")
            assertEquals(
                "wallet.recipient_not_on_chain",
                h.pay(wanjiru, Payee.Group(chat), tenUsdc, blockchain = "SOL-DEVNET").code(),
            )
        }

    @Test
    fun `money received by a group wallet is told to its signer, with the wallet's id`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()
            val wallet = h.wallets.wallets(owner.holder).single()
            h.frames.sent.clear()

            h.applyUpdate(
                h.update(
                    wallet,
                    h.circleUserOf(owner.holder),
                    CustodyDirection.IN,
                    CustodyTransactionState.CONFIRMED,
                    tenUsdc,
                ),
            )

            val (to, frame) = h.frames.sent.single()
            assertEquals(amina, to)
            assertEquals(owner.walletId, (frame as WalletFrame.PaymentReceived).walletId)
            val history = h.listTransactions(HistoryQuery(otieno, null, null, 50, walletId = owner.walletId)).data()
            assertEquals(1, history.entries.size, "every member reads the group wallet's history")
            assertEquals(
                "wallet.not_found",
                h.listTransactions(HistoryQuery(outsider, null, null, 50, walletId = owner.walletId)).code(),
            )
        }

    // ---- paying out: option A ----

    @Test
    fun `money leaves a group wallet only for an approved payout, to its payee, for its amount`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()
            fundGroup(owner, 100_000_000)
            h.walletOn(wanjiru)

            assertEquals("wallet.payout_required", payOut(owner).code())
            assertEquals("wallet.payout_not_approved", payOut(owner, requestId = UUID.randomUUID()).code())
            val requestId = approve()
            assertEquals(
                "wallet.payout_mismatch",
                payOut(owner, amountMinor = tenUsdc + 1, requestId = requestId).code(),
            )
            assertEquals(
                "wallet.payout_mismatch",
                payOut(owner, payee = Payee.Person(otieno), requestId = requestId).code(),
            )
            assertEquals("wallet.not_signer", payOut(owner, requestId = requestId, by = otieno).code())

            val prepared = payOut(owner, requestId = requestId).data().transfer
            assertEquals(owner.holder, prepared.senderId, "it leaves the group wallet, not Amina's")
            assertEquals(amina, prepared.signerId)
            assertEquals(chat, prepared.fromGroupId)
            assertEquals(requestId, prepared.payoutRequestId)
            assertEquals(
                h.circleUserOf(owner.holder),
                h.custody.transfers
                    .last()
                    .first,
            )
        }

    @Test
    fun `a payout is paid once at a time - a second payment waits until the first fails or expires`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()
            fundGroup(owner, 100_000_000)
            h.walletOn(wanjiru)
            val requestId = approve()
            val key = UUID.randomUUID()

            val first = payOut(owner, requestId = requestId, key = key).data().transfer
            assertEquals(first.transferId, payOut(owner, requestId = requestId, key = key).data().transfer.transferId)
            assertEquals("wallet.payout_in_progress", payOut(owner, requestId = requestId).code())

            h.clock.advance(java.time.Duration.ofMinutes(16))
            assertNotNull(payOut(owner, requestId = requestId).data(), "an unsigned one that expired doesn't count")
        }

    @Test
    fun `a personal payment never carries a payout`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000)
            h.walletOn(wanjiru)
            h.reach.connect(amina, wanjiru)
            val command =
                TransferCommand(
                    amina,
                    "BASE-SEPOLIA",
                    Payee.Person(wanjiru),
                    Money(tenUsdc, Asset.USDC),
                    null,
                    null,
                    UUID.randomUUID(),
                    payoutRequestId = UUID.randomUUID(),
                )
            assertEquals("wallet.payout_mismatch", h.prepareTransfer(command).code())
        }

    @Test
    fun `W5 shows a group payout to every member, from the wallet's side`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()
            fundGroup(owner, 100_000_000)
            h.walletOn(wanjiru)
            val transfer = payOut(owner, requestId = approve()).data().transfer

            val seen = h.getTransfer(otieno, transfer.transferId).data()
            assertTrue(seen.isOutgoing)
            assertEquals(owner.holder, seen.side)
            assertEquals(wanjiru, h.getTransfer(wanjiru, transfer.transferId).data().side, "the payee sees IN")
            assertEquals("wallet.transfer_not_found", h.getTransfer(outsider, transfer.transferId).code())
        }

    @Test
    fun `the signer hears how a group payout goes, with the wallet's id`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()
            fundGroup(owner, 100_000_000)
            h.walletOn(wanjiru)
            val transfer = payOut(owner, requestId = approve()).data().transfer
            h.frames.sent.clear()

            h.applyUpdate(
                h.update(
                    h.wallets.wallets(owner.holder).single(),
                    h.circleUserOf(owner.holder),
                    CustodyDirection.OUT,
                    CustodyTransactionState.CONFIRMED,
                    tenUsdc,
                    refId = transfer.transferId.toString(),
                    destination = transfer.destinationAddress,
                ),
            )

            val updated = h.frames.sent.filter { it.second is WalletFrame.TransferUpdated }
            assertEquals(listOf(amina), updated.map { it.first })
            assertEquals(owner.walletId, (updated.single().second as WalletFrame.TransferUpdated).walletId)
            val confirmed =
                h.bus.published
                    .single { it.type == TransferConfirmed.TYPE }
                    .decode<TransferConfirmed>()
            assertEquals(chat, confirmed.fromGroupId)
            assertEquals(transfer.payoutRequestId.toString(), confirmed.payoutRequestId)
        }

    // ---- the deletion guard ----

    @Test
    fun `the signer can't delete their account while a group wallet they sign for holds money`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()
            fundGroup(owner, 5_000_000)

            assertEquals("wallet.group_funds_remaining", h.checkFunds(amina).code())
            assertTrue(h.checkFunds(otieno) is com.circaso.shared.Resource.Success, "members aren't held back")
            fundGroup(owner, 0)
            assertTrue(h.checkFunds(amina) is com.circaso.shared.Resource.Success)
        }

    // ---- Wallets.groupWallet for chama ----

    @Test
    fun `chama reads the group's wallet, its status and signer`() =
        runBlocking<Unit> {
            assertNull(h.findGroupWallet(chat))
            setUp().data()
            assertEquals("SETTING_UP", h.findGroupWallet(chat)?.status)
            activeGroupWallet()
            val summary = assertNotNull(h.findGroupWallet(chat))
            assertTrue(summary.isActive)
            assertEquals(amina, summary.signerId)
        }
}
