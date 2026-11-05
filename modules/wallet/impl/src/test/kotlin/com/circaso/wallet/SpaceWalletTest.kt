package com.circaso.wallet

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.shared.decode
import com.circaso.wallet.api.MembershipCharge
import com.circaso.wallet.api.SpaceAccess
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.WalletCreated
import com.circaso.wallet.domain.models.CreateSpaceWalletCommand
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Space wallets (Spaces phase 3, option A as group wallets): W18 by the space's owner, who sees and signs; admins see
 * but don't sign; members pay into it (`to: { spaceId }`); a membership payment (SP13) is exact and personal; the
 * owner sends directly, no payout needed.
 */
class SpaceWalletTest {
    private val h = Harness()
    private val baraka = UserId.new() // owns the space
    private val amina = UserId.new() // an admin
    private val juma = UserId.new() // a member
    private val visitor = UserId.new()
    private val space = UUID.randomUUID()
    private val tenUsdc = 10_000_000L

    init {
        h.spaces.roles[space to baraka] = SpaceAccess.OWNER
        h.spaces.roles[space to amina] = SpaceAccess.ADMIN
        h.spaces.roles[space to juma] = SpaceAccess.MEMBER
    }

    private suspend fun setUp(by: UserId = baraka) =
        h.createSpaceWallet(CreateSpaceWalletCommand(by, space, UUID.randomUUID()))

    private suspend fun activeSpaceWallet(): WalletOwner.Space {
        val owner = setUp().data().owner
        h.custody.sign(h.circleUserOf(owner.holder))
        h.getWallet(WalletQuery(baraka, null, owner.walletId)).data()
        return owner
    }

    private suspend fun fund(owner: WalletOwner.Space, usdcMinor: Long) {
        val wallet = h.wallets.wallets(owner.holder).single()
        h.custody.held[wallet.circleWalletId] = mapOf("USDC" to BigInteger.valueOf(usdcMinor))
        h.cache.clear(wallet.circleWalletId)
    }

    private suspend fun payWith(
        sender: UserId,
        paymentId: UUID?,
        amountMinor: Long = tenUsdc,
        payee: Payee = Payee.Space(space),
    ) = h.prepareTransfer(
        TransferCommand(
            sender,
            "BASE-SEPOLIA",
            payee,
            Money(amountMinor, Asset.USDC),
            null,
            null,
            UUID.randomUUID(),
            spacePaymentId = paymentId,
        ),
    )

    @Test
    fun `W18 makes the space wallet its own Circle user with the owner as signer, for the owner only`() =
        runBlocking<Unit> {
            assertEquals("wallet.not_space_owner", setUp(by = amina).code())
            assertEquals("wallet.space_not_found", setUp(by = visitor).code())
            val setup = setUp().data()
            assertEquals(WalletStatus.SETTING_UP, setup.setup.status)
            assertNotNull(setup.setup.challengeId)
            assertEquals(baraka, setup.owner.signerId)
            assertEquals(setup.owner.walletId, setUp().data().owner.walletId, "one wallet per space")
            assertEquals(1, h.owners.spaceRows.size)
        }

    @Test
    fun `when it turns active the event names the space, and the owner and admins hear wallet ready`() =
        runBlocking<Unit> {
            val owner = activeSpaceWallet()
            val created =
                h.bus.published
                    .single { it.type == WalletCreated.TYPE }
                    .decode<WalletCreated>()
            assertEquals(space.toString(), created.spaceId)
            val ready =
                h.frames.sent
                    .filter { it.second is WalletFrame.WalletReady }
                    .map { it.first }
                    .toSet()
            assertEquals(setOf(baraka, amina), ready)
            assertEquals("wallet.already_exists", setUp().code())
            assertEquals("ACTIVE", h.findSpaceWallet(space)?.status)
            assertEquals(owner.walletId, h.findSpaceWallet(space)?.walletId)
        }

    @Test
    fun `the owner and admins see the space wallet, only the owner signs, members and others don't see it`() =
        runBlocking<Unit> {
            val owner = activeSpaceWallet()
            assertTrue(
                h
                    .getWallet(WalletQuery(amina, null, owner.walletId))
                    .data()
                    .access
                    ?.canSign == false,
            )
            assertEquals("wallet.not_found", h.getWallet(WalletQuery(juma, null, owner.walletId)).code())
            assertEquals("wallet.space_not_found", h.getSpaceWallet(juma, space).code())
            assertEquals(
                WalletStatus.ACTIVE,
                h
                    .getSpaceWallet(amina, space)
                    .data()
                    .listing
                    ?.status,
            )
            val listed = h.listMyWallets(amina).data()
            assertEquals(owner.walletId, listed.last().owner.walletId, "W16 lists the space wallets she administers")
            assertEquals(false, listed.last().canSign)
        }

