package com.circaso.wallet

import com.circaso.identity.api.AccountStatus
import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.integrations.api.IntegrationError
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.PaymentHold
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W4 and W5 over in-memory fakes: every rule in its order, idempotency, `refId`, and what Circle's refusals do. */
class TransferTest {
    private val h = Harness()
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val tenUsdc = 10_000_000L

    /** Amina with 100 USDC on Base, Bob with a wallet on Base, and the two connected. */
    private suspend fun ready() {
        h.fund(amina, 100_000_000)
        h.walletOn(bob)
        h.reach.connect(amina, bob)
    }

    private suspend fun payBob(
        usdcMinor: Long = tenUsdc,
        blockchain: String = "BASE-SEPOLIA",
        key: UUID = UUID.randomUUID(),
        chatId: ChatId? = null,
    ) = h.pay(amina, Payee.Person(bob), usdcMinor, blockchain, key, chatId)

    // ---- the happy path ----

    @Test
    fun `W4 prepares a transfer to a person, with refId the transfer id, on the chain the request names`() =
        runBlocking<Unit> {
            ready()
            val key = UUID.randomUUID()

            val prepared = payBob(key = key).data()

            val transfer = prepared.transfer
            assertEquals(TransferState.AWAITING_SIGNATURE, transfer.state)
            assertNotNull(prepared.challengeId)
            assertEquals("token-${h.circleUserOf(amina)}", prepared.sdk?.userToken)
            assertEquals(h.clock.instant().plusSeconds(15 * 60), transfer.expiresAt)
            val (circleUser, request) = h.custody.transfers.single()
            assertEquals(h.circleUserOf(amina), circleUser)
            assertEquals(transfer.transferId.toString(), request.refId, "Circle's updates lead back to the transfer")
            assertEquals(key, request.idempotencyKey)
            assertEquals("BASE-SEPOLIA", request.blockchain)
            assertEquals(h.walletOn(amina).circleWalletId, request.walletId)
            assertEquals(h.walletOn(bob).address, request.destinationAddress)
            assertEquals(Money(tenUsdc, Asset.USDC), request.amount)
            assertEquals(bob, transfer.recipientId)
            assertEquals(TransferOrigin.APP, transfer.origin)

            val row = (h.transactions as MemoryTransactionRepository).rows.single()
            assertEquals(Direction.OUT, row.direction)
            assertEquals(amina, row.userId)
            assertEquals(transfer.transferId, row.transferId)
            assertEquals(TransferState.AWAITING_SIGNATURE, row.state)
            assertEquals(tenUsdc, h.spend.total(amina, LocalDate.of(2026, 10, 28)), "taken from today's limit")
        }

    @Test
    fun `W4 sends from the wallet on the named chain, to the recipient's address on that chain`() =
        runBlocking<Unit> {
            ready()
            h.fund(amina, 50_000_000, "SOL-DEVNET")
            val bobOnSolana = h.walletOn(bob, "SOL-DEVNET")

            payBob(blockchain = "SOL-DEVNET").data()

            val request =
                h.custody.transfers
                    .single()
                    .second
            assertEquals("SOL-DEVNET", request.blockchain)
            assertEquals(h.walletOn(amina, "SOL-DEVNET").circleWalletId, request.walletId)
            assertEquals(bobOnSolana.address, request.destinationAddress)
        }

