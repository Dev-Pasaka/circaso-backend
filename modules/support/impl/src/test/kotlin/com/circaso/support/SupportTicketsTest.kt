package com.circaso.support

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.AssigneeFilter
import com.circaso.support.api.SupportFrame
import com.circaso.support.api.NewTicket
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
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.support.SupportConfig
import com.circaso.support.presentation.mappers.toResponse
import com.circaso.wallet.api.TransferId
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ticket rules over in-memory fakes: opening, the user's replies, the staff desk, the queue and the counts. */
class SupportTicketsTest {
    private val amina = UserId.new()
    private val baraka = UserId.new()
    private val agent = UUID.randomUUID()

    private fun request(
        user: UserId = amina,
        subject: String = "Payment not received",
        description: String = "I sent 20 USDC to Baraka yesterday and he has not received it.",
        context: TicketContext = TicketContext(),
        source: TicketSource = TicketSource.APP,
        category: TicketCategory = TicketCategory.PAYMENTS,
    ) = NewTicket(user, subject, description, category, context, source)

    private suspend fun SupportHarness.opened(user: UserId = amina): Ticket = service.open(request(user)).data()

    @Test
    fun `a new ticket is open, waiting on staff, numbered in order and trimmed`() =
        runTest {
            val h = SupportHarness()
            val first = h.service.open(request(subject = "  Payment\nnot received  ")).data()
            val second = h.opened()

            assertEquals(TicketStatus.OPEN, first.status)
            assertEquals(WaitingOn.STAFF, first.waitingOn)
            assertEquals("Payment not received", first.subject)
            assertEquals(1, first.number)
            assertEquals(2, second.number)
            assertEquals(TicketSource.APP, first.source)
            assertTrue(first.ticketId.value < second.ticketId.value, "ids sort in the order they were made")
            assertEquals(first.createdAt, first.lastMessageAt)
        }

    @Test
    fun `a subject or description out of range, or only blanks and control characters, is refused`() =
        runTest {
            val h = SupportHarness()
            h.service.open(request(subject = "ab")).assertError(SupportError.SubjectInvalid)
            h.service.open(request(subject = "x".repeat(121))).assertError(SupportError.SubjectInvalid)
            h.service.open(request(subject = "\u0000\u0007  ")).assertError(SupportError.SubjectInvalid)
            h.service.open(request(description = "too short")).assertError(SupportError.DescriptionInvalid)
            h.service.open(request(description = "x".repeat(4_001))).assertError(SupportError.DescriptionInvalid)
            assertTrue(h.tickets.rows.isEmpty())
        }

    @Test
    fun `a transfer must be one the user sent, received or signed, and a chat one they are in`() =
        runTest {
            val h = SupportHarness()
            val sent = h.wallets.payment(from = amina, to = baraka)
            val received = h.wallets.payment(from = baraka, to = amina)
            val signed = h.wallets.payment(from = UserId.new(), to = baraka, signer = amina)
            val others = h.wallets.payment(from = baraka, to = UserId.new())
            val chat = ChatId.new()
            h.chats.members += chat to amina
            val strangersChat = ChatId.new()

            listOf(sent, received, signed).forEach {
                h.service.open(request(context = TicketContext(transferId = it.value))).data()
            }
            h.service.open(request(context = TicketContext(chatId = chat.value))).data()
            h.service
                .open(request(context = TicketContext(transferId = others.value)))
                .assertError(SupportError.ContextInvalid)
            h.service
                .open(request(context = TicketContext(transferId = TransferId.new().value)))
                .assertError(SupportError.ContextInvalid)
            h.service
                .open(request(context = TicketContext(chatId = strangersChat.value)))
                .assertError(SupportError.ContextInvalid)
            assertEquals(4, h.tickets.rows.size)
        }

    @Test
    fun `at most five new tickets a day, app and Asha together, and the window passes`() =
        runTest {
            val h = SupportHarness()
            repeat(3) { h.opened() }
            repeat(2) { h.asha.open(request(source = TicketSource.ASHA)).data() }

            val refused = h.service.open(request())
            refused.assertError(SupportError.RateLimited(1))
            val wait = (refused as Resource.Error).error.details?.retryAfterSeconds
            assertEquals(Duration.ofDays(1).seconds, wait)
            h.service.open(request(user = baraka)).data() // someone else's day is their own

            h.clock.advance(Duration.ofDays(1))
            h.opened()
            assertEquals(7, h.tickets.rows.size)
        }