    @Test
    fun `members pay into the space wallet - visitors can't, unless paying their membership`() =
        runBlocking<Unit> {
            val owner = activeSpaceWallet()
            h.fund(juma, 100_000_000)
            h.fund(visitor, 100_000_000)

            val paid = payWith(juma, paymentId = null).data().transfer
            assertEquals(owner.holder, paid.recipientId)
            assertEquals(space, paid.toSpaceId)
            assertEquals("wallet.space_not_member", payWith(visitor, paymentId = null).code())

            val paymentId = UUID.randomUUID()
            h.spaces.charges[paymentId] = visitor to MembershipCharge(paymentId, space, tenUsdc)
            assertEquals("wallet.space_payment_invalid", payWith(visitor, paymentId, amountMinor = tenUsdc - 1).code())
            assertEquals("wallet.space_payment_invalid", payWith(juma, paymentId).code(), "only its own payer")
            val membership = payWith(visitor, paymentId).data().transfer
            assertEquals(paymentId, membership.spacePaymentId)

            h.applyUpdate(
                h.update(
                    h.walletOn(visitor),
                    h.circleUserOf(visitor),
                    CustodyDirection.OUT,
                    CustodyTransactionState.CONFIRMED,
                    tenUsdc,
                    refId = membership.transferId.toString(),
                    destination = membership.destinationAddress,
                ),
            )
            val confirmed =
                h.bus.published
                    .single { it.type == TransferConfirmed.TYPE }
                    .decode<TransferConfirmed>()
            assertEquals(space.toString(), confirmed.toSpaceId)
            assertEquals(paymentId.toString(), confirmed.spacePaymentId)
        }

    @Test
    fun `the owner sends from the space wallet directly - admins can't - no payout is asked for`() =
        runBlocking<Unit> {
            val owner = activeSpaceWallet()
            fund(owner, 100_000_000)
            h.activeWallet(juma)

            fun from(by: UserId) =
                TransferCommand(
                    by,
                    "BASE-SEPOLIA",
                    Payee.Person(juma),
                    Money(tenUsdc, Asset.USDC),
                    null,
                    null,
                    UUID.randomUUID(),
                    walletId = owner.walletId,
                )
            assertEquals("wallet.not_signer", h.prepareTransfer(from(amina)).code())
            h.reach.connect(baraka, juma)
            val sent = h.prepareTransfer(from(baraka)).data().transfer
            assertEquals(owner.holder, sent.senderId)
            assertEquals(space, sent.fromSpaceId)
            assertNull(sent.payoutRequestId)
        }

    @Test
    fun `a membership is paid from the personal wallet only`() =
        runBlocking<Unit> {
            val owner = activeSpaceWallet()
            fund(owner, 100_000_000)
            val paymentId = UUID.randomUUID()
            h.spaces.charges[paymentId] = baraka to MembershipCharge(paymentId, space, tenUsdc)
            val fromSpace =
                TransferCommand(
                    baraka,
                    "BASE-SEPOLIA",
                    Payee.Space(space),
                    Money(tenUsdc, Asset.USDC),
                    null,
                    null,
                    UUID.randomUUID(),
                    walletId = owner.walletId,
                    spacePaymentId = paymentId,
                )
            assertEquals("wallet.space_payment_personal_only", h.prepareTransfer(fromSpace).code())
        }

    @Test
    fun `a space wallet's signer who no longer owns the space can't sign`() =
        runBlocking<Unit> {
            val owner = activeSpaceWallet()
            fund(owner, 100_000_000)
            h.activeWallet(juma)
            h.spaces.roles.remove(space to baraka)
            val command =
                TransferCommand(
                    baraka,
                    "BASE-SEPOLIA",
                    Payee.Person(juma),
                    Money(tenUsdc, Asset.USDC),
                    null,
                    null,
                    UUID.randomUUID(),
                    walletId = owner.walletId,
                )
            assertEquals("wallet.signer_left", h.prepareTransfer(command).code())
        }

    @Test
    fun `a payment into a space and its membership are quoted at the space rate, 1 percent, and W4 charges it`() =
        runBlocking<Unit> {
            val rules = MemoryRules()
            val fees = Harness(ruleRows = rules)
            rules.seedFees()
            fees.spaces.roles[space to baraka] = SpaceAccess.OWNER
            fees.spaces.roles[space to juma] = SpaceAccess.MEMBER
            val owner = fees.createSpaceWallet(CreateSpaceWalletCommand(baraka, space, UUID.randomUUID())).data().owner
            fees.custody.sign(fees.circleUserOf(owner.holder))
            fees.sync.sync(fees.wallets.circleUser(owner.holder)!!).data()
            fees.fund(juma, 400_000_000)
            fees.fund(visitor, 400_000_000)

            val tip = fees.quote(juma, Payee.Space(space), 100_000_000).data()
            assertEquals(1_000_000L, tip.fee.amountMinor)
            assertEquals(com.circaso.wallet.api.WalletOwnerType.SPACE, tip.fee.parties.receiverType)

            val paymentId = UUID.randomUUID()
            fees.spaces.charges[paymentId] = visitor to MembershipCharge(paymentId, space, 100_000_000)
            val membership =
                TransferCommand(
                    visitor,
                    "BASE-SEPOLIA",
                    Payee.Space(space),
                    Money(100_000_000, Asset.USDC),
                    null,
                    null,
                    UUID.randomUUID(),
                    spacePaymentId = paymentId,
                )
            val quote = fees.quoteTransfer(membership).data()
            assertEquals(1_000_000L, quote.fee.amountMinor)
            val prepared = fees.prepareTransfer(membership.copy(quoteId = quote.quoteId)).data().transfer
            assertEquals(1_000_000L, prepared.feeMinor)
            assertEquals(paymentId, prepared.spacePaymentId)
        }
}
