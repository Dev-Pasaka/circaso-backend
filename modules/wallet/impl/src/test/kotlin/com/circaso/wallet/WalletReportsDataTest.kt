package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.PeriodTotals
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.TransferFilter
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.data.repositories.TransferRepositoryImpl
import com.circaso.wallet.data.repositories.WalletExpiry
import com.circaso.wallet.data.repositories.WalletOwnerRepositoryImpl
import com.circaso.wallet.data.repositories.WalletReportRepositoryImpl
import com.circaso.wallet.data.repositories.WalletRepositoryImpl
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.support.HoldingPrices
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.StaffTransferPages
import com.circaso.wallet.domain.usecases.BuildFinanceReport
import com.circaso.wallet.domain.usecases.CountGroupWallets
import com.circaso.wallet.domain.usecases.FindGroupWallets
import com.circaso.wallet.domain.usecases.GetStaffHoldings
import com.circaso.wallet.domain.usecases.GetStaffTransfer
import com.circaso.wallet.domain.usecases.ListGroupWallets
import com.circaso.wallet.domain.usecases.ListStaffTransfers
import com.circaso.wallet.domain.usecases.ListTransfersOf
import com.circaso.wallet.domain.usecases.SumFeesPaid
import com.circaso.wallet.presentation.api.WalletReportsImpl
import com.circaso.wallet.presentation.services.WalletReportService
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The staff money reads on PostgreSQL (decision 2026-11-08): keyset pages, the merged per-person list, the report. */
class WalletReportsDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val transfers = TransferRepositoryImpl(database)
    private val owners = WalletOwnerRepositoryImpl(database)
    private val repository = WalletReportRepositoryImpl(database)
    private val fakes = Harness()
    private val pages = StaffTransferPages(repository)
    private val reports =
        WalletReportsImpl(
            WalletReportService(
                GetStaffHoldings(
                    WalletRepositoryImpl(database),
                    HoldingsReader(fakes.custody, fakes.cache, fakes.warnings),
                    HoldingPrices(fakes.prices, fakes.custody),
                ),
                ListTransfersOf(pages),
                SumFeesPaid(repository),
                ListStaffTransfers(pages),
                GetStaffTransfer(transfers),
                BuildFinanceReport(repository),
                CountGroupWallets(repository),
                ListGroupWallets(repository),
                FindGroupWallets(owners),
            ),
        )
    private val day: Instant = Instant.parse("2026-10-05T00:00:00Z")
    private val amina = UserId.new()

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("wallet")
            WalletExpiry(database).run()
        }

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private suspend fun transfer(
        createdAt: Instant,
        sender: UserId = UserId.new(),
        recipient: UserId? = UserId.new(),
        state: TransferState = TransferState.CONFIRMED,
        amount: Money = Money(1_000_000, Asset.USDC),
        blockchain: String = "BASE-SEPOLIA",
        usdTotalMicros: Long = amount.amountMinor.toLong(),
    ): Transfer {
        val transfer =
            Transfer(
                transferId = TransferId.new(),
                senderId = sender,
                senderWalletId = "w-${UUID.randomUUID()}",
                blockchain = blockchain,
                idempotencyKey = UUID.randomUUID(),
                recipientId = recipient,
                destinationAddress = "0xabc",
                amount = amount,
                state = state,
                circleTxId = null,
                txHash = null,
                chatId = null,
                origin = TransferOrigin.APP,
                proposalId = null,
                failureReason = null,
                createdAt = createdAt.truncatedTo(ChronoUnit.MICROS),
                updatedAt = createdAt.truncatedTo(ChronoUnit.MICROS),
                usdTotalMinor = usdTotalMicros,
            )
        transfers.insert(transfer)
        return transfer
    }

    /** A fee leg as the ledger keeps it; only what the reports read matters here. */
    private fun fee(
        transfer: Transfer,
        feeUsdMicros: Long,
        state: String = "COLLECTED",
        holder: UserId = transfer.senderId,
    ) {
        TestPostgres.superuser { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO wallet.fee_ledger (transfer_id, holder_id, blockchain, asset, amount_minor, " +
                        "fee_minor, rate_bps, minimum, kind, sender_type, gas_share_max_bps, state, created_at, " +
                        "updated_at, fee_usd_minor) VALUES (?, ?, ?, ?, 1000000, 10000, 100, false, 'PERSON', " +
                        "'PERSON', 2500, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, transfer.transferId.value)
                    statement.setObject(2, holder.value)
                    statement.setString(3, transfer.blockchain)
                    statement.setString(4, transfer.amount.asset.symbol)
                    statement.setString(5, state)
                    statement.setObject(6, Timestamp.from(transfer.createdAt))
                    statement.setObject(7, Timestamp.from(transfer.createdAt))
                    statement.setLong(8, feeUsdMicros)
                    statement.executeUpdate()
                }
        }
    }

    @Test
    fun `the report counts confirmed transfers and collected fees by UTC day, every day of the range`() =
        runBlocking<Unit> {
            val first = transfer(day.plusSeconds(3_600), usdTotalMicros = 1_010_000)
            val late = transfer(day.plusSeconds(86_399), usdTotalMicros = 2_000_000)
            val eth = Asset("ETH", 18)
            val big = Money(BigInteger.TEN.pow(21), eth)
            val ether = transfer(day.plusSeconds(2 * 86_400 + 60), amount = big, usdTotalMicros = 3_500_000_000)
            val failed = transfer(day.plusSeconds(7_200), state = TransferState.FAILED)
            transfer(day.minusSeconds(1))
            transfer(day.plusSeconds(3 * 86_400))
            fee(first, 10_000)
            fee(late, 20_000)
            fee(ether, 35_000_000)
            fee(failed, 99_000, state = "VOID")

            val report = reports.report(day, day.plusSeconds(3 * 86_400), ReportBucket.DAY)

            assertEquals(
                listOf(
                    PeriodTotals(LocalDate.of(2026, 10, 5), 2, 3_010_000, 30_000),
                    PeriodTotals(LocalDate.of(2026, 10, 6), 0, 0, 0),
                    PeriodTotals(LocalDate.of(2026, 10, 7), 1, 3_500_000_000, 35_000_000),
                ),
                report.series,
            )
            assertEquals(3, report.count)
            assertEquals(3_503_010_000, report.volumeUsdMicros)
            assertEquals(35_030_000, report.feesUsdMicros)
            val byCoin = report.byChainAsset.associateBy { it.asset }
            assertEquals(BigInteger.TEN.pow(21), byCoin.getValue("ETH").amountMinor, "past a bigint, exact")
            assertEquals(18, byCoin.getValue("ETH").decimals)
            assertEquals(35_000_000, byCoin.getValue("ETH").feesUsdMicros)
            assertEquals(2, byCoin.getValue("USDC").count)
            assertEquals(BigInteger.valueOf(2_000_000), byCoin.getValue("USDC").amountMinor)
            assertEquals("ETH", report.byChainAsset.first().asset, "the biggest volume first")
        }

    @Test
    fun `weekly and monthly reports put each transfer in its period`() =
        runBlocking<Unit> {
            // 2026-10-05 is a Monday.
            transfer(day.plusSeconds(6 * 86_400))
            transfer(day.plusSeconds(7 * 86_400))
            transfer(Instant.parse("2026-11-02T10:00:00Z"))

            val weeks = reports.report(day, Instant.parse("2026-11-03T00:00:00Z"), ReportBucket.WEEK)
            val months = reports.report(day, Instant.parse("2026-11-03T00:00:00Z"), ReportBucket.MONTH)

            assertEquals(listOf(1L, 1L, 0L, 0L, 1L), weeks.series.map { it.count })
            assertEquals(LocalDate.of(2026, 11, 2), weeks.series.last().period)
            assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)), months.series.map { it.period })
            assertEquals(listOf(2L, 1L), months.series.map { it.count })
        }

    @Test
    fun `a person's transfers, sent and received, page newest first by keyset, a payment to oneself once`() =
        runBlocking<Unit> {
            val mine =
                listOf(
                    transfer(day.plusSeconds(10), sender = amina),
                    transfer(day.plusSeconds(20), recipient = amina),
                    transfer(day.plusSeconds(30), sender = amina, recipient = null),
                    transfer(day.plusSeconds(40), sender = amina, recipient = amina),
                    transfer(day.plusSeconds(50), recipient = amina, state = TransferState.FAILED),
                )
            transfer(day.plusSeconds(35))

            val seen = mutableListOf<List<TransferId>>()
            var cursor: String? = null
            do {
                val page = reports.transfersOf(amina, cursor, 2).data()
                seen += page.items.map { it.transferId }
                cursor = page.nextCursor
            } while (cursor != null)

            assertEquals(mine.reversed().map { it.transferId }, seen.flatten())
            assertEquals(listOf(2, 2, 1), seen.map { it.size })
            assertEquals("request.malformed", reports.transfersOf(amina, "nope", 2).errorCode())
        }

    @Test
    fun `every transfer filters by state, chain, coin, person and time, newest first`() =
        runBlocking<Unit> {
            val confirmed = transfer(day.plusSeconds(10))
            val solana = transfer(day.plusSeconds(20), blockchain = "SOL-DEVNET")
            transfer(day.plusSeconds(30), state = TransferState.FAILED)
            val aminas = transfer(day.plusSeconds(40), sender = amina)

            assertEquals(
                listOf(aminas, solana, confirmed).map { it.transferId },
                reports
                    .transfers(TransferFilter(state = "confirmed"), null, 10)
                    .data()
                    .items
                    .map { it.transferId },
            )
            assertEquals(
                listOf(solana.transferId),
                reports
                    .transfers(
                        TransferFilter(blockchain = "SOL-DEVNET"),
                        null,
                        10,
                    ).data()
                    .items
                    .map { it.transferId },
            )
            assertEquals(
                listOf(aminas.transferId),
                reports
                    .transfers(TransferFilter(userId = amina), null, 10)
                    .data()
                    .items
                    .map { it.transferId },
            )
            assertEquals(
                listOf(solana.transferId),
                reports
                    .transfers(TransferFilter(from = day.plusSeconds(15), to = day.plusSeconds(25)), null, 10)
                    .data()
                    .items
                    .map { it.transferId },
            )
            assertEquals(
                4,
                reports
                    .transfers(TransferFilter(), null, 10)
                    .data()
                    .items.size,
            )
            assertEquals("request.malformed", reports.transfers(TransferFilter(state = "LOST"), null, 10).errorCode())
            assertEquals(confirmed.transferId, reports.transfer(confirmed.transferId)?.transferId)
            assertNull(reports.transfer(TransferId.new()))
        }

    @Test
    fun `the fees a person paid are their collected fee legs only`() =
        runBlocking<Unit> {
            fee(transfer(day, sender = amina), 10_000)
            fee(transfer(day.plusSeconds(1), sender = amina), 25_000)
            fee(transfer(day.plusSeconds(2), sender = amina), 50_000, state = "QUOTED")
            fee(transfer(day.plusSeconds(3)), 70_000)

            assertEquals(35_000, reports.feesPaidBy(amina))
            assertEquals(0, reports.feesPaidBy(UserId.new()))
        }

    @Test
    fun `group wallets page newest first, are counted, and are found by their groups`() =
        runBlocking<Unit> {
            val groups =
                List(3) { i ->
                    WalletOwner
                        .Group(
                            WalletId(UUID.randomUUID()),
                            ChatId(UUID.randomUUID()),
                            UserId.new(),
                            UserId.new(),
                            day.plusSeconds(i.toLong()),
                        ).also { owners.insert(it) }
                }

            val first = reports.groupWallets(null, 2).data()
            val second = reports.groupWallets(first.nextCursor, 2).data()

            assertEquals(groups.reversed().map { it.walletId.value }, (first.items + second.items).map { it.walletId })
            assertNull(second.nextCursor)
            assertEquals(3, reports.groupWalletCount())
            assertEquals(
                setOf(groups[0].chatId),
                reports.groupWalletsOf(setOf(groups[0].chatId, ChatId(UUID.randomUUID()))).keys,
            )
            assertEquals("request.malformed", reports.groupWallets("garbage", 2).errorCode())
        }
}
