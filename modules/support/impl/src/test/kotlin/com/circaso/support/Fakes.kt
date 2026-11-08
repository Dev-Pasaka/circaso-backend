package com.circaso.support

import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.AssigneeFilter
import com.circaso.support.api.SupportFrame
import com.circaso.support.api.TicketCounts
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketMessage
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketStatus
import com.circaso.support.api.WaitingOn
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.ports.FrameSender
import com.circaso.support.domain.ports.TicketAllowance
import com.circaso.support.domain.ports.Transactor
import com.circaso.support.domain.repositories.MessageRepository
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.repositories.isOldestFirst
import com.circaso.support.domain.support.OrderedIds
import com.circaso.support.domain.support.SupportConfig
import com.circaso.support.domain.usecases.AddStaffNote
import com.circaso.support.domain.usecases.AssignTicket
import com.circaso.support.domain.usecases.ChangeTicketStatus
import com.circaso.support.domain.usecases.CountTickets
import com.circaso.support.domain.usecases.GetMyTicket
import com.circaso.support.domain.usecases.GetTicket
import com.circaso.support.domain.usecases.ListMyTickets
import com.circaso.support.domain.usecases.ListTicketQueue
import com.circaso.support.domain.usecases.ListUserTickets
import com.circaso.support.domain.usecases.OpenTicket
import com.circaso.support.domain.usecases.RemoveAccountTickets
import com.circaso.support.domain.usecases.ReplyAsStaff
import com.circaso.support.domain.usecases.ReplyAsUser
import com.circaso.support.presentation.api.SupportDeskImpl
import com.circaso.support.presentation.api.SupportTicketsImpl
import com.circaso.support.presentation.services.SupportDeskService
import com.circaso.support.presentation.services.TicketService
import com.circaso.wallet.api.GroupWalletSummary
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.api.WalletAmount
import com.circaso.wallet.api.Wallets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `tickets` in memory, with the same conditions and orderings as the SQL. */
class MemoryTickets : TicketRepository {
    val rows = linkedMapOf<TicketId, Ticket>()
    private var lastNumber = 0L

    override suspend fun insert(ticket: Ticket): Ticket {
        val numbered = ticket.copy(number = ++lastNumber)
        rows[ticket.ticketId] = numbered
        return numbered
    }

    override suspend fun find(ticketId: TicketId) = rows[ticketId]

    override suspend fun listOfUser(
        userId: UserId,
        after: TicketId?,
        limit: Int,
    ) = rows.values
        .filter { it.userId == userId && (after == null || it.ticketId.value < after.value) }
        .sortedByDescending { it.ticketId.value }
        .take(limit)

    override suspend fun queue(
        filter: TicketQueueFilter,
        after: TicketId?,
        limit: Int,
    ): List<Ticket> {
        val oldestFirst = filter.isOldestFirst()
        val matching =
            rows.values.filter { ticket ->
                (filter.status == null || ticket.status == filter.status) &&
                    (filter.category == null || ticket.category == filter.category) &&
                    when (val assignee = filter.assignee) {
                        AssigneeFilter.Anyone -> true
                        AssigneeFilter.Unassigned -> ticket.assigneeId == null
                        is AssigneeFilter.Staff -> ticket.assigneeId == assignee.staffId
                    } &&
                    (filter.createdBefore == null || ticket.createdAt < filter.createdBefore) &&
                    (
                        after == null ||
                            if (oldestFirst) {
                                ticket.ticketId.value > after.value
                            } else {
                                ticket.ticketId.value <
                                    after.value
                            }
                    )
            }
        val sorted =
            if (oldestFirst) {
                matching.sortedBy {
                    it.ticketId.value
                }
            } else {
                matching.sortedByDescending { it.ticketId.value }
            }
        return sorted.take(limit)
    }

    private fun change(
        ticketId: TicketId,
        condition: (Ticket) -> Boolean,
        set: (Ticket) -> Ticket,
    ): Ticket? {
        val row = rows[ticketId]?.takeIf(condition) ?: return null
        return set(row).copy(version = row.version + 1).also { rows[ticketId] = it }
    }

    override suspend fun userReplied(
        ticketId: TicketId,
        userId: UserId,
        at: Instant,
    ) = change(ticketId, { it.userId == userId && it.status != TicketStatus.CLOSED }) {
        it.copy(status = TicketStatus.OPEN, waitingOn = WaitingOn.STAFF, updatedAt = at, lastMessageAt = at)
    }

    override suspend fun staffReplied(ticketId: TicketId, at: Instant) =
        change(ticketId, { it.status != TicketStatus.CLOSED }) {
            it.copy(status = TicketStatus.PENDING_USER, waitingOn = WaitingOn.USER, updatedAt = at, lastMessageAt = at)
        }

    override suspend fun noted(ticketId: TicketId, at: Instant) = change(ticketId, { true }) { it.copy(updatedAt = at) }

    override suspend fun assign(
        ticketId: TicketId,
        assigneeId: UUID?,
        at: Instant,
    ) = change(ticketId, { true }) { it.copy(assigneeId = assigneeId, updatedAt = at) }

    override suspend fun changeStatus(
        ticketId: TicketId,
        status: TicketStatus,
        at: Instant,
    ) = change(ticketId, { it.status != status }) {
        val waiting =
            when (status) {
                TicketStatus.OPEN -> WaitingOn.STAFF
                TicketStatus.PENDING_USER -> WaitingOn.USER
                else -> null
            }
        it.copy(status = status, waitingOn = waiting, updatedAt = at)
    }