    @Test
    fun `a ticket refused for its text or context does not use up the day`() =
        runTest {
            val h = SupportHarness()
            repeat(10) { h.service.open(request(subject = "x")) }
            repeat(5) { h.opened() }
            assertEquals(5, h.tickets.rows.size)
        }

    @Test
    fun `Asha opens a ticket with source ASHA and gets its number to tell the user`() =
        runTest {
            val h = SupportHarness()
            h.opened()
            val opened = h.asha.open(request(source = TicketSource.ASHA)).data()

            assertEquals("CS-000002", opened.number)
            assertEquals(
                TicketSource.ASHA,
                h.tickets.rows
                    .getValue(opened.ticketId)
                    .source,
            )
        }

    @Test
    fun `the user's list is theirs alone, newest first, a page at a time`() =
        runTest {
            val h = SupportHarness(SupportConfig(ticketsPerDay = 50))
            val mine = (1..5).map { h.opened() }
            h.opened(baraka)

            val first = h.service.list(amina, null, 2).data()
            assertEquals(mine.reversed().take(2).map { it.ticketId }, first.tickets.map { it.ticketId })
            val second = h.service.list(amina, first.next, 2).data()
            assertEquals(
                mine
                    .reversed()
                    .drop(2)
                    .take(2)
                    .map { it.ticketId },
                second.tickets.map { it.ticketId },
            )
            val last = h.service.list(amina, second.next, 2).data()
            assertEquals(listOf(mine.first().ticketId), last.tickets.map { it.ticketId })
            assertNull(last.next)
        }

    @Test
    fun `someone else's ticket is not found, to read or to reply on`() =
        runTest {
            val h = SupportHarness()
            val ticket = h.opened()

            h.service.get(baraka, ticket.ticketId).assertError(SupportError.TicketNotFound)
            h.service.reply(baraka, ticket.ticketId, "hello").assertError(SupportError.TicketNotFound)
            h.service.get(amina, TicketId.new()).assertError(SupportError.TicketNotFound)
            assertTrue(h.messages.rows.isEmpty())
        }

    @Test
    fun `a staff reply waits on the user and sends a frame with no text, and the user's reply opens it again`() =
        runTest {
            val h = SupportHarness()
            val ticket = h.opened()

            val replied = h.desk.reply(ticket.ticketId, agent, "Can you send the transfer id?").data()
            assertEquals(TicketStatus.PENDING_USER, replied.summary.status)
            assertEquals(WaitingOn.USER, replied.summary.waitingOn)
            assertEquals(agent, replied.messages.single().staffId)
            assertEquals<List<Pair<UserId, SupportFrame>>>(
                listOf(amina to TicketRepliedFrame(ticket.ticketId, "CS-000001", TicketStatus.PENDING_USER)),
                h.frames.sent,
            )

            val reopened = h.service.reply(amina, ticket.ticketId, "  Here it is: 0193…  ").data()
            assertEquals(TicketStatus.OPEN, reopened.ticket.status)
            assertEquals(WaitingOn.STAFF, reopened.ticket.waitingOn)
            assertEquals(listOf(TicketAuthor.STAFF, TicketAuthor.USER), reopened.messages.map { it.author })
            assertEquals("Here it is: 0193…", reopened.messages.last().body)
            assertEquals(1, h.frames.sent.size, "the user's own reply sends them nothing")
        }

    @Test
    fun `a resolved ticket opens again when the user replies, a closed one refuses and stays as it was`() =
        runTest {
            val h = SupportHarness()
            val ticket = h.opened()
            h.desk.changeStatus(ticket.ticketId, TicketStatus.RESOLVED).data()

            val reopened = h.service.reply(amina, ticket.ticketId, "Still broken").data()
            assertEquals(TicketStatus.OPEN, reopened.ticket.status)

            h.desk.changeStatus(ticket.ticketId, TicketStatus.CLOSED).data()
            val before = h.tickets.rows.getValue(ticket.ticketId)
            h.service.reply(amina, ticket.ticketId, "Hello?").assertError(SupportError.TicketClosed)
            h.desk.reply(ticket.ticketId, agent, "Hi").assertError(SupportError.TicketClosed)
            assertEquals(before, h.tickets.rows.getValue(ticket.ticketId))
            assertEquals(1, h.messages.rows.size)
        }

