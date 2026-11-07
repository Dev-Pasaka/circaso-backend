package com.circaso.share

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.share.api.ShareCode
import com.circaso.share.data.repositories.LinkRepositoryImpl
import com.circaso.share.data.repositories.ShareExpiry
import com.circaso.share.di.shareDatabaseSpec
import com.circaso.share.domain.models.FiatValue
import com.circaso.share.domain.models.LinkCursor
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.models.StatementLine
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `share.links` on PostgreSQL, migrated by its own Flyway script: the snapshot round trip, revoking, paging, purging. */
class LinkDataTest {
    private val database = TestPostgres.database(shareDatabaseSpec)
    private val links = LinkRepositoryImpl(database)
    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    private val owner = UserId.new()

    @BeforeTest
    fun clean() = TestPostgres.truncate("share")

    private fun link(
        content: ShareContent,
        createdAt: Instant = now,
        endsAt: Instant? = null,
        kind: ShareKind = ShareKind.RECEIPT,
    ) = ShareLink(ShareCode.new(), owner, kind, content, createdAt, endsAt, null)

    private val receipt =
        ShareContent.Receipt(
            transferId = TransferId.new(),
            isSent = true,
            amount = "12.5",
            asset = "USDC",
            fiat = FiatValue("KES", BigDecimal("1612.50"), now),
            status = "SUBMITTED",
            blockchain = "BASE-SEPOLIA",
            txHash = null,
            at = now,
            fromName = "Wanjiru",
            toName = null,
        )

    @Test
    fun `every kind of snapshot reads back as written, and a taken code isn't overwritten`() =
        runBlocking<Unit> {
            val statement =
                ShareContent.Statement(
                    ChatId.new(),
                    "Holiday Fund",
                    500,
                    125,
                    20,
                    105,
                    2,
                    now,
                    null,
                    listOf(StatementLine(now, true, 125, "Juma", null)),
                    now,
                )
            val person = ShareContent.Person(owner, "Wanjiru", "wanjiru", "token")
            val address = ShareContent.Address("BASE", "0xabc", null)
            for (content in listOf(receipt, statement, person, address)) {
                val l = link(content)
                assertTrue(links.insert(l, null))
                assertEquals(l, links.find(l.code, now))
                assertFalse(links.insert(l.copy(content = address), null))
            }
        }

    @Test
    fun `revoking clears the snapshot, keeps the row until its time, then the purge takes it`() =
        runBlocking<Unit> {
            val l = link(receipt)
            links.insert(l, null)
            assertFalse(links.revoke(l.code, UserId.new(), now, now.plusSeconds(60)), "someone else's")
            assertTrue(links.revoke(l.code, owner, now, now.plusSeconds(60)))
            assertFalse(links.revoke(l.code, owner, now, now.plusSeconds(60)), "already off")
            val gone = links.find(l.code, now)!!
            assertNull(gone.content)
            assertFalse(gone.isLive(now))
            assertFalse(links.updateContent(l.code, receipt), "a revoked link stays empty")
            assertNull(links.find(l.code, now.plusSeconds(61)))
        }

    @Test
    fun `the owner's live links page newest first and skip ended ones`() =
        runBlocking<Unit> {
            val a = link(receipt, now.minusSeconds(3))
            val b = link(receipt, now.minusSeconds(2))
            val c = link(receipt, now.minusSeconds(1))
            val ended = link(receipt, now, endsAt = now.minusSeconds(1))
            listOf(a, b, c, ended).forEach { links.insert(it, null) }
            val first = links.listLive(owner, now, null, 2)
            assertEquals(listOf(c.code, b.code), first.map { it.code })
            val rest = links.listLive(owner, now, LinkCursor(b.createdAt, b.code), 2)
            assertEquals(listOf(a.code), rest.map { it.code })
            assertEquals(4, links.deleteOwnedBy(owner))
        }

    @Test
    fun `the purge job deletes rows past their time`() =
        runBlocking<Unit> {
            val l = link(receipt)
            links.insert(l, expiresAt = Instant.now().minus(Duration.ofMinutes(1)))
            assertTrue(ShareExpiry(database).run() >= 1)
            assertNull(links.find(l.code, Instant.EPOCH))
        }
}