    override suspend fun counts() =
        TicketCounts(
            open = rows.values.count { it.status == TicketStatus.OPEN }.toLong(),
            pendingUser = rows.values.count { it.status == TicketStatus.PENDING_USER }.toLong(),
            unassigned = rows.values.count { it.status == TicketStatus.OPEN && it.assigneeId == null }.toLong(),
        )

    override suspend fun deleteOfUser(userId: UserId): Int {
        val gone = rows.values.filter { it.userId == userId }.map { it.ticketId }
        gone.forEach { rows.remove(it) }
        return gone.size
    }
}

/** `messages` in memory; [tickets] stands in for the foreign key's cascade. */
class MemoryMessages(private val tickets: MemoryTickets) : MessageRepository {
    val rows = mutableListOf<Pair<TicketId, TicketMessage>>()

    override suspend fun insert(ticketId: TicketId, message: TicketMessage) {
        rows += ticketId to message
    }

    override suspend fun of(ticketId: TicketId, withNotes: Boolean) =
        rows
            .filter { it.first == ticketId && it.first in tickets.rows && (withNotes || !it.second.internal) }
            .map { it.second }
            .sortedBy { it.messageId }
}

/** Runs the block as if in a transaction: an error leaves both tables as they were. */
class MemoryTransactor(private val tickets: MemoryTickets, private val messages: MemoryMessages) : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = block()

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> {
        val savedTickets = LinkedHashMap(tickets.rows)
        val savedMessages = messages.rows.toList()
        val result = block()
        if (result is Resource.Error) {
            tickets.rows.clear()
            tickets.rows.putAll(savedTickets)
            messages.rows.clear()
            messages.rows.addAll(savedMessages)
        }
        return result
    }
}

class RecordingFrames : FrameSender {
    val sent = mutableListOf<Pair<UserId, SupportFrame>>()

    override suspend fun send(userId: UserId, frame: SupportFrame) {
        sent += userId to frame
    }
}

/** The daily cap in memory: a window per user that starts at its first ticket, on [clock]. */
class MemoryAllowance(private val clock: MovableClock) : TicketAllowance {
    private val windows = mutableMapOf<UserId, Pair<Instant, Long>>()

    override suspend fun take(
        userId: UserId,
        max: Long,
        window: Duration,
    ): Long? {
        val now = clock.instant()
        val (start, count) = windows[userId]?.takeIf { it.first.plus(window) > now } ?: (now to 0L)
        windows[userId] = start to count + 1
        if (count + 1 <= max) return null
        return Duration.between(now, start.plus(window)).seconds.coerceAtLeast(1)
    }
}

/** The transfers wallet knows, by id. */
class FakeWallets : Wallets {
    val transfers = mutableMapOf<TransferId, TransferSummary>()

    fun payment(
        from: UserId,
        to: UserId?,
        signer: UserId? = null,
    ): TransferId {
        val id = TransferId.new()
        transfers[id] =
            TransferSummary(
                transferId = id,
                senderId = from,
                recipientId = to,
                destinationAddress = "0xabc",
                amount = WalletAmount("USDC", "1000000", "1"),
                state = "CONFIRMED",
                chatId = null,
                signerId = signer,
            )
        return id
    }

    override suspend fun hasWallet(userId: UserId) = true

    override suspend fun transfer(transferId: TransferId) = transfers[transferId]

    override suspend fun groupWallet(chatId: ChatId): GroupWalletSummary? = null
}

/** Chat membership as a set of (chat, user) pairs. */
class FakeChats : ChatDirectory {
    val members = mutableSetOf<Pair<ChatId, UserId>>()

    override suspend fun isMember(chatId: ChatId, userId: UserId) = (chatId to userId) in members

    override suspend fun members(chatId: ChatId) = members.filter { it.first == chatId }.map { it.second }.toSet()

    override suspend fun admins(chatId: ChatId) = emptySet<UserId>()

    override suspend fun isGroup(chatId: ChatId) = true

    override suspend fun groupsOf(userId: UserId) = members.filter { it.second == userId }.map { it.first }

    override suspend fun owner(chatId: ChatId): UserId? = null
}

class MovableClock(var now: Instant = Instant.parse("2026-11-08T09:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** Support's use cases, services and contracts over in-memory fakes of every port. */
class SupportHarness(val config: SupportConfig = SupportConfig()) {
    val clock = MovableClock()
    val tickets = MemoryTickets()
    val messages = MemoryMessages(tickets)
    val transactor = MemoryTransactor(tickets, messages)
    val frames = RecordingFrames()
    val allowance = MemoryAllowance(clock)
    val wallets = FakeWallets()
    val chats = FakeChats()
    val ids = OrderedIds(clock)

    val service =
        TicketService(
            OpenTicket(tickets, wallets, chats, allowance, ids, config),
            ListMyTickets(tickets),
            GetMyTicket(tickets, messages),
            ReplyAsUser(tickets, messages, transactor, ids),
        )
    val desk =
        SupportDeskImpl(
            SupportDeskService(
                ListTicketQueue(tickets),
                GetTicket(tickets, messages),
                ListUserTickets(tickets),
                ReplyAsStaff(tickets, messages, transactor, frames, ids),
                AddStaffNote(tickets, messages, transactor, ids),
                AssignTicket(tickets, messages, clock),
                ChangeTicketStatus(tickets, messages, frames, clock),
                CountTickets(tickets),
            ),
        )
    val asha = SupportTicketsImpl(service)
    val removeAccount = RemoveAccountTickets(tickets)
}

fun <T> Resource<T>.data(): T = assertIs<Resource.Success<T>>(this).data

fun Resource<*>.assertError(expected: DomainError) {
    assertEquals(expected.code, assertIs<Resource.Error>(this).error.code)
}

fun Resource<*>.code(): String = assertIs<Resource.Error>(this).error.code
