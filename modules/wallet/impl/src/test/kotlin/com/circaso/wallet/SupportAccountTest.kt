package com.circaso.wallet

import com.circaso.identity.api.AccountKind
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.RecipientQuery
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Support accounts never hold a wallet (decision 2026-10-31): W2, W12, W13, W4, W9 and W10 all refuse them. */
class SupportAccountTest {
    private val h = Harness()
    private val support = UserId.new()
    private val amina = UserId.new()
    private val noWallet = WalletError.SupportAccountHasNoWallet.code

    init {
        h.accounts.kinds[support] = AccountKind.SUPPORT
    }

    /** An account that held a wallet before it became support, which the rules can't allow but must survive. */
    private suspend fun supportWithWallet(): UserId {
        val legacy = UserId.new()
        h.fund(legacy, 100_000_000)
        h.accounts.kinds[legacy] = AccountKind.SUPPORT
        return legacy
    }

    @Test
    fun `W2 never sets up a wallet for a support account, and Circle is never asked`() =
        runBlocking<Unit> {
            val refused = h.create(support)

            assertEquals(noWallet, refused.code())
            assertEquals(ErrorKind.FORBIDDEN, (refused as Resource.Error).error.kind)
            assertEquals(null, h.wallets.circleUser(support), "no Circle user is claimed for it")
            assertTrue(h.custody.calls.isEmpty(), "Circle was never called")
        }

    @Test
    fun `W12 and W13 never add a chain for a support account`() =
        runBlocking<Unit> {
            assertEquals(noWallet, h.add(support, "SOL-DEVNET").code())
            assertEquals(noWallet, h.addMissingChains(support).code())
        }

    @Test
    fun `W9 answers that a support account can't be paid, even when the payer can reach it`() =
        runBlocking<Unit> {
            h.reach.connect(amina, support)

            assertEquals(noWallet, h.checkRecipient(RecipientQuery(amina, support, null, null)).code())
            assertEquals(noWallet, h.checkRecipient(RecipientQuery(amina, support, "BASE-SEPOLIA", null)).code())
        }

    @Test
    fun `W4 refuses paying a support account, and the money stays put`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000)
            h.reach.connect(amina, support)

            val refused = h.pay(amina, Payee.Person(support), 10_000_000, "BASE-SEPOLIA", UUID.randomUUID(), null)

            assertEquals(noWallet, refused.code())
            assertTrue(h.custody.transfers.isEmpty(), "nothing went to Circle")
        }

    @Test
    fun `W4 refuses a support account paying, even one that somehow holds a wallet`() =
        runBlocking<Unit> {
            val legacy = supportWithWallet()
            h.walletOn(amina)
            h.reach.connect(legacy, amina)

            val refused = h.pay(legacy, Payee.Person(amina), 10_000_000, "BASE-SEPOLIA", UUID.randomUUID(), null)

            assertEquals(noWallet, refused.code())
            assertTrue(h.custody.transfers.isEmpty())
        }

    @Test
    fun `W10 never reminds a support account to set up a wallet`() =
        runBlocking<Unit> {
            h.reach.connect(amina, support)

            assertEquals(noWallet, h.requestSetup(amina, support).code())
            assertTrue(h.frames.sent.isEmpty())
        }

    @Test
    fun `a person is unaffected by the rule`() =
        runBlocking<Unit> {
            h.reach.connect(amina, UserId.new())
            assertEquals(AccountKind.PERSON, h.accounts.kind(amina))
            h.create(amina).data()
        }
}
