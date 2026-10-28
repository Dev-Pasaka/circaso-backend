package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.UserId
import com.circaso.wallet.data.repositories.DatabaseTransactor
import com.circaso.wallet.data.repositories.WalletExpiry
import com.circaso.wallet.data.repositories.WalletOutboxImpl
import com.circaso.wallet.data.repositories.WalletRepositoryImpl
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
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
 * Wallet's data layer on PostgreSQL (infrastructure's test container, migrated by wallet's own Flyway scripts): the
 * claims the keys settle, and the wallet announced once however many requests see Circle's new wallet at once.
 */
class WalletDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val repository = WalletRepositoryImpl(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("wallet")
            WalletExpiry(database).run()
        }

    private fun chainWallet(
        userId: UserId,
        id: String = "w-${UUID.randomUUID()}",
        blockchain: String = "BASE-SEPOLIA",
        address: String = "0x${UUID.randomUUID().toString().replace("-", "")}",
    ) = ChainWallet(id, userId, blockchain, "SCA", address, ChainWalletState.ACTIVE, now)

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

    @Test
    fun `a Circle user is claimed once per account and read back as written`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            assertNull(repository.circleUser(amina))
            val first = CircleUser(amina, UUID.randomUUID(), now)

            assertTrue(repository.insertCircleUser(first))
            assertFalse(repository.insertCircleUser(CircleUser(amina, UUID.randomUUID(), now)))

            assertEquals(first, repository.circleUser(amina))
        }

    @Test
    fun `two taps on Create wallet at once share one Circle user`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) {
                val amina = UserId.new()
                val h = Harness(repository, DatabaseTransactor(database), WalletOutboxImpl(database))

                concurrently(2) { h.create(amina) }

                assertEquals(1, count("SELECT count(*) FROM wallet.circle_users WHERE user_id = ?", amina.value))
            }
        }

    @Test
    fun `one wallet per account and chain, one owner per address on a chain, oldest first`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val base = chainWallet(amina)
            val solana = chainWallet(amina, blockchain = "SOL-DEVNET", address = "So1AbC")

            assertTrue(repository.insertWallet(base))
            assertTrue(repository.insertWallet(solana))
            assertFalse(repository.insertWallet(base), "the same Circle wallet")
            assertFalse(repository.insertWallet(chainWallet(amina)), "a second wallet on the same chain")
            assertFalse(
                repository.insertWallet(chainWallet(UserId.new(), address = base.address)),
                "somebody else at the same address",
            )
            assertTrue(repository.insertWallet(chainWallet(UserId.new(), blockchain = "SOL-DEVNET", address = "x")))

            assertEquals(listOf(base, solana), repository.wallets(amina))
        }

    @Test
    fun `a new wallet seen by many reads at once is recorded and announced exactly once, with its outbox row`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) {
                val amina = UserId.new()
                val h = Harness(repository, DatabaseTransactor(database), WalletOutboxImpl(database))
                h.create(amina).data()
                h.custody.sign(h.circleUserOf(amina))

                concurrently(4) { h.wallet(amina) }

                assertEquals(1, h.bus.published.size)
                assertEquals(1, count("SELECT count(*) FROM wallet.wallets WHERE user_id = ?", amina.value))
                assertEquals(
                    1,
                    count(
                        "SELECT count(*) FROM wallet.outbox WHERE type = 'wallet.WalletCreated' AND payload LIKE ?",
                        "%${amina.value}%",
                    ),
                )
            }
        }

    @Test
    fun `the purge keeps the outbox partitions a day ahead`() =
        runBlocking<Unit> {
            WalletExpiry(database).run()
            assertTrue(
                count("SELECT count(*) FROM pg_tables WHERE schemaname = 'wallet' AND tablename LIKE 'outbox_p%'") >= 2,
            )
        }

    private companion object {
        const val RACE_RUNS = 20

        suspend fun <T> concurrently(times: Int, block: suspend (Int) -> T): List<T> =
            coroutineScope { (0 until times).map { async(Dispatchers.Default) { block(it) } }.awaitAll() }
    }
}
