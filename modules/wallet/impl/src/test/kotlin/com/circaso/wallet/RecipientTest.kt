package com.circaso.wallet

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.RecipientQuery
import com.circaso.wallet.domain.models.RecipientStatus
import com.circaso.wallet.domain.models.ReminderOutcome
import com.circaso.wallet.domain.ports.WalletFrame
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W9 who can be paid, W10 reminding a contact, W11 an outside address, over in-memory fakes. */
class RecipientTest {
    private val h = Harness()
    private val amina = UserId.new()
    private val bob = UserId.new()

    private suspend fun check(blockchain: String? = null, chatId: ChatId? = null) =
        h.checkRecipient(RecipientQuery(amina, bob, blockchain, chatId))

    // ---- W9 ----

    @Test
    fun `W9 says NO_WALLET, then the chains they can be paid on, and NO_WALLET_ON_CHAIN for another`() =
        runBlocking<Unit> {
            h.reach.connect(amina, bob)
            assertEquals(RecipientStatus.NO_WALLET, check().data().status)

            h.walletOn(bob)
            val any = check().data()
            assertEquals(RecipientStatus.CAN_RECEIVE, any.status)
            assertEquals(listOf("BASE-SEPOLIA"), any.blockchains)
            assertEquals(RecipientStatus.CAN_RECEIVE, check("BASE-SEPOLIA").data().status)
            assertEquals(RecipientStatus.NO_WALLET_ON_CHAIN, check("SOL-DEVNET").data().status)
        }

    @Test
    fun `W9 leaves out a chain retired from receiving`() =
        runBlocking<Unit> {
            h.reach.connect(amina, bob)
            h.walletOn(bob)
            h.walletOn(bob, "MATIC-AMOY")

            assertEquals(listOf("BASE-SEPOLIA"), check().data().blockchains)
            assertEquals(RecipientStatus.NO_WALLET_ON_CHAIN, check("MATIC-AMOY").data().status)
        }

    @Test
    fun `W9 needs reach or the shared chat, and refuses yourself and an unsupported chain`() =
        runBlocking<Unit> {
            h.walletOn(bob)
            assertEquals("users.not_reachable", check().code())
            val chat = ChatId.new()
            h.chats.members[chat] = setOf(amina, bob)
            assertEquals(RecipientStatus.CAN_RECEIVE, check(chatId = chat).data().status)

            assertEquals("wallet.self_transfer", h.checkRecipient(RecipientQuery(amina, amina, null, null)).code())
            assertEquals("wallet.chain_unsupported", check("AVAX-FUJI").code())
        }

    // ---- W10 ----

    @Test
    fun `W10 tells a contact without a wallet once a day, naming who wants to pay`() =
        runBlocking<Unit> {
            h.reach.connect(amina, bob)
            h.profiles.names[amina] = "Amina"

            assertEquals(ReminderOutcome.SENT, h.requestSetup(amina, bob).data())
            assertEquals(ReminderOutcome.ALREADY_SENT, h.requestSetup(amina, bob).data())

            val frame = h.frames.to<WalletFrame.SetupRequested>(bob).single()
            assertEquals(amina, frame.fromUserId)
            assertEquals("Amina", frame.fromName)
        }

    @Test
    fun `W10 to someone with a wallet, someone unreachable, or yourself sends nothing`() =
        runBlocking<Unit> {
            assertEquals("users.not_reachable", h.requestSetup(amina, bob).code())
            assertEquals("wallet.self_transfer", h.requestSetup(amina, amina).code())
            h.reach.connect(amina, bob)
            h.walletOn(bob)
            assertEquals(ReminderOutcome.HAS_WALLET, h.requestSetup(amina, bob).data())
            assertTrue(h.frames.sent.none { it.second is WalletFrame.SetupRequested })
        }

    // ---- W11 ----

    @Test
    fun `W11 asks Circle whether the address is valid on that chain`() =
        runBlocking<Unit> {
            val evm = "0xAbCdEf0123456789aBcDeF0123456789AbCdEf01"
            val valid = h.validateAddress("BASE-SEPOLIA", evm).data()
            assertTrue(valid.valid)
            assertEquals(evm.lowercase(), valid.address)

            assertEquals(false, h.validateAddress("SOL-DEVNET", evm).data().valid, "an EVM address isn't Solana's")
            assertEquals("wallet.chain_unsupported", h.validateAddress("AVAX-FUJI", evm).code())
            h.custody.down = true
            assertEquals("wallet.custody_unavailable", h.validateAddress("BASE-SEPOLIA", evm).code())
        }
}
