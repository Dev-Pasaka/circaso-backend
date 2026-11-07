package com.circaso.support

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.shared.decodeData
import com.circaso.support.api.AssigneeFilter
import com.circaso.support.api.SupportFrames
import com.circaso.support.api.TicketAuthor
import com.circaso.support.api.TicketCategory
import com.circaso.support.api.TicketContext
import com.circaso.support.api.TicketCounts
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketRepliedFrame
import com.circaso.support.api.TicketSource
import com.circaso.support.api.TicketStatus
import com.circaso.support.api.TicketUpdatedFrame
import com.circaso.support.api.WaitingOn
import com.circaso.support.data.cache.RealtimeFramesImpl
import com.circaso.support.data.repositories.DatabaseTransactor
import com.circaso.support.data.repositories.MessageRepositoryImpl
import com.circaso.support.data.repositories.SupportExpiry
import com.circaso.support.data.repositories.TicketRepositoryImpl
import com.circaso.support.di.supportDatabaseSpec
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.support.OrderedIds
import com.circaso.support.domain.support.madeAt
import com.circaso.support.domain.support.message
import com.circaso.support.domain.usecases.ReplayFrames
import com.circaso.support.presentation.api.SupportRealtimeReplay
import com.circaso.support.presentation.services.RealtimeService
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `support` on PostgreSQL, migrated by its own Flyway scripts: numbering, the conditional changes, the queue's
 * orderings and filters, the counts, the cascade on account deletion, a rollback, the kept frames and their replay.
 */
class SupportDataTest {
    private val database = TestPostgres.database(supportDatabaseSpec)
    private val tickets = TicketRepositoryImpl(database)
    private val messages = MessageRepositoryImpl(database)
    private val transactor = DatabaseTransactor(database)
    private val ids = OrderedIds(Clock.systemUTC())
    private val amina = UserId.new()
    private val baraka = UserId.new()
    private val agent = UUID.randomUUID()

    @BeforeTest
    fun clean() = TestPostgres.truncate("support")

    private fun ticket(
        user: UserId = amina,
        category: TicketCategory = TicketCategory.PAYMENTS,
        at: Instant? = null,
    ): Ticket {
        val id = if (at == null) ids.next() else Uuid7.generate(at)
        val now = id.madeAt()
        return Ticket(
            ticketId = TicketId(id),
            number = 0,
            userId = user,
            subject = "Payment not received",
            description = "I sent 20 USDC yesterday and it has not arrived.",
            category = category,
            status = TicketStatus.OPEN,
            source = TicketSource.ASHA,
            context = TicketContext(transferId = UUID.randomUUID()),
            assigneeId = null,
            waitingOn = WaitingOn.STAFF,
            createdAt = now,
            updatedAt = now,
            lastMessageAt = now,
            version = 0,
        )
    }

    @Test
    fun `a ticket reads back as written, numbered by the database in order`() =
        runBlocking<Unit> {
            val first = tickets.insert(ticket())
            val second = tickets.insert(ticket())

            assertEquals(first, tickets.find(first.ticketId))
            assertEquals(first.number + 1, second.number)
            assertTrue(first.number > 0)
            assertNull(tickets.find(TicketId.new()))
        }

    @Test
    fun `the user's list is newest first and pages by id`() =
        runBlocking<Unit> {
            val mine = (1..3).map { tickets.insert(ticket()) }
            tickets.insert(ticket(baraka))

            assertEquals(mine.reversed(), tickets.listOfUser(amina, null, 10))
            assertEquals(listOf(mine[0]), tickets.listOfUser(amina, mine[1].ticketId, 10))
        }

    @Test
    fun `changes are conditional - a closed ticket takes no reply, someone else's no user reply`() =
        runBlocking<Unit> {
            val open = tickets.insert(ticket())
            val now = Instant.now()

            val replied = tickets.staffReplied(open.ticketId, now)!!
            assertEquals(TicketStatus.PENDING_USER, replied.status)
            assertEquals(WaitingOn.USER, replied.waitingOn)
            assertEquals(1, replied.version)
            assertNull(tickets.userReplied(open.ticketId, baraka, now))
            assertEquals(TicketStatus.OPEN, tickets.userReplied(open.ticketId, amina, now)!!.status)

            val closed = tickets.changeStatus(open.ticketId, TicketStatus.CLOSED, now)!!
            assertNull(closed.waitingOn)
            assertNull(tickets.changeStatus(open.ticketId, TicketStatus.CLOSED, now), "already closed")
            assertNull(tickets.staffReplied(open.ticketId, now))
            assertNull(tickets.userReplied(open.ticketId, amina, now))
            assertEquals(closed.lastMessageAt, tickets.noted(open.ticketId, now.plusSeconds(60))!!.lastMessageAt)
            assertEquals(agent, tickets.assign(open.ticketId, agent, now)!!.assigneeId)
            assertNull(tickets.assign(TicketId.new(), agent, now))
        }

    @Test
    fun `messages read oldest first, notes only for staff, and a failed reply rolls back`() =
        runBlocking<Unit> {
            val open = tickets.insert(ticket())
            val reply = message(ids, TicketAuthor.STAFF, "We're looking", staffId = agent)
            val note = message(ids, TicketAuthor.STAFF, "Base RPC was down", staffId = agent, isInternal = true)
            val answer = message(ids, TicketAuthor.USER, "Thanks")
            listOf(reply, note, answer).forEach { messages.insert(open.ticketId, it) }

            assertEquals(listOf(reply, note, answer), messages.of(open.ticketId, withNotes = true))
            assertEquals(listOf(reply, answer), messages.of(open.ticketId, withNotes = false))

            val result =
                transactor.attempt("test.rollback") {
                    tickets.staffReplied(open.ticketId, Instant.now())
                    messages.insert(open.ticketId, message(ids, TicketAuthor.STAFF, "lost"))
                    Resource.error(SupportError.TicketClosed)
                }
            assertTrue(result is Resource.Error)
            assertEquals(TicketStatus.OPEN, tickets.find(open.ticketId)!!.status)
            assertEquals(3, messages.of(open.ticketId, withNotes = true).size)
        }

