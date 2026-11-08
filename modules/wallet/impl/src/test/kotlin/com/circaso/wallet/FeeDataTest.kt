package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.data.repositories.DatabaseTransactor
import com.circaso.wallet.data.repositories.FeeLedgerRepositoryImpl
import com.circaso.wallet.data.repositories.FeeQuoteRepositoryImpl
import com.circaso.wallet.data.repositories.RuleRepositoryImpl
import com.circaso.wallet.data.repositories.TransactionRepositoryImpl
import com.circaso.wallet.data.repositories.TransferRepositoryImpl
import com.circaso.wallet.data.repositories.WalletOutboxImpl
import com.circaso.wallet.data.repositories.WalletRepositoryImpl
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.FeeKind
import com.circaso.wallet.domain.models.FeeState
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.support.RuleVersions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Fees and limits on PostgreSQL (wallet's own Flyway scripts, V4): the seeded rules as the domain reads them, the
 * bounds on a rate, a payment's fee locked on its transfer and its ledger row, and the cap held under a lock when two
 * payments race for what is left.
 */
class FeeDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val rules = RuleRepositoryImpl(database)
    private val ledger = FeeLedgerRepositoryImpl(database)
    private val transfers = TransferRepositoryImpl(database)

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("wallet")
            // The seeds of V4, which the truncate took away, straight from the script.
            seeds().forEach(::sql)
            // The container's clock may run ahead of this JVM's: start the seeds a little earlier.
            sql("UPDATE wallet.fee_rules SET effective_from = now() - interval '1 hour'")
            sql("UPDATE wallet.limit_rules SET effective_from = now() - interval '1 hour'")
        }

    private fun sql(statement: String) {
        TestPostgres.superuser { connection -> connection.createStatement().use { it.execute(statement) } }
    }

    private fun harness() =
        Harness(
            WalletRepositoryImpl(database),
            DatabaseTransactor(database),
            WalletOutboxImpl(database),
            transfers,
            TransactionRepositoryImpl(database),
            ruleRows = rules,
            quoteRows = FeeQuoteRepositoryImpl(database),
            ledger = ledger,
        ).also { it.clock.now = Instant.now() }

    @Test
    fun `the seeded rules read back in force, with their rates and caps in dollars`() =
        runBlocking<Unit> {
            val fees = RuleVersions.currentFees(rules.feeRules(), Instant.now())
            assertEquals(8, fees.size)
            val external = fees.single { it.kind == FeeKind.EXTERNAL }
            assertEquals(200, external.rateBps)
            assertEquals(10_000_000_000L, external.capBaseMinor, "10 000 dollars")
            assertEquals(2_500, external.gasShareMaxBps)
            assertEquals(0, fees.single { it.blockchain == "SOL-DEVNET" }.rateBps)

            val limits = RuleVersions.currentLimits(rules.limitRules(), Instant.now())
            assertEquals(10_000_000_000L, limits.single { it.ownerType == WalletOwnerType.GROUP }.dailyMaxMinor)
            assertEquals(500_000_000L, limits.single { it.ownerType == WalletOwnerType.PERSON }.perPaymentMaxMinor)
            assertEquals(null, limits.single { it.ownerType == WalletOwnerType.SPACE }.dailyMaxMinor)
        }

    @Test
    fun `a rate outside the bounds is refused by the database`() {
        assertFailsWith<SQLException> {
            sql(
                "INSERT INTO wallet.fee_rules (rule_id, version, kind, rate_bps, effective_from, created_at) " +
                    "VALUES ('0195f2f0-0000-7000-8000-0000000000ff', 1, 'EXTERNAL', 300, now(), now())",
            )
        }
    }

    @Test
    fun `a payment's fee is locked on its transfer and written to the ledger, which follows it`() =
        runBlocking<Unit> {
            val h = harness()
            val (amina, bob) = UserId.new() to UserId.new()
            h.fund(amina, 300_000_000)
            h.walletOn(bob)
            h.reach.connect(amina, bob)

            val quote = h.quote(amina, Payee.Person(bob), 100_000_000).data()
            val prepared =
                h
                    .prepareTransfer(
                        com.circaso.wallet.domain.models.TransferCommand(
                            amina,
                            "BASE-SEPOLIA",
                            Payee.Person(bob),
                            com.circaso.shared.Money(100_000_000, com.circaso.shared.Asset.USDC),
                            null,
                            null,
                            java.util.UUID.randomUUID(),
                            quoteId = quote.quoteId,
                        ),
                    ).data()
                    .transfer

            assertEquals(500_000.toBigInteger(), transfers.find(prepared.transferId)?.feeMinor)
            assertEquals(FakeFeeConfig.FEE_WALLET, transfers.find(prepared.transferId)?.feeAddress)
            assertEquals(100_500_000L, h.used(amina), "the window counts the fee")
            val id = prepared.transferId
            assertNotNull(ledger.settle(id, FeeState.COLLECTED, "0xhash", "0.000002", 5_000, Instant.now()))
            assertNull(ledger.settle(id, FeeState.VOID, null, null, null, Instant.now()), "collected stays")
        }

    @Test
    fun `two payments racing for the last of a limit - the lock lets only one through`() =
        runBlocking<Unit> {
            val h = harness()
            val (amina, bob) = UserId.new() to UserId.new()
            h.fund(amina, 2_000_000_000)
            h.walletOn(bob)
            h.reach.connect(amina, bob)
            h.pay(amina, Payee.Person(bob), 500_000_000).let { refused ->
                // No quote: the 0.5 % fee answers fee_changed; pay with the quote it carries.
                val fields = (refused as com.circaso.shared.Resource.Error).error.details!!.fields
                val id = fields.single { it.field == "quoteId" }.problem
                h
                    .prepareTransfer(
                        com.circaso.wallet.domain.models.TransferCommand(
                            amina,
                            "BASE-SEPOLIA",
                            Payee.Person(bob),
                            com.circaso.shared.Money(500_000_000, com.circaso.shared.Asset.USDC),
                            null,
                            null,
                            java.util.UUID.randomUUID(),
                            quoteId = java.util.UUID.fromString(id),
                        ),
                    ).data()
            }
            // 502.50 has gone of 1 000; with no fee now, two of 300 can't both fit the 497.50 left.
            sql("DELETE FROM wallet.fee_rules")

            val results =
                coroutineScope {
                    (0 until 2)
                        .map { async(Dispatchers.Default) { h.pay(amina, Payee.Person(bob), 300_000_000) } }
                        .awaitAll()
                }

            assertEquals(1, results.count { it is com.circaso.shared.Resource.Success })
            val refused = results.filterIsInstance<com.circaso.shared.Resource.Error>().map { it.error.code }
            assertEquals(listOf("wallet.limit_exceeded"), refused)
        }

    internal companion object {
        /** The `INSERT`s of the V4 script, so the test reads exactly what the migration seeds. */
        fun seeds(): List<String> =
            checkNotNull(FeeDataTest::class.java.getResource("/db/wallet/V4__fees_limits.sql"))
                .readText()
                .lines()
                // Comments first: one may hold a ";" (the seeds' do).
                .joinToString("\n") { it.substringBefore("--") }
                .split(";")
                .map { it.trim() }
                .filter { it.startsWith("INSERT INTO") }
    }
}
