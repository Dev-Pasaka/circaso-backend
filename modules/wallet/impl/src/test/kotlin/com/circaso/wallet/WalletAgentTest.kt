package com.circaso.wallet

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.support.Counterparties
import com.circaso.wallet.domain.usecases.GetTransaction
import com.circaso.wallet.domain.usecases.ListTransactions
import com.circaso.wallet.domain.usecases.SummariseTransactions
import com.circaso.wallet.presentation.mappers.AgentHistoryParams
import com.circaso.wallet.presentation.mappers.toAgentResponse
import com.circaso.wallet.presentation.mappers.toHistoryQuery
import com.circaso.wallet.presentation.mappers.toSummaryQuery
import com.circaso.wallet.presentation.mappers.transactionQuery
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asha's wallet agent routes over in-memory fakes (decision 2026-10-31): scoping, filters, totals and caps. */
class WalletAgentTest {
    private val transactions = MemoryTransactionRepository()
    private val profiles = NamedProfiles()
    private val rates = FakeRates().apply { rates["KES"] = BigDecimal("129.50") }
    private val counterparties = Counterparties(profiles)
    private val list = ListTransactions(transactions, counterparties, rates, personalHolders())
    private val summarise = SummariseTransactions(transactions, counterparties, rates)
    private val one = GetTransaction(transactions, counterparties, rates)
    private val amina = UserId.new()
    private val bob = UserId.new()
    private val cara = UserId.new()

    private fun row(
        at: String,
        userId: UserId = amina,
        direction: Direction = Direction.OUT,
        state: TransferState = TransferState.CONFIRMED,
        counterpartyId: UserId? = bob,
        address: String? = "0xb0b",
        minor: Long = 20_000_000,
        asset: Asset = Asset.USDC,
        blockchain: String = "BASE-SEPOLIA",
    ) = WalletTransaction(
        id = UUID.randomUUID(),
        userId = userId,
        direction = direction,
        state = state,
        amount = Money(minor, asset),
        blockchain = blockchain,
        counterpartyId = counterpartyId,
        counterpartyAddress = address,
        transferId = if (direction == Direction.OUT) TransferId.new() else null,
        circleTxId = null,
        txHash = null,
        chatId = ChatId.new(),
        origin = TransferOrigin.APP,
        occurredAt = Instant.parse(at),
    )

    private suspend fun add(vararg rows: WalletTransaction) = rows.forEach { transactions.insert(it) }

    private suspend fun listed(params: AgentHistoryParams) =
        list(params.toHistoryQuery(amina).data()).data().entries.map { it.transaction.id }

    @Test
    fun `the list holds only the caller's rows, and every filter narrows it`() =
        runBlocking<Unit> {
            val toBob = row("2026-10-02T10:00:00Z")
            val fromCara = row("2026-10-05T10:00:00Z", direction = Direction.IN, counterpartyId = cara, address = null)
            val usdt = row("2026-09-20T10:00:00Z", asset = Asset.USDT)
            val solana = row("2026-09-10T10:00:00Z", blockchain = "SOL-DEVNET")
            val outside = row("2026-08-01T10:00:00Z", counterpartyId = null, address = OUTSIDE)
            val bobs = row("2026-10-03T10:00:00Z", userId = bob, counterpartyId = amina)
            add(toBob, fromCara, usdt, solana, outside, bobs)

            assertEquals(listOf(fromCara.id, toBob.id, usdt.id, solana.id, outside.id), listed(AgentHistoryParams()))
            assertEquals(listOf(fromCara.id), listed(AgentHistoryParams(direction = "in")))
            assertEquals(listOf(fromCara.id), listed(AgentHistoryParams(counterparty = cara.toString())))
            assertEquals(
                listOf(outside.id),
                listed(AgentHistoryParams(counterparty = OUTSIDE.uppercase().replace("0X", "0x"))),
            )
            assertEquals(listOf(usdt.id), listed(AgentHistoryParams(asset = "usdt")))
            assertEquals(listOf(solana.id), listed(AgentHistoryParams(blockchain = "sol-devnet")))
            assertEquals(listOf(fromCara.id, toBob.id), listed(AgentHistoryParams(month = "2026-10")))
            assertEquals(
                listOf(fromCara.id, toBob.id, usdt.id),
                listed(AgentHistoryParams(from = "2026-09-15", to = "2026-10-05")),
                "both dates are inclusive",
            )
            assertEquals(listOf(toBob.id), listed(AgentHistoryParams(month = "2026-10", to = "2026-10-04")))
            assertTrue(listed(AgentHistoryParams(from = "2026-11-01", to = "2026-10-01")).isEmpty())
        }

