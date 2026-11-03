package com.circaso.wallet

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.HistoryPage
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.support.Counterparties
import com.circaso.wallet.domain.usecases.ListTransactions
import com.circaso.wallet.presentation.mappers.historyQuery
import com.circaso.wallet.presentation.mappers.toResponse
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W6 over in-memory fakes: whose rows, which states, month bounds, keyset pages and one profile lookup per page. */
class HistoryTest {
    private val transactions = MemoryTransactionRepository()
    private val profiles = CountingProfiles()
    private val list = ListTransactions(transactions, Counterparties(profiles), FakeRates(), personalHolders())
    private val amina = UserId.new()
    private val bob = UserId.new()

    private fun row(
        at: String,
        userId: UserId = amina,
        direction: Direction = Direction.OUT,
        state: TransferState = TransferState.CONFIRMED,
        counterpartyId: UserId? = bob,
        address: String = "0xb0b",
    ) = WalletTransaction(
        id = UUID.randomUUID(),
        userId = userId,
        direction = direction,
        state = state,
        amount = Money(20_000_000, Asset.USDC),
        blockchain = "BASE-SEPOLIA",
        counterpartyId = counterpartyId,
        counterpartyAddress = address,
        transferId = if (direction == Direction.OUT) TransferId.new() else null,
        circleTxId = null,
        txHash = "0xh",
        chatId = ChatId.new(),
        origin = TransferOrigin.ASHA,
        occurredAt = Instant.parse(at),
    )

    private suspend fun add(vararg rows: WalletTransaction) = rows.forEach { transactions.insert(it) }

    private suspend fun page(
        month: YearMonth? = null,
        limit: Int = 50,
        after: HistoryPage? = null,
    ): HistoryPage = list(HistoryQuery(amina, month, after?.next, limit)).data()

    @Test
    fun `only the caller's own rows, newest first, and only payments that were signed`() =
        runBlocking<Unit> {
            val confirmed = row("2026-10-20T10:00:00Z")
            val submitted = row("2026-10-21T10:00:00Z", state = TransferState.SUBMITTED)
            val failed = row("2026-10-19T10:00:00Z", state = TransferState.FAILED)
            val received = row("2026-10-22T10:00:00Z", direction = Direction.IN)
            add(
                confirmed,
                submitted,
                failed,
                received,
                row("2026-10-23T10:00:00Z", state = TransferState.AWAITING_SIGNATURE),
                row("2026-10-24T10:00:00Z", state = TransferState.EXPIRED),
                row("2026-10-25T10:00:00Z", userId = bob, counterpartyId = amina),
            )

            val shown = page().entries.map { it.transaction.id }

            assertEquals(listOf(received.id, submitted.id, confirmed.id, failed.id), shown)
        }

    @Test
    fun `a month is its UTC calendar month, both edges exact`() =
        runBlocking<Unit> {
            val first = row("2026-10-01T00:00:00Z")
            val last = row("2026-10-31T23:59:59.999999Z")
            add(first, last, row("2026-09-30T23:59:59.999999Z"), row("2026-11-01T00:00:00Z"))

            val october = page(YearMonth.of(2026, 10))

            assertEquals(listOf(last.id, first.id), october.entries.map { it.transaction.id })
            assertNull(october.next)
        }

    @Test
    fun `without a month the pages run back across months to the first payment`() =
        runBlocking<Unit> {
            val rows = (1..5).map { row("2026-${"%02d".format(6 + it)}-15T12:00:00Z") }
            add(*rows.toTypedArray())

            val first = page(limit = 2)
            val second = page(limit = 2, after = first)
            val third = page(limit = 2, after = second)

            val expected = rows.reversed().map { it.id }
            assertEquals(expected, (first.entries + second.entries + third.entries).map { it.transaction.id })
            assertNull(third.next, "the last page has no cursor")
        }

    @Test
    fun `rows at the same instant are paged by id without a gap or a repeat`() =
        runBlocking<Unit> {
            val rows = (1..5).map { row("2026-10-20T10:00:00Z") }
            add(*rows.toTypedArray())

            val seen = mutableListOf<UUID>()
            var current = page(limit = 2)
            seen += current.entries.map { it.transaction.id }
            while (current.next != null) {
                current = page(limit = 2, after = current)
                seen += current.entries.map { it.transaction.id }
            }

            assertEquals(rows.map { it.id }.toSet(), seen.toSet())
            assertEquals(rows.size, seen.size)
        }