    @Test
    fun `a reply's body must be 1 to 4000 characters`() =
        runTest {
            val h = SupportHarness()
            val ticket = h.opened()
            h.service.reply(amina, ticket.ticketId, "   ").assertError(SupportError.BodyInvalid)
            h.service.reply(amina, ticket.ticketId, "x".repeat(4_001)).assertError(SupportError.BodyInvalid)
            h.desk.reply(ticket.ticketId, agent, "").assertError(SupportError.BodyInvalid)
            h.desk.note(ticket.ticketId, agent, "").assertError(SupportError.BodyInvalid)
            h.service.reply(amina, ticket.ticketId, "x".repeat(4_000)).data()
        }

    @Test
    fun `a note is staff only - no frame, no change the user can see, and hidden from their view`() =
        runTest {
            val h = SupportHarness()
            val ticket = h.opened()
            h.clock.advance(Duration.ofMinutes(5))

            val noted = h.desk.note(ticket.ticketId, agent, "Looks like a stuck Base transfer").data()
            assertTrue(noted.messages.single().internal)
            assertTrue(h.frames.sent.isEmpty())
            val row = h.tickets.rows.getValue(ticket.ticketId)
            assertEquals(ticket.lastMessageAt, row.lastMessageAt)
            assertEquals(TicketStatus.OPEN, row.status)

            val users = h.service.get(amina, ticket.ticketId).data()
            assertTrue(users.messages.isEmpty())
            assertTrue(users.toResponse().messages.isEmpty())
            assertEquals(
                1,
                h.desk
                    .ticket(ticket.ticketId)
                    .data()
                    .messages.size,
            )
            h.desk.note(TicketId.new(), agent, "x").assertError(SupportError.TicketNotFound)
        }

    @Test
    fun `the user sees staff as Circaso Support, never a staff id`() =
        runTest {
            val h = SupportHarness()
            val ticket = h.opened()
            h.desk.reply(ticket.ticketId, agent, "We're on it.").data()

            val message =
                h.service
                    .get(amina, ticket.ticketId)
                    .data()
                    .toResponse()
                    .messages
                    .single()
            assertEquals("STAFF", message.author)
            assertEquals("Circaso Support", message.authorName)
            assertTrue(agent.toString() !in message.toString())
        }

    @Test
    fun `assigning sends nothing, and unassigning clears it`() =
        runTest {
            val h = SupportHarness()
            val ticket = h.opened()

            assertEquals(
                agent,
                h.desk
                    .assign(ticket.ticketId, agent)
                    .data()
                    .summary.assigneeId,
            )
            assertNull(
                h.desk
                    .assign(ticket.ticketId, null)
                    .data()
                    .summary.assigneeId,
            )
            assertTrue(h.frames.sent.isEmpty())
            h.desk.assign(TicketId.new(), agent).assertError(SupportError.TicketNotFound)
        }

    @Test
    fun `a status change sends ticket updated once, PENDING_USER can't be set by hand, closed can be reopened`() =
        runTest {
            val h = SupportHarness()
            val ticket = h.opened()

            val resolved = h.desk.changeStatus(ticket.ticketId, TicketStatus.RESOLVED).data()
            assertNull(resolved.summary.waitingOn)
            h.desk.changeStatus(ticket.ticketId, TicketStatus.RESOLVED).data() // the same again: no frame
            h.desk.changeStatus(ticket.ticketId, TicketStatus.PENDING_USER).assertError(SupportError.StatusInvalid)
            h.desk.changeStatus(ticket.ticketId, TicketStatus.CLOSED).data()
            val reopened = h.desk.changeStatus(ticket.ticketId, TicketStatus.OPEN).data()
            assertEquals(WaitingOn.STAFF, reopened.summary.waitingOn)
            h.desk.changeStatus(TicketId.new(), TicketStatus.OPEN).assertError(SupportError.TicketNotFound)

            assertEquals<List<Pair<UserId, SupportFrame>>>(
                listOf(TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.OPEN).map {
                    amina to TicketUpdatedFrame(ticket.ticketId, "CS-000001", it)
                },
                h.frames.sent,
            )
        }

