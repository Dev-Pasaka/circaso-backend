package com.circaso.wallet

import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.ApprovedPayout
import com.circaso.wallet.domain.models.CreateGroupWalletCommand
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletQuery
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Option A (decision 2026-11-03), parked on 2026-11-06 behind `WALLET_GROUP_PAYOUT_APPROVALS`: switched on, money
 * leaves a group wallet only for a payout the admins approved.
 */
class GroupPayoutApprovalsTest {
    private val h = Harness(approvalsRequired = true)
    private val amina = UserId.new()
    private val wanjiru = UserId.new()
    private val chat = h.chats.group(listOf(amina, wanjiru, UserId.new()))
    private val tenUsdc = 10_000_000L

    private suspend fun activeGroupWallet(): WalletOwner.Group {
        val owner = h.createGroupWallet(CreateGroupWalletCommand(amina, chat, UUID.randomUUID())).data().owner
        h.custody.sign(h.circleUserOf(owner.holder))
        h.getWallet(WalletQuery(wanjiru, null, owner.walletId)).data()
        val wallet = h.wallets.wallets(owner.holder).single()
        h.custody.held[wallet.circleWalletId] = mapOf("USDC" to BigInteger.valueOf(100_000_000))
        h.cache.clear(wallet.circleWalletId)
        return owner
    }

    private suspend fun payOut(owner: WalletOwner.Group, requestId: UUID? = null) =
        h.prepareTransfer(
            TransferCommand(
                amina,
                "BASE-SEPOLIA",
                Payee.Person(wanjiru),
                Money(tenUsdc, Asset.USDC),
                chat,
                null,
                UUID.randomUUID(),
                walletId = owner.walletId,
                payoutRequestId = requestId,
            ),
        )

    @Test
    fun `with approvals on, money leaves a group wallet only for an approved payout`() =
        runBlocking<Unit> {
            val owner = activeGroupWallet()
            h.walletOn(wanjiru)

            assertEquals("wallet.payout_required", payOut(owner).code())
            val requestId = UUID.randomUUID()
            h.approvals.approved[requestId] = ApprovedPayout(requestId, chat, wanjiru, null, tenUsdc)
            assertEquals(requestId, payOut(owner, requestId).data().transfer.payoutRequestId)
        }
}