    @Test
    fun `a page is capped at 50, defaults to 20, and malformed arguments name their field`() =
        runBlocking<Unit> {
            assertEquals(20, AgentHistoryParams().toHistoryQuery(amina).data().limit)
            assertEquals(50, AgentHistoryParams(limit = "500").toHistoryQuery(amina).data().limit)
            assertMalformed(AgentHistoryParams(limit = "0").toHistoryQuery(amina), "limit")
            assertMalformed(AgentHistoryParams(from = "last week").toHistoryQuery(amina), "from")
            assertMalformed(AgentHistoryParams(month = "2026-13").toHistoryQuery(amina), "month")
            assertMalformed(AgentHistoryParams(direction = "sideways").toHistoryQuery(amina), "direction")
            assertMalformed(AgentHistoryParams(asset = "not a coin").toHistoryQuery(amina), "asset")
            assertEquals(
                "ETH",
                AgentHistoryParams(asset = "eth")
                    .toHistoryQuery(amina)
                    .data()
                    .filter.asset,
            )
            assertMalformed(AgentHistoryParams(currency = "XXXX").toHistoryQuery(amina), "currency")
            assertMalformed(AgentHistoryParams(top = "-1").toSummaryQuery(amina), "top")
            assertEquals(20, AgentHistoryParams(top = "99").toSummaryQuery(amina).data().topCounterparties)

            add(*(1..60).map { row("2026-10-01T10:00:00Z") }.toTypedArray())
            val page = list(AgentHistoryParams(limit = "100").toHistoryQuery(amina).data()).data()
            assertEquals(50, page.entries.size)
            assertTrue(page.next != null)
        }

    @Test
    fun `amounts are exact in whole units and fiat is at today's rate in the currency asked for`() =
        runBlocking<Unit> {
            add(row("2026-10-02T10:00:00Z", minor = 1_500_000))
            profiles.known[bob] = PublicProfile(bob, "Bob Otieno", "bob", null, null)

            val answer =
                list(AgentHistoryParams(currency = "kes").toHistoryQuery(amina).data()).data().toAgentResponse()

            val payment = answer.transactions.single()
            assertEquals("1.5", payment.amount)
            assertEquals("USDC", payment.asset)
            assertEquals("194.25", payment.fiat)
            assertEquals("Bob Otieno", payment.counterparty.displayName)
            assertEquals("KES", answer.rate!!.currency)
        }

    @Test
    fun `the summary adds up money that moved, by asset, person and month`() =
        runBlocking<Unit> {
            profiles.known[bob] = PublicProfile(bob, "Bob", null, null, null)
            profiles.known[cara] = PublicProfile(cara, "Cara", null, null, null)
            add(
                row("2026-09-02T10:00:00Z", minor = 10_000_000),
                row("2026-10-02T10:00:00Z", minor = 5_000_000),
                row("2026-10-03T10:00:00Z", direction = Direction.IN, counterpartyId = cara, minor = 50_000_000),
                row("2026-10-04T10:00:00Z", state = TransferState.FAILED, minor = 99_000_000),
                row("2026-10-05T10:00:00Z", userId = bob, counterpartyId = amina, minor = 77_000_000),
            )

            val summary =
                summarise(AgentHistoryParams(currency = "KES").toSummaryQuery(amina).data()).data().toAgentResponse()

            assertEquals(3, summary.count, "a failed payment and someone else's rows don't count")
            val usdc = summary.totals.single()
            assertEquals("50", usdc.totalIn)
            assertEquals("15", usdc.totalOut)
            assertEquals("35", usdc.net)
            assertEquals(1, usdc.countIn)
            assertEquals(2, usdc.countOut)
            assertEquals("6475.00", usdc.fiatIn)
            assertEquals(listOf("Cara", "Bob"), summary.topCounterparties.map { it.counterparty.displayName })
            assertEquals(listOf("2026-09", "2026-10"), summary.byMonth.map { it.month })
            assertEquals("5", summary.byMonth.last().totalOut)
            assertEquals(false, summary.truncated)
        }

    @Test
    fun `a summary reads at most 5000 payments and says when there were more`() =
        runBlocking<Unit> {
            val start = Instant.parse("2026-01-01T00:00:00Z")
            add(*(0 until 5_001).map { row(start.plusSeconds(it * 60L).toString(), minor = 1) }.toTypedArray())

            val summary = summarise(AgentHistoryParams().toSummaryQuery(amina).data()).data()

            assertEquals(5_000, summary.count)
            assertTrue(summary.truncated)
        }

    @Test
    fun `one payment is found only among the caller's own rows`() =
        runBlocking<Unit> {
            val mine = row("2026-10-02T10:00:00Z")
            val theirs = row("2026-10-02T10:00:00Z", userId = bob, counterpartyId = amina)
            val abandoned = row("2026-10-02T10:00:00Z", state = TransferState.AWAITING_SIGNATURE)
            add(mine, theirs, abandoned)

            val found = one(transactionQuery(amina, mine.id.toString(), null).data()).data()
            assertEquals(mine.id, found.entry.transaction.id)
            assertNull(found.rate)
            assertEquals(
                "wallet.transfer_not_found",
                one(transactionQuery(amina, theirs.id.toString(), null).data()).code(),
            )
            assertEquals(
                "wallet.transfer_not_found",
                one(transactionQuery(amina, abandoned.id.toString(), null).data()).code(),
            )
            assertMalformed(transactionQuery(amina, "nope", null), "transactionId")
        }

    private fun assertMalformed(result: Resource<*>, field: String) {
        val error = assertIs<Resource.Error>(result)
        assertEquals("request.malformed", error.error.code)
        assertEquals(
            field,
            error.error.details
                ?.fields
                ?.firstOrNull()
                ?.field,
        )
    }
}

private val OUTSIDE = "0x" + "ab".repeat(20)

private class NamedProfiles : Profiles {
    val known = mutableMapOf<UserId, PublicProfile>()

    override suspend fun get(ids: Set<UserId>): Map<UserId, PublicProfile> =
        ids.mapNotNull { id -> known[id]?.let { id to it } }.toMap()
}