    @Test
    fun `the queue puts the longest wait on top for open statuses, newest first otherwise, and pages`() =
        runTest {
            val h = SupportHarness(SupportConfig(ticketsPerDay = 50))
            val all = (1..5).map { h.opened(if (it % 2 == 0) baraka else amina) }
            h.desk.changeStatus(all[4].ticketId, TicketStatus.RESOLVED).data()

            val open = h.desk.queue(TicketQueueFilter(status = TicketStatus.OPEN), null, 2).data()
            assertEquals(all.take(2).map { it.ticketId }, open.items.map { it.ticketId })
            val rest = h.desk.queue(TicketQueueFilter(status = TicketStatus.OPEN), open.nextCursor, 2).data()
            assertEquals(all.drop(2).take(2).map { it.ticketId }, rest.items.map { it.ticketId })
            assertNull(rest.nextCursor)

            val everything = h.desk.queue(TicketQueueFilter(), null, 10).data()
            assertEquals(all.reversed().map { it.ticketId }, everything.items.map { it.ticketId })
        }

    @Test
    fun `the queue filters by category, assignee, nobody, and age`() =
        runTest {
            val h = SupportHarness(SupportConfig(ticketsPerDay = 50))
            val old = h.service.open(request(category = TicketCategory.WALLET)).data()
            h.clock.advance(Duration.ofHours(30))
            val recent = h.opened()
            h.desk.assign(recent.ticketId, agent).data()

            suspend fun ids(filter: TicketQueueFilter) =
                h.desk
                    .queue(filter, null, 10)
                    .data()
                    .items
                    .map { it.ticketId }

            assertEquals(listOf(old.ticketId), ids(TicketQueueFilter(category = TicketCategory.WALLET)))
            assertEquals(listOf(recent.ticketId), ids(TicketQueueFilter(assignee = AssigneeFilter.Staff(agent))))
            assertEquals(listOf(old.ticketId), ids(TicketQueueFilter(assignee = AssigneeFilter.Unassigned)))
            assertEquals(
                listOf(old.ticketId),
                ids(TicketQueueFilter(TicketStatus.OPEN, createdBefore = h.clock.now.minus(Duration.ofDays(1)))),
            )
        }

    @Test
    fun `a bad cursor or limit on the queue is malformed`() =
        runTest {
            val h = SupportHarness()
            assertEquals("request.malformed", h.desk.queue(TicketQueueFilter(), "not-a-cursor!", 10).code())
            assertEquals("request.malformed", h.desk.queue(TicketQueueFilter(), null, 0).code())
            assertEquals("request.malformed", h.desk.queue(TicketQueueFilter(), null, 201).code())
        }

    @Test
    fun `the counts are open, waiting on the user, and open with nobody on it`() =
        runTest {
            val h = SupportHarness()
            val (a, b, c, d) = (1..4).map { h.opened() }
            h.desk.assign(a.ticketId, agent).data()
            h.desk.reply(b.ticketId, agent, "Over to you").data()
            h.desk.changeStatus(c.ticketId, TicketStatus.CLOSED).data()

            assertEquals(TicketCounts(open = 2, pendingUser = 1, unassigned = 1), h.desk.counts().data())
            assertEquals(
                d.ticketId,
                h.desk
                    .ofUser(amina, 1)
                    .data()
                    .single()
                    .ticketId,
            )
        }

    @Test
    fun `a deleted account's tickets and their messages all go, and again is harmless`() =
        runTest {
            val h = SupportHarness()
            val ticket = h.opened()
            val other = h.opened(baraka)
            h.desk.reply(ticket.ticketId, agent, "Hi").data()

            h.removeAccount(amina).data()
            h.removeAccount(amina).data()

            assertEquals(
                listOf(other.ticketId),
                h.tickets.rows.keys
                    .toList(),
            )
            assertTrue(h.messages.of(ticket.ticketId, withNotes = true).isEmpty())
        }
}