    @Test
    fun `names come from one batched profile lookup per page, an outside address carries no person`() =
        runBlocking<Unit> {
            val cara = UserId.new()
            val gone = UserId.new()
            profiles.known[bob] = PublicProfile(bob, "Bob", "bob", "https://cdn/bob.jpg", null)
            profiles.known[cara] = PublicProfile(cara, "Cara", null, null, null)
            add(
                row("2026-10-20T10:00:00Z"),
                row("2026-10-21T10:00:00Z", direction = Direction.IN),
                row("2026-10-22T10:00:00Z", counterpartyId = cara),
                row("2026-10-23T10:00:00Z", counterpartyId = gone),
                row("2026-10-24T10:00:00Z", counterpartyId = null, address = "0xoutside"),
            )

            val entries = page().entries

            assertEquals(1, profiles.calls.size, "one lookup for the page")
            assertEquals(setOf(bob, cara, gone), profiles.calls.single())
            val byAddress = entries.single { it.transaction.counterpartyId == null }.counterparty
            assertNull(byAddress.userId)
            assertNull(byAddress.displayName)
            assertEquals("0xoutside", byAddress.address)
            val toBob = entries.first { it.transaction.counterpartyId == bob }.counterparty
            assertEquals("Bob", toBob.displayName)
            assertEquals("bob", toBob.handle)
            assertEquals("https://cdn/bob.jpg", toBob.avatarUrl)
            val toGone = entries.single { it.transaction.counterpartyId == gone }.counterparty
            assertEquals(gone, toGone.userId)
            assertNull(toGone.displayName, "a deleted account keeps its id, not a name")
        }

    @Test
    fun `a payment known only by address is named when that address is a Circaso wallet`() =
        runBlocking<Unit> {
            val wallets = MemoryWalletRepository()
            wallets.insertWallet(
                ChainWallet("w-bob", bob, "BASE-SEPOLIA", "SCA", "0xb0b", ChainWalletState.ACTIVE, Instant.EPOCH),
            )
            wallets.insertWallet(
                ChainWallet("w-own", amina, "BASE-SEPOLIA", "SCA", "0xown", ChainWalletState.ACTIVE, Instant.EPOCH),
            )
            profiles.known[bob] = PublicProfile(bob, "Bob", "bob", null, null)
            val named =
                ListTransactions(transactions, Counterparties(profiles, wallets), FakeRates(), personalHolders())
            add(
                row("2026-10-20T10:00:00Z", counterpartyId = null, address = "0xb0b"),
                row("2026-10-21T10:00:00Z", counterpartyId = null, address = "0xoutside"),
                row("2026-10-22T10:00:00Z", counterpartyId = null, address = "0xown"),
            )

            val entries =
                named(
                    HistoryQuery(amina, null, null, 50),
                ).data().entries.associateBy { it.counterparty.address }

            assertEquals(bob, entries.getValue("0xb0b").counterparty.userId)
            assertEquals("Bob", entries.getValue("0xb0b").counterparty.displayName)
            assertEquals("bob", entries.getValue("0xb0b").counterparty.handle)
            assertNull(entries.getValue("0xoutside").counterparty.userId)
            assertNull(
                entries.getValue("0xown").counterparty.userId,
                "moving money between your own wallets names nobody",
            )
        }

    @Test
    fun `a page with nobody on it asks users nothing`() =
        runBlocking<Unit> {
            add(row("2026-10-20T10:00:00Z", counterpartyId = null))

            page()

            assertTrue(profiles.calls.isEmpty())
        }

    @Test
    fun `the query string is checked for shape and the cursor round-trips`() =
        runBlocking<Unit> {
            assertEquals(50, historyQuery(amina, null, null, null).data().limit)
            assertEquals(200, historyQuery(amina, null, null, "1000").data().limit)
            assertEquals(YearMonth.of(2026, 10), historyQuery(amina, "2026-10", null, null).data().month)
            listOf("2026-13", "2026-1", "October", "2026-10-01").forEach { month ->
                assertMalformed(historyQuery(amina, month, null, null), "month")
            }
            assertMalformed(historyQuery(amina, null, "not-a-cursor", null), "cursor")
            assertMalformed(historyQuery(amina, null, null, "0"), "limit")
            assertMalformed(historyQuery(amina, null, null, "ten"), "limit")

            add(row("2026-10-20T10:00:00.123456Z"), row("2026-10-21T10:00:00Z"))
            val first = page(limit = 1)
            val cursor = first.toResponse().nextCursor!!
            val parsed = historyQuery(amina, null, cursor, "1").data()
            assertEquals(first.next, parsed.after)
            val response = list(parsed).data().toResponse()
            assertNull(response.nextCursor)
            val item = response.transactions.single()
            assertEquals("OUT", item.direction)
            assertEquals("ASHA", item.origin)
            assertEquals("20000000", item.amount.amountMinor)
            assertEquals("20", item.amount.amount)
            assertEquals(bob, item.counterparty.userId)
        }

    @Test
    fun `origin is only shown on payments sent`() =
        runBlocking<Unit> {
            add(row("2026-10-20T10:00:00Z", direction = Direction.IN))

            val item = page().toResponse().transactions.single()

            assertEquals("IN", item.direction)
            assertNull(item.origin)
            assertNull(item.transferId)
        }

    private fun assertMalformed(result: Resource<*>, field: String) {
        val error = assertIs<Resource.Error>(result)
        assertEquals("request.malformed", error.error.code)
        assertEquals(
            field,
            error.error.details
                ?.fields
                ?.single()
                ?.field,
        )
    }
}

/** Users' profiles, recording each batch asked for. */
private class CountingProfiles : Profiles {
    val known = mutableMapOf<UserId, PublicProfile>()
    val calls = mutableListOf<Set<UserId>>()

    override suspend fun get(ids: Set<UserId>): Map<UserId, PublicProfile> {
        calls += ids
        return ids.mapNotNull { id -> known[id]?.let { id to it } }.toMap()
    }
}
