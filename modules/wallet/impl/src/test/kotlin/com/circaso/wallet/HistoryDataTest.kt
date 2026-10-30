package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.data.repositories.TransactionRepositoryImpl
import com.circaso.wallet.data.repositories.WalletExpiry
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.HISTORY_STATES
import com.circaso.wallet.domain.models.HistoryPosition
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.usecases.ListTransactions
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * W6 on PostgreSQL (infrastructure's test container, migrated by wallet's own Flyway scripts): the keyset query over
 * `transactions_user_idx`, its month bounds, ties on `occurred_at`, and that a row read back is the row written.
 */
class HistoryDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val transactions = TransactionRepositoryImpl(database)
    private val amina = UserId.new()
    private val bob = UserId.new()

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("wallet")
            WalletExpiry(database).run()
        }

    private fun row(
        at: String,
        userId: UserId = amina,
        state: TransferState = TransferState.CONFIRMED,
        direction: Direction = Direction.OUT,
    ) = WalletTransaction(
        id = UUID.randomUUID(),
        userId = userId,
        direction = direction,
        state = state,
        amount = Money(20_000_000, Asset.USDC),
        blockchain = "BASE-SEPOLIA",
        counterpartyId = bob,
        counterpartyAddress = "0xb0b",
        transferId = if (direction == Direction.OUT) TransferId.new() else null,
        circleTxId = "circle-${UUID.randomUUID()}",
        txHash = "0xh",
        chatId = null,
        origin = TransferOrigin.APP,
        occurredAt = Instant.parse(at),
    )

    @Test
    fun `history pages a user's signed rows newest first, ties by id, within a month or across months`() =
        runBlocking<Unit> {
            val tied = (1..3).map { row("2026-10-20T10:00:00.000001Z") }
            val septemberLast = row("2026-09-30T23:59:59.999999Z")
            val octoberFirst = row("2026-10-01T00:00:00Z")
            val november = row("2026-11-01T00:00:00Z", direction = Direction.IN)
            val mine = tied + septemberLast + octoberFirst + november
            val noise =
                listOf(
                    row("2026-10-21T10:00:00Z", state = TransferState.AWAITING_SIGNATURE),
                    row("2026-10-22T10:00:00Z", state = TransferState.EXPIRED),
                    row("2026-10-23T10:00:00Z", userId = bob),
                )
            (mine + noise).forEach { transactions.insert(it) }

            val all = walk(month = null)
            assertEquals(mine.size, all.size, "every signed row of the caller's, once")
            assertEquals(mine.toSet(), all.toSet(), "read back as written")
            assertEquals(all.sortedByDescending { it.occurredAt }.map { it.occurredAt }, all.map { it.occurredAt })
            assertEquals(november.id, all.first().id)
            assertEquals(septemberLast.id, all.last().id)

            val october = walk(YearMonth.of(2026, 10))
            assertEquals((tied + octoberFirst).toSet(), october.toSet())
            assertEquals(octoberFirst.id, october.last().id)

            val firstPage = transactions.history(amina, HISTORY_STATES, null, null, null, 2)
            val pastTies = HistoryPosition(firstPage.last().occurredAt, firstPage.last().id)
            assertEquals(
                all.drop(2).map { it.id },
                transactions.history(amina, HISTORY_STATES, null, null, pastTies, 50).map { it.id },
            )
        }

    @Test
    fun `the use case over the real table ends on the last page with no cursor`() =
        runBlocking<Unit> {
            transactions.insert(row("2026-10-20T10:00:00Z"))
            val list = ListTransactions(transactions, FakeProfiles().apply { names[bob] = "Bob" })

            val page = list(HistoryQuery(amina, null, null, 50)).data()

            assertEquals(
                "Bob",
                page.entries
                    .single()
                    .counterparty.displayName,
            )
            assertNull(page.next)
        }

    /** Every row the query reaches, two at a time. */
    private suspend fun walk(month: YearMonth?): List<WalletTransaction> {
        val from = month?.atDay(1)?.atStartOfDay()?.toInstant(ZoneOffset.UTC)
        val until =
            month
                ?.plusMonths(1)
                ?.atDay(1)
                ?.atStartOfDay()
                ?.toInstant(ZoneOffset.UTC)
        val seen = mutableListOf<WalletTransaction>()
        var after: HistoryPosition? = null
        while (true) {
            val page = transactions.history(amina, HISTORY_STATES, from, until, after, 2)
            seen += page
            if (page.size < 2) return seen
            after = page.last().let { HistoryPosition(it.occurredAt, it.id) }
        }
    }
}
