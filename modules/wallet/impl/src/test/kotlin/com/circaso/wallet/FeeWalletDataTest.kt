package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Network
import com.circaso.shared.Uuid7
import com.circaso.wallet.data.repositories.FeeDestinationRepositoryImpl
import com.circaso.wallet.data.repositories.FeeWalletRepositoryImpl
import com.circaso.wallet.data.repositories.FeeWithdrawalRepositoryImpl
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.FeeDestination
import com.circaso.wallet.domain.models.FeeWallet
import com.circaso.wallet.domain.models.FeeWalletNetwork
import com.circaso.wallet.domain.models.FeeWalletOrigin
import com.circaso.wallet.domain.models.FeeWalletStatus
import com.circaso.wallet.domain.models.FeeWithdrawal
import com.circaso.wallet.domain.models.WithdrawalChange
import com.circaso.wallet.domain.models.WithdrawalState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fee wallets on PostgreSQL (V13): a fee address already charged to is never replaced, a wallet row is written
 * once however many servers try, and a withdrawal moves only from the state it is in.
 */
class FeeWalletDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val wallets = FeeWalletRepositoryImpl(database)
    private val destinations = FeeDestinationRepositoryImpl(database)
    private val withdrawals = FeeWithdrawalRepositoryImpl(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val staff = UUID.randomUUID()
    private val address = "0x00000000000000000000000000000000000000aa"
    private val other = "0x00000000000000000000000000000000000000bb"

    @BeforeTest
    fun clean() = TestPostgres.truncate("wallet")

    private fun sql(statement: String) =
        TestPostgres.superuser { connection -> connection.createStatement().use { it.execute(statement) } }

    private fun chainFee(blockchain: String, address: String?) =
        sql(
            "INSERT INTO wallet.chain_fees (blockchain, fee_address, updated_at) " +
                "VALUES ('$blockchain', ${address?.let { "'$it'" } ?: "NULL"}, now())",
        )

    private fun feeAddress(blockchain: String): String? =
        TestPostgres.superuser { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT fee_address FROM wallet.chain_fees WHERE blockchain = '$blockchain'",
                    ).use {
                        if (it.next()) it.getString(1) else null
                    }
            }
        }

    private fun wallet(
        blockchain: String,
        at: String,
        walletId: String?,
        origin: FeeWalletOrigin = FeeWalletOrigin.CREATED,
    ) = FeeWallet(blockchain, at, walletId, walletId?.let { "set-1" }, origin, now)

    @Test
    fun `a new fee wallet sets the chain's fee address, and the network row follows the chain's name`() =
        runBlocking<Unit> {
            val recorded = wallets.record(wallet("BASE", address, "w-1"))

            assertEquals(wallet("BASE", address, "w-1"), recorded)
            assertEquals(address, feeAddress("BASE"))
            assertEquals(mapOf("BASE" to address), wallets.feeAddresses())
            assertEquals(listOf("BASE"), wallets.wallets(Network.MAINNET).map { it.blockchain })
            assertTrue(wallets.wallets(Network.TESTNET).isEmpty())
        }

    @Test
    fun `a fee address already there is never replaced, and an empty one is filled`() =
        runBlocking<Unit> {
            chainFee("BASE-SEPOLIA", other)
            chainFee("MATIC-AMOY", null)
            chainFee("ETH-SEPOLIA", "")

            wallets.record(wallet("BASE-SEPOLIA", address, "w-1"))
            wallets.record(wallet("MATIC-AMOY", address, "w-2"))
            wallets.record(wallet("ETH-SEPOLIA", address, "w-3"))

            assertEquals(other, feeAddress("BASE-SEPOLIA"), "what fees are charged to didn't move")
            assertEquals(address, feeAddress("MATIC-AMOY"))
            assertEquals(address, feeAddress("ETH-SEPOLIA"))
        }

    @Test
    fun `a chain's row is written once - a second wallet for it changes nothing`() =
        runBlocking<Unit> {
            val results =
                List(8) { n -> async { wallets.record(wallet("BASE", address, "w-$n")) } }.awaitAll()

            val row = wallets.wallets(Network.MAINNET).single()
            assertTrue(results.all { it == row }, "everyone is told the row that won")
            assertEquals(row, wallets.record(wallet("BASE", other, "w-late")), "another address is not taken either")
            assertEquals(address, feeAddress("BASE"))
        }

    @Test
    fun `an address-only row takes its Circle wallet when it is found, at the same address only`() =
        runBlocking<Unit> {
            wallets.record(wallet("BASE-SEPOLIA", address, null, FeeWalletOrigin.ADDRESS_ONLY))

            val wrong = wallets.record(wallet("BASE-SEPOLIA", other, "w-x", FeeWalletOrigin.IMPORTED))
            val found = wallets.record(wallet("BASE-SEPOLIA", address, "w-1", FeeWalletOrigin.IMPORTED))
            val again = wallets.record(wallet("BASE-SEPOLIA", address, "w-2", FeeWalletOrigin.IMPORTED))

            assertNull(wrong.circleWalletId)
            assertEquals(FeeWalletOrigin.IMPORTED to "w-1", found.origin to found.circleWalletId)
            assertEquals("w-1", again.circleWalletId, "a wallet id once set stays")
        }

    @Test
    fun `a network's row keeps its wallet set through a status without one`() =
        runBlocking<Unit> {
            assertNull(wallets.network(Network.MAINNET))
            wallets.saveNetwork(
                FeeWalletNetwork(Network.MAINNET, "set-1", FeeWalletStatus.NOT_CREATED, "Creating.", now),
            )
            wallets.saveNetwork(FeeWalletNetwork(Network.MAINNET, null, FeeWalletStatus.FAILED, "Circle refused.", now))

            val row = wallets.network(Network.MAINNET)

            assertEquals(
                Triple("set-1", FeeWalletStatus.FAILED, "Circle refused."),
                Triple(row?.walletSetId, row?.status, row?.reason),
            )
            wallets.saveNetwork(FeeWalletNetwork(Network.MAINNET, null, FeeWalletStatus.READY, null, now))
            assertNull(wallets.network(Network.MAINNET)?.reason)
        }

    @Test
    fun `the destination delay is the table's, 24 hours as migrated and when the row is missing`() =
        runBlocking<Unit> {
            assertEquals(24, wallets.destinationDelayHours(), "the tests' truncate removed the row")
            sql("INSERT INTO wallet.fee_wallet_settings (id) VALUES (1)")
            assertEquals(24, wallets.destinationDelayHours(), "the column's default")
            sql("UPDATE wallet.fee_wallet_settings SET destination_delay_hours = 48")
            assertEquals(48, wallets.destinationDelayHours())
            assertFails { sql("UPDATE wallet.fee_wallet_settings SET destination_delay_hours = -1") }
            assertFails { sql("INSERT INTO wallet.fee_wallet_settings (id) VALUES (2)") }
        }

    private fun destination(
        blockchain: String = "BASE",
        at: String = address,
        createdAt: Instant = now,
    ) = FeeDestination(Uuid7.generate(createdAt), blockchain, at, "Treasury", staff, "admin@circaso.com", createdAt)

    @Test
    fun `an address is a destination once per chain while it is in use, and again after it was removed`() =
        runBlocking<Unit> {
            val first = destination()
            val onTestnet = destination("BASE-SEPOLIA", createdAt = now.plusSeconds(1))

            assertTrue(destinations.add(first))
            assertFalse(destinations.add(destination()), "the same chain and address")
            assertTrue(destinations.add(onTestnet))
            assertEquals(listOf(onTestnet.id, first.id), destinations.live(null).map { it.id }, "newest first")
            assertEquals(listOf(first), destinations.live(Network.MAINNET))

            assertTrue(destinations.remove(first.id, staff, now.plusSeconds(5)))
            assertFalse(destinations.remove(first.id, staff, now.plusSeconds(6)))
            assertTrue(destinations.live(Network.MAINNET).isEmpty())
            assertEquals(now.plusSeconds(5), destinations.find(first.id)?.removedAt, "kept for the withdrawals' sake")
            assertTrue(destinations.add(destination(createdAt = now.plusSeconds(10))), "added again, a new row")
        }

    private fun withdrawal(
        destination: FeeDestination,
        key: String = UUID.randomUUID().toString(),
        amount: String = "115792089237316195423570985008687907853269984665640564039457584007913129639935",
        at: Instant = now,
    ) = FeeWithdrawal(
        id = Uuid7.generate(at),
        blockchain = destination.blockchain,
        asset = "USDC",
        tokenAddress = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
        decimals = 6,
        amountMinor = BigInteger(amount),
        destinationId = destination.id,
        destinationAddress = destination.address,
        destinationLabel = destination.label,
        circleWalletId = "w-1",
        tokenId = "token-usdc",
        circleKey = UUID.randomUUID(),
        state = WithdrawalState.PENDING,
        circleTxId = null,
        txHash = null,
        failureReason = null,
        reason = "Monthly sweep",
        requestedBy = staff,
        requestedByEmail = "admin@circaso.com",
        idempotencyKey = key,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `a withdrawal is stored to the last minor unit and once per key`() =
        runBlocking<Unit> {
            val to = destination().also { destinations.add(it) }
            val row = withdrawal(to, key = "key-1")

            assertTrue(withdrawals.insert(row))
            assertFalse(withdrawals.insert(withdrawal(to, key = "key-1", amount = "5")), "the same person's key")

            assertEquals(row, withdrawals.find(row.id), "a full uint256 comes back exactly")
            assertEquals(row, withdrawals.byIdempotencyKey(staff, "key-1"))
            assertNull(withdrawals.byIdempotencyKey(UUID.randomUUID(), "key-1"))
            assertFails { sql("UPDATE wallet.fee_withdrawals SET amount_minor = 0") }
        }

    @Test
    fun `a withdrawal moves only from the state it is in, and can't look sent without Circle's transaction`() =
        runBlocking<Unit> {
            val to = destination().also { destinations.add(it) }
            val row = withdrawal(to).also { withdrawals.insert(it) }
            val later = now.plusSeconds(30)
            val sent = WithdrawalChange(circleTxId = "tx-1")

            assertFails("the table refuses it") {
                withdrawals.move(row.id, WithdrawalState.PENDING, WithdrawalState.SENT, WithdrawalChange(), later)
            }
            assertEquals(WithdrawalState.PENDING, withdrawals.find(row.id)?.state)

            val moves =
                List(6) {
                    async { withdrawals.move(row.id, WithdrawalState.PENDING, WithdrawalState.SENT, sent, later) }
                }.awaitAll()
            assertEquals(1, moves.count { it }, "one of several at once moves it")
            assertFalse(
                withdrawals.move(
                    row.id,
                    WithdrawalState.PENDING,
                    WithdrawalState.FAILED,
                    WithdrawalChange(failureReason = "late"),
                    later,
                ),
            )

            withdrawals.noteHash(row.id, "0xabc", later)
            assertTrue(
                withdrawals.move(row.id, WithdrawalState.SENT, WithdrawalState.CONFIRMED, WithdrawalChange(), later),
            )
            withdrawals.noteHash(row.id, "0xother", later)

            val done = withdrawals.find(row.id)
            assertEquals(
                listOf(WithdrawalState.CONFIRMED, "tx-1", "0xabc", null, later),
                listOf(done?.state, done?.circleTxId, done?.txHash, done?.failureReason, done?.updatedAt),
            )
        }

    @Test
    fun `the lists - open ones oldest first, pages newest first by network, what a chain has promised`() =
        runBlocking<Unit> {
            val main = destination().also { destinations.add(it) }
            val test = destination("BASE-SEPOLIA").also { destinations.add(it) }
            val rows =
                listOf(
                    withdrawal(main, amount = "1", at = now),
                    withdrawal(main, amount = "2", at = now.plusSeconds(1)),
                    withdrawal(test, amount = "3", at = now.plusSeconds(2)),
                    withdrawal(main, amount = "4", at = now.plusSeconds(3)),
                ).onEach { withdrawals.insert(it) }
            val ids = rows.map { it.id }
            withdrawals.move(
                ids[1],
                WithdrawalState.PENDING,
                WithdrawalState.FAILED,
                WithdrawalChange(failureReason = "no"),
                now,
            )
            withdrawals.move(
                ids[3],
                WithdrawalState.PENDING,
                WithdrawalState.SENT,
                WithdrawalChange(circleTxId = "tx-4"),
                now,
            )

            assertEquals(listOf(ids[0], ids[2], ids[3]), withdrawals.open(10).map { it.id })
            assertEquals(listOf(ids[0]), withdrawals.open(1).map { it.id })
            assertEquals(setOf(ids[0], ids[3]), withdrawals.openOn("BASE").map { it.id }.toSet())
            assertEquals(listOf(ids[3], ids[1]), withdrawals.page(Network.MAINNET, null, 2).map { it.id })
            assertEquals(listOf(ids[0]), withdrawals.page(Network.MAINNET, ids[1], 2).map { it.id })
            assertEquals(ids.reversed(), withdrawals.page(null, null, 10).map { it.id })
            assertEquals(listOf(ids[2]), withdrawals.page(Network.TESTNET, null, 10).map { it.id })
        }
}