    @Test
    fun `W4 pays an outside address valid on the chain, stored lowercase on EVM`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000)
            val address = "0xAbCdEf0123456789aBcDeF0123456789AbCdEf01"

            val transfer = h.pay(amina, Payee.Address(address), tenUsdc).data().transfer

            assertNull(transfer.recipientId)
            assertEquals(address.lowercase(), transfer.destinationAddress)
            assertEquals(1, h.custody.count("validateAddress"))
        }

    @Test
    fun `W4 confirming an Asha proposal records origin ASHA`() =
        runBlocking<Unit> {
            ready()
            val proposal = UUID.randomUUID()
            val transfer =
                h.pay(amina, Payee.Person(bob), tenUsdc, proposalId = proposal).data().transfer
            assertEquals(TransferOrigin.ASHA, transfer.origin)
            assertEquals(proposal, transfer.proposalId)
        }

    // ---- the rules, in order ----

    @Test
    fun `an account that isn't ACTIVE is refused first, whatever else is wrong`() =
        runBlocking<Unit> {
            h.accounts.statuses[amina] = AccountStatus.SUSPENDED
            assertEquals("wallet.account_restricted", payBob(usdcMinor = 0, blockchain = "NOPE").code())
        }

    @Test
    fun `then the wallet - none, still setting up, on another chain, or the chain unsupported`() =
        runBlocking<Unit> {
            assertEquals("wallet.not_found", payBob().code())
            h.create(amina).data()
            assertEquals("wallet.not_ready", payBob().code())
            h.custody.sign(h.circleUserOf(amina))
            h.wallet(amina).data()
            assertEquals("wallet.not_on_chain", payBob(blockchain = "SOL-DEVNET").code())
            assertEquals("wallet.chain_unsupported", payBob(blockchain = "AVAX-FUJI").code())
            assertEquals(0, h.custody.count("prepareTransfer"))
        }

    @Test
    fun `a frozen wallet on the chain can't send`() =
        runBlocking<Unit> {
            ready()
            val wallets = h.wallets as MemoryWalletRepository
            val base = wallets.rows.single { it.userId == amina }
            wallets.rows.remove(base)
            wallets.rows += base.copy(state = ChainWalletState.FROZEN)
            assertEquals("wallet.not_on_chain", payBob().code())
        }

    @Test
    fun `then the payment hold`() =
        runBlocking<Unit> {
            ready()
            h.holds.holds[amina] = PaymentHold(Instant.parse("2026-10-30T00:00:00Z"))
            assertEquals("wallet.on_hold", payBob(usdcMinor = 0).code(), "the hold comes before the amount")
        }

    @Test
    fun `then the amount - above zero, an offered asset, within the per-payment and daily limits`() =
        runBlocking<Unit> {
            ready()
            h.fund(amina, 2_000_000_000)
            assertEquals("wallet.amount_invalid", payBob(usdcMinor = 0).code())
            val usdt =
                TransferCommand(
                    amina,
                    "BASE-SEPOLIA",
                    Payee.Person(bob),
                    Money(1, Asset.USDT),
                    null,
                    null,
                    UUID.randomUUID(),
                )
            assertEquals("wallet.amount_invalid", h.prepareTransfer(usdt).code(), "USDT isn't offered on the chain")
            assertEquals("wallet.limit_exceeded", payBob(usdcMinor = TEST_LIMITS.perPaymentMinor + 1).code())

            payBob(usdcMinor = 500_000_000).data()
            payBob(usdcMinor = 500_000_000).data()
            assertEquals("wallet.limit_exceeded", payBob(usdcMinor = 1).code(), "1 000 USDC a day")
        }

    @Test
    fun `the amount is checked before the payee, and the payee before the balance`() =
        runBlocking<Unit> {
            h.fund(amina, 1)
            h.walletOn(bob)
            assertEquals("wallet.limit_exceeded", payBob(usdcMinor = TEST_LIMITS.perPaymentMinor + 1).code())
            assertEquals("users.not_reachable", payBob().code(), "unreachable comes before too little money")
            h.reach.connect(amina, bob)
            assertEquals("wallet.insufficient_funds", payBob().code())
        }

    @Test
    fun `a person must be reachable, or share the chat the payment is made from, and a block always wins`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000)
            h.walletOn(bob)
            val chat = ChatId.new()
            assertEquals("users.not_reachable", payBob(chatId = chat).code())

            h.chats.members[chat] = setOf(amina, bob)
            payBob(chatId = chat).data()

            h.reach.blocked += setOf(amina, bob)
            assertEquals("users.not_reachable", payBob(chatId = chat).code())
        }

    @Test
    fun `a person needs a wallet, and one on the transfer's chain that still receives`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000)
            h.fund(amina, 100_000_000, "SOL-DEVNET")
            h.fund(amina, 100_000_000, "MATIC-AMOY")
            h.reach.connect(amina, bob)
            assertEquals("wallet.recipient_no_wallet", payBob().code())

            h.walletOn(bob)
            assertEquals("wallet.recipient_not_on_chain", payBob(blockchain = "SOL-DEVNET").code())

            h.walletOn(bob, "MATIC-AMOY")
            assertEquals(
                "wallet.recipient_not_on_chain",
                payBob(blockchain = "MATIC-AMOY").code(),
                "nobody is paid on a chain retired from receiving",
            )
        }

    @Test
    fun `paying yourself is refused, by user or by your own address`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000)
            assertEquals("wallet.self_transfer", h.pay(amina, Payee.Person(amina), tenUsdc).code())
            val own =
                h
                    .walletOn(amina)
                    .address
                    .uppercase()
                    .replace("0X", "0x")
            assertEquals("wallet.self_transfer", h.pay(amina, Payee.Address(own), tenUsdc).code())
        }

    @Test
    fun `an outside address must be valid on that same chain, and a Circaso wallet on a retired chain can't be paid`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000)
            h.fund(amina, 100_000_000, "MATIC-AMOY")
            assertEquals("wallet.address_invalid", h.pay(amina, Payee.Address("0x1234"), tenUsdc).code())
            val solanaAddress = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
            assertEquals(
                "wallet.address_invalid",
                h.pay(amina, Payee.Address(solanaAddress), tenUsdc).code(),
                "a Solana address isn't valid on Base",
            )

            val bobOnAmoy = h.walletOn(bob, "MATIC-AMOY")
            assertEquals(
                "wallet.recipient_not_on_chain",
                h.pay(amina, Payee.Address(bobOnAmoy.address), tenUsdc, "MATIC-AMOY").code(),
            )
        }

    @Test
    fun `a retired chain still sends to an outside address, so it can be emptied`() =
        runBlocking<Unit> {
            h.fund(amina, 100_000_000, "MATIC-AMOY")
            val out = "0x00000000000000000000000000000000000000aa"
            assertEquals(
                TransferState.AWAITING_SIGNATURE,
                h
                    .pay(amina, Payee.Address(out), tenUsdc, "MATIC-AMOY")
                    .data()
                    .transfer.state,
            )
        }

    @Test
    fun `the balance is Circle's now on the transfer's chain, never the cache`() =
        runBlocking<Unit> {
            ready()
            val base = h.walletOn(amina)
            h.cache.put(
                base.circleWalletId,
                listOf(Holding("USDC", USDC_BASE, 6, true, BigInteger.valueOf(999_000_000))),
            )
            h.custody.held[base.circleWalletId] = mapOf("USDC" to BigInteger.valueOf(tenUsdc - 1))

            assertEquals("wallet.insufficient_funds", payBob().code())
            assertEquals(0L, h.spend.total(amina, LocalDate.of(2026, 10, 28)), "a refused transfer takes no limit")
        }

    // ---- idempotency ----

    @Test
    fun `the same key returns the same transfer and Circle's same challenge, once in the history and the limit`() =
        runBlocking<Unit> {
            ready()
            val key = UUID.randomUUID()

            val first = payBob(key = key).data()
            val again = payBob(key = key, usdcMinor = 1).data()

            assertEquals(first.transfer.transferId, again.transfer.transferId)
            assertEquals(first.challengeId, again.challengeId)
            assertEquals(tenUsdc, again.transfer.amount.amountMinor, "the first request's transfer, as it was")
            assertEquals(1, (h.transfers as MemoryTransferRepository).rows.size)
            assertEquals(1, (h.transactions as MemoryTransactionRepository).rows.size)
            assertEquals(tenUsdc, h.spend.total(amina, LocalDate.of(2026, 10, 28)))
        }

    @Test
    fun `a key reused after the transfer was signed returns it with nothing to sign, even days later`() =
        runBlocking<Unit> {
            ready()
            val key = UUID.randomUUID()
            val first = payBob(key = key).data()
            h.applyUpdate(outbound(first.transfer.transferId.toString(), "SUBMITTED")).data()
            h.clock.advance(Duration.ofDays(3))

            val again = payBob(key = key).data()

            assertEquals(TransferState.SUBMITTED, again.transfer.state)
            assertNull(again.challengeId)
            assertEquals(1, h.custody.count("prepareTransfer"))
        }

    @Test
    fun `when Circle refuses, the transfer fails, its limit comes back, and the wallet code is answered`() =
        runBlocking<Unit> {
            ready()
            h.custody.refuseTransfers = IntegrationError.InsufficientFunds()
            val key = UUID.randomUUID()

            assertEquals("wallet.insufficient_funds", payBob(key = key).code())

            val transfer = (h.transfers as MemoryTransferRepository).rows.values.single()
            assertEquals(TransferState.FAILED, transfer.state)
            assertEquals("wallet.insufficient_funds", transfer.failureReason)
            assertEquals(TransferState.FAILED, (h.transactions as MemoryTransactionRepository).rows.single().state)
            assertEquals(0L, h.spend.total(amina, LocalDate.of(2026, 10, 28)))
            h.custody.refuseTransfers = null
            assertNull(payBob(key = key).data().challengeId, "the same key answers the failed transfer")

            h.custody.refuseTransfers = IntegrationError.AddressInvalid()
            assertEquals("wallet.address_invalid", payBob().code())
            h.custody.refuseTransfers = IntegrationError.PinLocked()
            assertEquals("wallet.pin_locked", payBob().code())
        }

    @Test
    fun `when Circle is down the transfer waits, and the same request asks again`() =
        runBlocking<Unit> {
            ready()
            val key = UUID.randomUUID()
            h.custody.refuseTransfers = IntegrationError.Unavailable("Circle")

            assertEquals("wallet.custody_unavailable", payBob(key = key).code())
            val waiting = (h.transfers as MemoryTransferRepository).rows.values.single()
            assertEquals(TransferState.AWAITING_SIGNATURE, waiting.state)

            h.custody.refuseTransfers = null
            val retried = payBob(key = key).data()
            assertEquals(waiting.transferId, retried.transfer.transferId)
            assertNotNull(retried.challengeId)
        }

    @Test
    fun `a transfer past its 15 minutes is answered without a challenge`() =
        runBlocking<Unit> {
            ready()
            val key = UUID.randomUUID()
            payBob(key = key).data()
            h.clock.advance(Duration.ofMinutes(16))

            assertNull(payBob(key = key).data().challengeId)
        }

    // ---- W5 ----

    @Test
    fun `W5 shows a transfer to its sender and to the person paid, and to nobody else`() =
        runBlocking<Unit> {
            ready()
            val transferId = payBob().data().transfer.transferId

            assertEquals(transferId, h.getTransfer(amina, transferId).data().transferId)
            assertEquals(transferId, h.getTransfer(bob, transferId).data().transferId)
            assertEquals("wallet.transfer_not_found", h.getTransfer(UserId.new(), transferId).code())
            assertEquals(
                "wallet.transfer_not_found",
                h.getTransfer(amina, TransferId.new()).code(),
            )
        }

    private suspend fun outbound(refId: String, state: String) =
        h.update(
            h.walletOn(amina),
            h.circleUserOf(amina),
            CustodyDirection.OUT,
            CustodyTransactionState.valueOf(state),
            tenUsdc,
            refId = refId,
            circleTxId = "tx-$refId",
        )
}