    @Test
    fun `the queue orders open statuses oldest first and the rest newest first, and filters`() =
        runBlocking<Unit> {
            val long = Instant.now().minus(Duration.ofDays(3))
            val old = tickets.insert(ticket(category = TicketCategory.WALLET, at = long))
            val middle = tickets.insert(ticket(baraka))
            val recent = tickets.insert(ticket())
            tickets.assign(middle.ticketId, agent, Instant.now())
            tickets.changeStatus(recent.ticketId, TicketStatus.RESOLVED, Instant.now())

            suspend fun queue(filter: TicketQueueFilter, after: TicketId? = null) =
                tickets.queue(filter, after, 10).map { it.ticketId }

            val open = TicketQueueFilter(status = TicketStatus.OPEN)
            assertEquals(listOf(old.ticketId, middle.ticketId), queue(open))
            assertEquals(listOf(middle.ticketId), queue(open, old.ticketId))
            assertEquals(listOf(recent.ticketId, middle.ticketId, old.ticketId), queue(TicketQueueFilter()))
            assertEquals(listOf(old.ticketId), queue(TicketQueueFilter(), middle.ticketId))
            assertEquals(listOf(recent.ticketId), queue(TicketQueueFilter(status = TicketStatus.RESOLVED)))
            assertEquals(listOf(old.ticketId), queue(TicketQueueFilter(category = TicketCategory.WALLET)))
            assertEquals(listOf(middle.ticketId), queue(TicketQueueFilter(assignee = AssigneeFilter.Staff(agent))))
            assertEquals(
                listOf(old.ticketId),
                queue(open.copy(assignee = AssigneeFilter.Unassigned)),
            )
            assertEquals(
                listOf(old.ticketId),
                queue(open.copy(createdBefore = Instant.now().minus(Duration.ofDays(1)))),
            )
        }

    @Test
    fun `the counts are open, waiting on the user, and open with nobody on it`() =
        runBlocking<Unit> {
            val (a, b, c) = (1..4).map { tickets.insert(ticket()) }
            tickets.assign(a.ticketId, agent, Instant.now())
            tickets.staffReplied(b.ticketId, Instant.now())
            tickets.changeStatus(c.ticketId, TicketStatus.CLOSED, Instant.now())

            assertEquals(TicketCounts(open = 2, pendingUser = 1, unassigned = 1), tickets.counts())
        }

    @Test
    fun `a deleted account's tickets go, their messages by the cascade`() =
        runBlocking<Unit> {
            val mine = tickets.insert(ticket())
            val theirs = tickets.insert(ticket(baraka))
            messages.insert(mine.ticketId, message(ids, TicketAuthor.USER, "hello"))

            assertEquals(1, tickets.deleteOfUser(amina))
            assertEquals(0, tickets.deleteOfUser(amina))
            assertNull(tickets.find(mine.ticketId))
            assertTrue(messages.of(mine.ticketId, withNotes = true).isEmpty())
            assertEquals(theirs, tickets.find(theirs.ticketId))
            assertEquals(0, rowsIn("support.messages"))
        }

    @Test
    fun `frames are kept with a seq per user, delivered, replayed, and a gap past what is kept is a resync`() =
        runBlocking<Unit> {
            val redis = MemoryRedis()
            val delivered = CopyOnWriteArrayList<Pair<UserId, RealtimeEnvelope>>()
            val realtime =
                object : Realtime {
                    override suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope): Resource<Unit> {
                        delivered += userId to envelope
                        return Resource.success(Unit)
                    }

                    override suspend fun deliverMany(userIds: Collection<UserId>, envelope: RealtimeEnvelope) =
                        error("support frames go to one user")
                }
            val frames = RealtimeFramesImpl(redis, database, realtime, Clock.systemUTC())
            val replay = SupportRealtimeReplay(RealtimeService(ReplayFrames(frames)))
            val ticketId = TicketId.new()

            frames.send(amina, TicketRepliedFrame(ticketId, "CS-000007", TicketStatus.PENDING_USER))
            frames.send(amina, TicketUpdatedFrame(ticketId, "CS-000007", TicketStatus.RESOLVED))
            frames.send(baraka, TicketUpdatedFrame(ticketId, "CS-000008", TicketStatus.CLOSED))

            assertEquals(listOf(1L, 2L, 1L), delivered.map { it.second.seq })
            assertEquals(Duration.ofDays(8), redis.ttls["seq:$amina"])
            val first = delivered.first().second
            assertEquals(SupportFrames.MODULE, first.module)
            assertEquals("ticket.replied", first.type)
            assertEquals(
                TicketRepliedFrame(ticketId, "CS-000007", TicketStatus.PENDING_USER),
                first.decodeData(TicketRepliedFrame.serializer()),
            )

            val missed = (replay.since(amina, 1) as Resource.Success).data
            assertEquals(listOf(delivered[1].second), missed)
            assertTrue((replay.since(amina, 2) as Resource.Success).data.isEmpty())

            TestPostgres.truncate("support") // the kept frames are gone, the seq is not: a gap past what is kept
            val resync = (replay.since(amina, 1) as Resource.Success).data.single()
            assertEquals("resync", resync.type)
            assertEquals(SupportFrames.MODULE, resync.module)
            assertTrue(SupportExpiry(database).run() >= 0)
        }

    private fun rowsIn(table: String): Int =
        TestPostgres.superuser { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
}
